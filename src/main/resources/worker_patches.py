"""Helper utilities for applying zarr compatibility patches.

This module provides helpers to patch zarr and related components to avoid
run-time errors with float-based slice indices, as well as a worker setup
routine for Dask.
"""

import math
import numbers
import sys

# Standard library or third-party imports (optional/guarded)
try:
    import numpy as np
except ImportError:
    np = None

try:
    import zarr
    import zarr.core
    import zarr.core.indexing as _z_idx
except ImportError:
    zarr = None
    _z_idx = None

try:
    import torch.utils.mkldnn as mkldnn
except (ImportError, AttributeError):
    mkldnn = None


def _coerce_slice(sel, dim_len=0, clamp=False, mode="expand"):
    """Robustly coerce a slice to integer start/stop/step.

    If `clamp` is True, bounds are clamped to [0, dim_len].
    This function handles float rounding.
    mode='expand': floor(start), ceil(stop) - safest for coverage (read).
    mode='nearest': round(start), round(stop) - safest for grid alignment (write).

    Parameters
    ----------
    sel : slice
        The input slice object.
    dim_len : int, optional
        Length of the dimension (used if clamp=True).
    clamp : bool, optional
        Whether to clamp bounds.
    mode : str, optional
        'expand' or 'nearest' (default 'expand').

    Returns
    -------
    slice
        A new slice object with integer components.
    """
    if sel.start is None:
        start = 0 if clamp else None
    else:
        try:
            val = float(sel.start)
            if mode == "nearest":
                start = int(round(val))
            else:
                start = int(math.floor(val))
        except Exception:
            start = 0 if clamp else None

    if sel.stop is None:
        stop = dim_len if clamp else None
    else:
        try:
            val = float(sel.stop)
            if mode == "nearest":
                # Shape-preserving rounding:
                # Calculate expected length and add to start to avoid 0.5+0.5=2 rounding errors
                if sel.start is not None:
                    # If we have a start, derive stop from length
                    try:
                        start_val = float(sel.start)
                        length = val - start_val
                        # Round length to nearest integer
                        len_int = int(round(length))
                        # Even if start shifted, we keep the length constant
                        stop = start + len_int if start is not None else int(round(val))
                    except Exception:
                        stop = int(round(val))
                else:
                    stop = int(round(val))
            else:
                stop = int(math.ceil(val))
        except Exception:
            stop = dim_len if clamp else None

    if sel.step is None:
        step = 1
    else:
        try:
            val = float(sel.step)
            # Ensure step is at least 1 if it's supposed to be positive
            # (assuming standard positive steps for blocks)
            step = int(max(1, round(val))) if val > 0 else int(val)
            if step == 0:
                step = 1
        except Exception:
            try:
                step = int(float(sel.step))
            except Exception:
                step = 1

    # Apply clamping and bounds logic if requested
    if clamp and dim_len > 0:
        if start is not None:
            if start < 0:
                start = max(0, dim_len + start)
        else:
            start = 0

        if stop is not None:
            if stop < 0:
                stop = max(0, dim_len + stop)
            stop = min(stop, dim_len)
        else:
            stop = dim_len

        # Ensure valid range (stop > start) unless empty is intended
        # Standard Python slicing allows start > stop (empty).
        # But if rounding caused accidental empty or overlap issues:
        # e.g. start=10.9 (floor->10), stop=10.1 (ceil->11). Range [10, 11). Correct.
        # e.g. start=10.1 (floor->10), stop=10.9 (ceil->11). Range [10, 11). Correct.
        if start > stop:
            stop = start

    return slice(start, stop, step)


def _coerce_selection_recursive(sel, mode="expand"):
    """Recursively coerce selection objects to integers."""
    if isinstance(sel, slice):
        return _coerce_slice(sel, clamp=False, mode=mode)
    elif isinstance(sel, (tuple, list)):
        return tuple(_coerce_selection_recursive(s, mode=mode) for s in sel)
    elif np is not None and isinstance(sel, (np.ndarray, list)):
        try:
            if hasattr(sel, "astype"):
                return sel.astype(int)
            return np.array(sel, dtype=int)
        except Exception:
            try:
                if isinstance(sel, list):
                    return tuple(int(x) for x in sel)
                return sel
            except Exception:
                return sel
    else:
        try:
            if isinstance(sel, numbers.Number) and not isinstance(sel, int):
                return int(float(sel))
            if (
                np is not None
                and isinstance(sel, np.generic)
                and not isinstance(sel, int)
            ):
                return int(float(sel))
        except Exception:
            pass
        return sel


def apply_zarr_patches():
    """Apply defensive monkeypatches to zarr in the current process.

    Returns
    -------
    bool
        True if at least one patch was applied successfully.
    """
    applied_any = False

    # 1. Patch zarr.open (positional mode argument)
    if zarr:
        try:
            _orig_open = zarr.open

            def _compat_open(*args, **kwargs):
                # If arguments are passed positionally (path, mode),
                # move the second argument to kwargs['mode'].
                if len(args) > 1:
                    if "mode" not in kwargs:
                        kwargs["mode"] = args[1]
                    # Retain only the first argument (path) as positional
                    args = (args[0],) + args[2:]
                return _orig_open(*args, **kwargs)

            zarr.open = _compat_open
            # print("apply_zarr_patches: patched zarr.open")
            # sys.stdout.flush()
            applied_any = True
        except Exception as e:
            print(f"apply_zarr_patches: failed to patch zarr.open: {e}")

    # 2. Patch Array.__getitem__ (coerce floats)
    if zarr and zarr.core:
        try:
            _orig_get = zarr.core.Array.__getitem__

            def _getitem_compat(self, selection):
                new_sel = _coerce_selection_recursive(selection, mode="nearest")
                # Optional: debug logging could be added here if needed
                return _orig_get(self, new_sel)

            zarr.core.Array.__getitem__ = _getitem_compat
            # print("apply_zarr_patches: patched Array.__getitem__")
            # sys.stdout.flush()
            applied_any = True
        except Exception as e:
            print(f"apply_zarr_patches: failed to patch Array.__getitem__: {e}")

        # Patch Array.__setitem__ (coerce floats strict for writing)
        try:
            _orig_set = zarr.core.Array.__setitem__

            def _setitem_compat(self, selection, value):
                # CRITICAL FIX for blocks issue:
                # If we have a shape mismatch between the computed block (value)
                # and the target slice derived from float coordinates, Zarr crashes.
                # To prevent this (Empty Blocks), we force the slice length to match
                # the ACTUAL data length we are trying to write.
                try:
                    if isinstance(selection, tuple) and hasattr(value, "shape"):
                        # Count slices to see if they align with value dimensions
                        slices_count = sum(1 for s in selection if isinstance(s, slice))
                        if slices_count == len(value.shape):
                            new_sel_list = []
                            v_idx = 0
                            for s in selection:
                                if isinstance(s, slice):
                                    length = value.shape[v_idx]
                                    # Round start to nearest grid integer
                                    if s.start is None:
                                        start = 0
                                    else:
                                        try:
                                            # Use floor for start so we don't accidentally
                                            # round up and create gaps when coercing
                                            # float slice indices to integers.
                                            start = int(math.floor(float(s.start)))
                                        except Exception:
                                            start = 0

                                    # Force stop to be exactly start + data_length
                                    stop = start + length

                                    # Preserve step if present
                                    step = s.step
                                    if step is not None:
                                        try:
                                            step = int(float(step))
                                        except:
                                            pass

                                    new_sel_list.append(slice(start, stop, step))
                                    v_idx += 1
                                else:
                                    # Non-slice selector (int, array, etc)
                                    new_sel_list.append(
                                        _coerce_selection_recursive(s, mode="nearest")
                                    )

                            new_sel = tuple(new_sel_list)
                            return _orig_set(self, new_sel, value)
                except Exception:
                    # If any logic fails, fall back to robust standard coercion
                    pass

                new_sel = _coerce_selection_recursive(selection, mode="nearest")
                return _orig_set(self, new_sel, value)

            zarr.core.Array.__setitem__ = _setitem_compat
            # print("apply_zarr_patches: patched Array.__setitem__")
            applied_any = True
        except Exception as e:
            print(f"apply_zarr_patches: failed to patch Array.__setitem__: {e}")

    # 3. Patch SliceDimIndexer.__init__ (clamp and coerce)
    if _z_idx:
        try:
            _orig_slice_init = getattr(_z_idx.SliceDimIndexer, "__init__", None)

            if _orig_slice_init:

                def _slice_init_compat(self, dim_sel, dim_len, dim_chunk_len):
                    if isinstance(dim_sel, slice):
                        coerced = _coerce_slice(
                            dim_sel, dim_len=dim_len, clamp=True, mode="nearest"
                        )
                    else:
                        coerced = _coerce_selection_recursive(dim_sel, mode="nearest")
                    return _orig_slice_init(self, coerced, dim_len, dim_chunk_len)

                _z_idx.SliceDimIndexer.__init__ = _slice_init_compat
                # print("apply_zarr_patches: patched SliceDimIndexer.__init__")
                # sys.stdout.flush()
                applied_any = True
        except Exception as e:
            print(f"apply_zarr_patches: failed to patch SliceDimIndexer: {e}")

    # 4. Patch mkldnn.to_mkldnn (noop)
    if mkldnn and not hasattr(mkldnn.to_mkldnn, "_is_patched"):
        try:
            _orig_to_mkldnn = mkldnn.to_mkldnn

            def _to_mkldnn_noop(module, dtype=None):
                return module

            _to_mkldnn_noop._original = _orig_to_mkldnn
            _to_mkldnn_noop._is_patched = True
            mkldnn.to_mkldnn = _to_mkldnn_noop
            # print("apply_zarr_patches: patched mkldnn.to_mkldnn")
            # sys.stdout.flush()
            applied_any = True
        except Exception:
            pass

    return applied_any


def setup_worker(nthreads: int, min_intensity: Optional[float] = None) -> Any:
    """Return a worker setup callable that enforces thread limits and
    optionally skips network evaluation for empty blocks.

    Parameters
    ----------
    nthreads : int
        Number of threads to enforce in environment variables.
    min_intensity : Optional[float], default None
        If provided, intensity threshold for skipping evaluation.

    Returns
    -------
    callable
        Function to be run on dask worker initialization.
    """

    def dask_setup(worker):
        import logging
        import os
        import sys

        import numpy as np

        # Enforce thread limits
        try:
            os.environ["OMP_NUM_THREADS"] = str(nthreads)
            os.environ["MKL_NUM_THREADS"] = str(nthreads)
            os.environ["OPENBLAS_NUM_THREADS"] = str(nthreads)
            os.environ["NUMEXPR_NUM_THREADS"] = str(nthreads)
            # Reduce fragmentation and improve stability for small GPUs
            os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"
        except Exception:
            pass

        # Disable MKLDNN which can cause instability or MemoryError in some 3D scenarios
        try:
            import torch.utils.mkldnn as mkldnn

            if mkldnn is not None and hasattr(mkldnn, "set_enabled"):
                mkldnn.set_enabled(False)
        except Exception:
            pass

        # Suppress cellpose's internal logger setup in workers to avoid
        # PermissionError when multiple workers try to open the same log file.
        # This is especially important on Windows.
        try:
            import cellpose.io

            def _noop_logger_setup(*args, **kwargs):
                pass

            cellpose.io.logger_setup = _noop_logger_setup
        except Exception:
            pass

        # Apply general zarr patches
        try:
            apply_zarr_patches()
        except Exception as e:
            print(f"setup_worker: apply_zarr_patches failed: {e}")

        # OPTIMIZATION: Early-exit Network skip for empty blocks
        # Patch CellposeModel.eval to skip the network if mean intensity is too low
        try:
            from cellpose.models import CellposeModel

            if not hasattr(CellposeModel.eval, "_patched_for_skip"):
                _orig_eval = CellposeModel.eval

                def _eval_with_skip(self, x, *args, **kwargs):
                    # Check if the input block should be skipped based on intensity
                    # We use max intensity as a safe 'empty' indicator
                    try:
                        # Extract max intensity. x can be multichannel (C, Z, Y, X) or (Z, Y, X)
                        curr_max = np.max(x)
                        thresh = min_intensity if min_intensity is not None else 0.0

                        if curr_max <= thresh:
                            logger = logging.getLogger("cellpose")
                            logger.info(
                                f"Skipping network run for block (max={curr_max:.2f} <= threshold={thresh:.2f})"
                            )

                            # We need to return (masks, flows, styles)
                            # Shape of masks matches spatial resolution of x
                            x_shape = x.shape
                            # If multichannel (C, ...), spatial shape is x_shape[1:]
                            # Wait: cellpose's eval x input for 2D is (Y, X) or (C, Y, X)
                            # For 3D it is (Z, Y, X) or (C, Z, Y, X)
                            # We assume the last 2 or 3 are spatial.
                            # Usually ds.distributed_eval gives (Z, Y, X) or (C, Z, Y, X)
                            spatial_shape = x_shape
                            if len(x_shape) >= 4:
                                spatial_shape = x_shape[
                                    1:
                                ]  # Strip C if present

                            masks = np.zeros(spatial_shape, dtype=np.uint16)
                            # Flows is usually [flows_rgb, prob_map, ...]
                            # or just a tuple of arrays.
                            # For 3D it's typically [flows_rgb, prob_map, cellprob_z, flows_x, flows_y]
                            # but ds.distributed_eval expects the standard output.
                            # We use zeros as flows.
                            flows = [np.zeros_like(masks).astype(np.float32)] * 4
                            styles = np.zeros(64, dtype=np.float32)

                            return masks, flows, styles
                    except Exception as e_skip:
                        print(f"Warning: early intensity check failed ({e_skip})")

                    return _orig_eval(self, x, *args, **kwargs)

                # Tag and replace
                _eval_with_skip._patched_for_skip = True
                CellposeModel.eval = _eval_with_skip
                # print("setup_worker: patched CellposeModel.eval for intensity-based skip")
        except Exception as e_patch:
            print(f"setup_worker: Failed to patch CellposeModel.eval: {e_patch}")

    return dask_setup
