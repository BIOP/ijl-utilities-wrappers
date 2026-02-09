#!/usr/bin/env python3
"""Distributed Cellpose CLI helper.

This module provides a command-line interface for running Cellpose segmentation
distributed across Dask workers, with support for Zarr arrays.

Works on
--------
2D, 3D
"""

import argparse
import getpass
import importlib
import inspect as _inspect
import json
import logging
import math
import multiprocessing
import numbers
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import webbrowser
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

# Optional dependencies handled with try-except to allow partial environments
try:
    import zarr
    import zarr.core.array as _zca
    import zarr.core.indexing as _z_idx
    from zarr.core import array as _zarr_array
except ImportError:
    zarr = None
    _zarr_array = None
    _z_idx = None
    _zca = None

try:
    import torch
    import torch.utils.mkldnn as mkldnn
except (ImportError, AttributeError):
    torch = None
    mkldnn = None

try:
    import psutil
except ImportError:
    psutil = None

try:
    import tifffile
except ImportError:
    tifffile = None

try:
    import dask
    import dask.array as da
    from dask.distributed import Client, LocalCluster, default_client
    from distributed import WorkerPlugin as _WP
except ImportError:
    dask = None
    da = None
    Client = None
    LocalCluster = None
    default_client = None
    _WP = None

# Try to import central worker patches helper; not fatal if missing.
try:
    import worker_patches  # type: ignore
except ImportError:
    worker_patches = None


class Tee:
    """Redirects stdout/stderr to a file and the original stream.

    Parameters
    ----------
    stream : Any
        The original stream (e.g., sys.stdout).
    file_handle : TextIO
        Open file handle for logging.
    lock : threading.Lock, optional
        A shared lock for thread-safe writing.
    """

    def __init__(self, stream, file_handle, lock=None):
        self.stream = stream
        self.file_handle = file_handle
        self.lock = lock or threading.Lock()

    def write(self, message):
        with self.lock:
            if self.stream:
                self.stream.write(message)
                # Flush stream so external wrappers (like Java/Fiji) see output immediately
                try:
                    self.stream.flush()
                except Exception:
                    pass
            if self.file_handle:
                self.file_handle.write(message)
                # Flush to ensure log updates, but avoid expensive fsync
                self.file_handle.flush()

    def flush(self):
        with self.lock:
            if self.stream and hasattr(self.stream, "flush"):
                self.stream.flush()
            if self.file_handle and hasattr(self.file_handle, "flush"):
                self.file_handle.flush()

    def close(self):
        # We don't close the file handle here as it might be shared
        pass


def _set_worker_logging(log_file):
    """Callback for dask workers to redirect their output to a common file."""
    if log_file:
        try:
            # Note: Multiple workers writing to the same file on Windows can be
            # problematic. We use a single handle and avoid multiple opens if possible.
            # However, dask workers are separate processes.
            f = open(log_file, "a", encoding="utf-8")
            sys.stdout = Tee(sys.stdout, f)
            sys.stderr = Tee(sys.stderr, f)
            print(f"Worker {os.getpid()} logging redirected to {log_file}")
        except Exception as e:
            print(f"Worker {os.getpid()} could not redirect logging: {e}")


def heartbeat(stop_event: threading.Event, log_file: Optional[str]) -> None:
    """Thread function to print periodic status and keep the process alive.

    Parameters
    ----------
    stop_event : threading.Event
        Event to signal when the heartbeat should stop.
    log_file : str, optional
        Path to the log file being managed.
    """
    start_time = time.time()
    while not stop_event.is_set():
        elapsed = time.time() - start_time
        msg = (
            f"--- HEARTBEAT: Process {os.getpid()} alive, elapsed: {elapsed:.1f}s ---\n"
        )
        sys.stdout.write(msg)
        sys.stdout.flush()
        time.sleep(30)


# Configure module logging; Fiji/launcher can redirect or capture stdout/stderr.
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
)
logger = logging.getLogger("distributed_cellpose_cli")

# On Windows, ensure we use spawn method for multiprocessing (required for Dask)
if sys.platform == "win32":
    try:
        # Only set if not already set (avoid errors in subprocesses)
        if multiprocessing.get_start_method(allow_none=True) is None:
            multiprocessing.set_start_method("spawn", force=False)
    except RuntimeError:
        # Already set, which is fine
        pass


def get_optimal_n_workers(
    use_gpu: bool,
    requested_n_workers: Optional[int],
    blocksize: Tuple[int, ...],
    model_type: str = "cyto3",
    diameter: float = 30.0,
) -> Tuple[int, int, int]:
    """Calculate optimal number of workers based on hardware availability.

    Returns
    -------
    Tuple[int, int, int]
        `(n_workers, dask_threads, internal_threads)`
        - n_workers: Number of processes in the cluster.
        - dask_threads: Threads per worker for Dask task scheduling (blocks).
        - internal_threads: Threads for internal libraries (MKL/OMP/Torch).
    """
    if blocksize is None:
        blocksize = (32, 224, 224)

    # Calculate scaling factor
    model_diam = 17.0 if "nuclei" in str(model_type).lower() else 30.0
    eff_diameter = float(diameter) if float(diameter) > 0 else 30.0
    scaling_factor = model_diam / eff_diameter

    z, y, x = blocksize
    is_3d_mode = z > 1

    if is_3d_mode:
        rescaled_voxels = (z * scaling_factor) * (y * scaling_factor) * (x * scaling_factor)
    else:
        rescaled_voxels = z * (y * scaling_factor) * (x * scaling_factor)

    print(
        f"Memory estimation: model_diam={model_diam}, diameter={eff_diameter}, scaling_factor={scaling_factor:.2f}, 3D={is_3d_mode}"
    )

    total_cpus = os.cpu_count() or 1
    eff_requested_workers = requested_n_workers if requested_n_workers > 0 else 9999

    # RAM Estimation
    max_workers_ram = eff_requested_workers
    if psutil is not None:
        try:
            available_ram = psutil.virtual_memory().available
            estimated_ram_per_worker = rescaled_voxels * 4 * 20
            usable_ram = available_ram * 0.8
            max_workers_ram = max(1, int(usable_ram // estimated_ram_per_worker))
            print(f"RAM-limited to {max_workers_ram} workers")
        except Exception:
            pass

    if not use_gpu:
        # CPU mode: maximize process parallelism for GIL avoidance
        n_workers = min(eff_requested_workers, total_cpus, max_workers_ram)
        # Use 1 Dask thread per worker, 1 OMP thread per worker
        return n_workers, 1, 1

    # GPU mode
    try:
        if not torch.cuda.is_available():
            return min(eff_requested_workers, total_cpus, max_workers_ram), 1, 1

        num_gpus = torch.cuda.device_count()
        gpu_props = torch.cuda.get_device_properties(0)
        total_memory_single_gpu = gpu_props.total_memory
        
        torch.cuda.empty_cache()
        free_memory = total_memory_single_gpu - torch.cuda.memory_allocated(0)
        usable_memory_per_gpu = min(total_memory_single_gpu * 0.8, free_memory * 0.95)

        estimated_vram_per_worker = rescaled_voxels * 4 * 10
        workers_per_gpu = int(usable_memory_per_gpu // estimated_vram_per_worker)
        
        # PERFORMANCE TUNE: 1 worker per GPU is the official recommendation for stability/speed.
        # This avoids multi-process contention on the same CUDA context.
        optimal_workers_per_gpu = 1 if workers_per_gpu >= 1 else 0
        
        # Determine number of GPU workers
        n_workers_gpu = max(1, num_gpus * optimal_workers_per_gpu)
        n_workers = min(eff_requested_workers, n_workers_gpu, max_workers_ram)
        
        # Internal threads: each worker can use multiple cores for pre/post-processing
        # but we set Dask threads to 1 to ensure blocks are processed sequentially per GPU.
        internal_threads = min(16, max(1, total_cpus // n_workers))

        print(
            f"GPU mode: n_workers={n_workers}, dask_threads=1, internal_threads={internal_threads} "
            f"(GPU-limited: {n_workers_gpu}, RAM-limited: {max_workers_ram})"
        )

        return n_workers, 1, internal_threads

    except Exception as e:
        print(f"Error in GPU worker calculation: {e}. Falling back to 1 worker.")
        return 1, 1, 1
    modules and that `cellpose.contrib.distributed_segmentation`
    provides the expected helpers. Exits the program with an
    informative message when a requirement is missing.

    Returns
    -------
    Tuple[Any, str]
        A tuple `(ds_mod, cellpose_version)` where `ds_mod` is the imported
        module and `cellpose_version` is the version string of cellpose.
    """
    missing = []
    info = []

    modules_to_check = ["cellpose", "dask", "distributed", "zarr", "tifffile"]
    for mod_name in modules_to_check:
        try:
            print(f"Checking module: {mod_name}...")
            sys.stdout.flush()
            mod = importlib.import_module(mod_name)
            ver = getattr(mod, "__version__", None)
            info.append((mod_name, ver))
        except Exception as e:
            print(f"Warning: Could not import {mod_name}: {e}")
            missing.append(mod_name)

    ds_mod = None
    if "cellpose" not in missing:
        try:
            print("Checking distributed cellpose module...")
            sys.stdout.flush()
            ds_mod = importlib.import_module(
                "cellpose.contrib.distributed_segmentation"
            )
        except Exception as e:
            print(f"Warning: Could not import distributed cellpose components: {e}")
            missing.append("cellpose.contrib.distributed_segmentation")

    if missing:
        print("Missing required Python packages or modules:")
        for m in missing:
            print(" -", m)
        print("Please install required packages into the conda env, for example:")
        print(
            "  conda activate <env> && pip install cellpose dask zarr tifffile imagecodecs"
        )
        print(
            "Or use the project provided YAMLs / instructions to build a working environment."
        )
        sys.exit(1)

    # sanity checks on the distributed module
    if not hasattr(ds_mod, "distributed_eval"):
        print(
            "cellpose.contrib.distributed_segmentation does not expose 'distributed_eval' - your cellpose version may be incompatible."
        )
        sys.exit(1)
    if not hasattr(ds_mod, "wrap_folder_of_tiffs"):
        print(
            "cellpose.contrib.distributed_segmentation does not expose 'wrap_folder_of_tiffs' - your cellpose version may be incompatible."
        )
        sys.exit(1)

    print("Found required Python packages and distributed cellpose helpers:")
    for m, v in info:
        print(f" - {m} {v}")

    # Get cellpose version for compatibility checks
    cellpose_version = None
    for m, v in info:
        if m == "cellpose":
            cellpose_version = v
            break

    return ds_mod, cellpose_version


def parse_blocksize(s: str) -> Tuple[int, ...]:
    """Parse a comma-separated blocksize string into a tuple of ints.

    Also handles concatenated format like `128256256` -> (128, 256, 256).

    Parameters
    ----------
    s : str
        Comma-separated integers, e.g. `"128,256,256"`, or concatenated
        integers like `128256256` (will be split into equal thirds).

    Returns
    -------
    Tuple[int, ...]
        The parsed blocksize tuple, e.g. `(128, 256, 256)`.
    """
    s = s.strip()
    if "," in s:
        # Normal comma-separated format
        parts = [int(x) for x in s.split(",") if x.strip()]
    elif s.isdigit() and len(s) == 1:
        # Single digit: repeat it to match typical spatial rank (heuristic)
        val = int(s)
        parts = [val, val, val]
    elif s.isdigit() and len(s) >= 6:
        # Concatenated format: split into equal thirds
        length = len(s)
        third = length // 3
        parts = [int(s[0:third]), int(s[third : 2 * third]), int(s[2 * third :])]
    else:
        # Fallback: try to parse as single integer (will likely fail downstream)
        parts = [int(s)]
    return tuple(parts)


def _apply_zarr_open_compat(
    z_module: Any, ds_module: Any = None, reload_ds: bool = True
) -> None:
    """Patch `zarr.open` to accept a positional `mode` argument.

    Some zarr versions make the `mode` argument keyword-only; older
    callers may call `zarr.open(path, 'w', ...)`. This function wraps
    `zarr.open` to accept a positional mode argument and optionally
    reloads the distributed segmentation module so it picks up the
    patched function.

    Parameters
    ----------
    z_module : Any
        The imported `zarr` module to patch.
    ds_module : Any, optional
        The `cellpose.contrib.distributed_segmentation` module; if
        provided, its reference to `zarr.open` will also be patched.
    reload_ds : bool, optional
        If True, attempt to reload `ds_module` after patching.
    """

    try:
        _orig_zarr_open = z_module.open

        def _zarr_open_compat(*args, **kwargs):
            if len(args) >= 2 and isinstance(args[1], str):
                return _orig_zarr_open(store=args[0], mode=args[1], *args[2:], **kwargs)
            return _orig_zarr_open(*args, **kwargs)

        z_module.open = _zarr_open_compat

        # patch the reference inside the distributed_segmentation module as well
        if ds_module is not None:
            try:
                if hasattr(ds_module, "zarr") and hasattr(ds_module.zarr, "open"):
                    ds_module.zarr.open = z_module.open
            except Exception:
                pass

        # optionally reload the ds module to pick up any in-module references
        if reload_ds and ds_module is not None:
            try:
                importlib.reload(ds_module)
                print("Reloaded distributed_segmentation after zarr.open patch.")
                sys.stdout.flush()
            except Exception as e:
                print("Could not reload distributed_segmentation:", e)
                sys.stdout.flush()

        print(
            "Patched zarr.open to be compatible with older callers (accepts positional mode)."
        )
        sys.stdout.flush()
        return True
    except Exception as e:
        print("Could not apply zarr.open compatibility patch:", e)
        sys.stdout.flush()
        return False


def _patch_worker_all() -> None:
    """Apply worker-side compatibility patches inside each Dask worker.

    Delegates to the central `worker_patches.apply_zarr_patches()` helper
    which performs all needed monkeypatches, including zarr indexing
    and suppressing redundant cellpose logger setup to avoid permission
    errors on Windows.
    """
    try:
        # Suppress cellpose's internal logger setup in workers to avoid
        # PermissionError when multiple workers try to open the same log file.
        try:
            import cellpose.io
            def _noop_logger_setup(*args, **kwargs):
                pass
            cellpose.io.logger_setup = _noop_logger_setup
        except Exception:
            pass

        if worker_patches is not None:
            return worker_patches.apply_zarr_patches()
        else:
            # Inline fallback: monkeypatch zarr.open and Array.__getitem__/__setitem__
            # This is critical for Zarr 3.x compatibility when worker_patches.py is missing.
            applied = False
            try:
                # 1. Patch zarr.open
                _orig_open = zarr.open

                def _o_compat(*a, **kw):
                    if len(a) >= 2 and isinstance(a[1], str):
                        return _orig_open(store=a[0], mode=a[1], *a[2:], **kw)
                    return _orig_open(*a, **kw)

                zarr.open = _o_compat
                applied = True

                # 2. Patch Array __getitem__ and __setitem__
                # Handle both zarr.Array (v3 shorthand) and zarr.core.array.Array
                def _coerce(sel, mode="expand"):
                    if isinstance(sel, slice):
                        if sel.start is None:
                            start = 0
                        else:
                            val = float(sel.start)
                            start = (
                                int(round(val))
                                if mode == "nearest"
                                else int(math.floor(val))
                            )
                        if sel.stop is None:
                            stop = None
                        else:
                            val = float(sel.stop)
                            if mode == "nearest" and sel.start is not None:
                                try:
                                    length = val - float(sel.start)
                                    stop = start + int(round(length))
                                except Exception:
                                    stop = int(round(val))
                            else:
                                stop = (
                                    int(round(val))
                                    if mode == "nearest"
                                    else int(math.ceil(val))
                                )
                        step = sel.step
                        if step is not None:
                            try:
                                step = int(float(step))
                            except Exception:
                                pass
                        return slice(start, stop, step)
                    elif isinstance(sel, (tuple, list)):
                        return tuple(_coerce(s, mode) for s in sel)
                    elif hasattr(sel, "astype"):
                        return sel.astype(int)
                    elif isinstance(
                        sel, (numbers.Number, np.generic)
                    ) and not isinstance(sel, int):
                        return int(float(sel))
                    return sel

                _orig_get = _zca.Array.__getitem__
                _orig_set = _zca.Array.__setitem__

                def _getitem_w(self, selection):
                    return _orig_get(self, _coerce(selection, mode="expand"))

                def _setitem_w(self, selection, value):
                    # Force slice length matching to prevent Zarr 3.x errors
                    try:
                        if isinstance(selection, tuple) and hasattr(value, "shape"):
                            slices_count = sum(
                                1 for s in selection if isinstance(s, slice)
                            )
                            if slices_count == len(value.shape):
                                new_sel_list = []
                                v_idx = 0
                                for s in selection:
                                    if isinstance(s, slice):
                                        length = value.shape[v_idx]
                                        start = (
                                            int(math.floor(float(s.start)))
                                            if s.start is not None
                                            else 0
                                        )
                                        new_sel_list.append(
                                            slice(start, start + length, s.step)
                                        )
                                        v_idx += 1
                                    else:
                                        new_sel_list.append(_coerce(s, mode="nearest"))
                                return _orig_set(self, tuple(new_sel_list), value)
                    except Exception:
                        pass
                    return _orig_set(self, _coerce(selection, mode="nearest"), value)

                _zca.Array.__getitem__ = _getitem_w
                _zca.Array.__setitem__ = _setitem_w
                applied = True
            except Exception:
                pass
            return applied
    except Exception:
        return False
        return True
    except Exception:
        return False


def _run_distributed_in_subprocess(
    input_zarr_path,
    write_zarr,
    blocksize,
    model_kwargs,
    eval_kwargs,
    cluster_kwargs,
    temporary_directory,
    n_workers,
    ncpus,
    debug_save_path=None,
):
    """Run `distributed_eval` inside an isolated Python subprocess.

    Calls the external `distributed_cellpose_subprocess_runner.py` script
    with a JSON config file, avoiding the need to generate script strings.

    Parameters
    ----------
    input_zarr_path : str
        Path to the input Zarr store.
    write_zarr : str
        Path to write the output stitched Zarr.
    blocksize : sequence of int
        Block size used for segmentation.
    model_kwargs : dict
        Keyword arguments passed to the model constructor.
    eval_kwargs : dict
        Keyword arguments for evaluation.
    cluster_kwargs : dict
        Cluster configuration.
    temporary_directory : str
        Directory for temporary files.
    n_workers : int
        Number of workers.
    ncpus : int
        Number of CPUs per worker.
    debug_save_path : str or None
        If provided, params will be saved to this path for debugging.

    Returns
    -------
    None
    """
    # Avoid forwarding an explicit 'ncpus' key into subprocess cluster kwargs
    # because recent dask versions reject it. Instead pass an explicit
    # 'threads_per_worker' value which maps to Dask's API.
    cluster_kwargs_clean = dict(cluster_kwargs) if cluster_kwargs else {}
    cluster_kwargs_clean.pop("ncpus", None)

    # Find the subprocess runner script in the same directory as this script
    runner_script = os.path.join(
        os.path.dirname(__file__), "distributed_cellpose_subprocess_runner.py"
    )

    if not os.path.exists(runner_script):
        raise FileNotFoundError(f"Subprocess runner script not found: {runner_script}")

    # Find worker_patches.py to pass its path to subprocess
    worker_patches_path = os.path.join(os.path.dirname(__file__), "worker_patches.py")
    if not os.path.exists(worker_patches_path):
        worker_patches_path = None

    with tempfile.TemporaryDirectory(prefix="distributed_cellpose_subproc_") as td:
        # Build parameters for subprocess runner
        params = {
            "input_zarr": input_zarr_path,
            "write_zarr": write_zarr,
            "blocksize": list(blocksize),
            "model_kwargs": model_kwargs,
            "eval_kwargs": eval_kwargs,
            "cluster_kwargs": cluster_kwargs_clean,
            "temporary_directory": temporary_directory,
            "n_workers": n_workers,
            "threads_per_worker": ncpus,
            "debug_log": os.path.join(td, "worker_patch_log.txt"),
            "worker_patches_path": worker_patches_path,
        }

        # If debug path requested, save params for inspection with persistent log path
        if debug_save_path:
            parent = os.path.dirname(debug_save_path) or "."
            os.makedirs(parent, exist_ok=True)
            debug_params_file = os.path.join(
                parent,
                os.path.splitext(os.path.basename(debug_save_path))[0] + "_params.json",
            )
            # Use persistent debug log path for debug mode
            debug_params = dict(params)
            debug_params["debug_log"] = os.path.join(parent, "worker_patch_log.txt")
            with open(debug_params_file, "w") as f:
                json.dump(debug_params, f, indent=2)
            print(f"Saved debug params to: {debug_params_file}")
            print(
                f"To run manually: {sys.executable} {runner_script} {debug_params_file}"
            )

        # Write parameters to JSON file in temp directory for actual run
        params_file = os.path.join(td, "params.json")
        with open(params_file, "w") as f:
            json.dump(params, f, indent=2)

        # Run the subprocess runner script
        cmd = [sys.executable, runner_script, params_file]
        print("Launching subprocess to run distributed_eval:", cmd)
        subprocess.check_call(cmd)

        # Copy debug log to persistent location if it exists
        try:
            src_log = os.path.join(td, "worker_patch_log.txt")
            if os.path.exists(src_log):
                dest_dir = temporary_directory or os.getcwd()
                os.makedirs(dest_dir, exist_ok=True)
                dest = os.path.join(
                    dest_dir,
                    f"worker_patch_log_{os.getpid()}_{int(time.time())}.txt",
                )
                shutil.copy(src_log, dest)
                print(f"Saved worker patch log to: {dest}")
        except Exception as e:
            print(f"Warning: Could not copy worker patch log: {e}")


def _run_distributed_eval(
    ds: Any,
    input_zarr: Any,
    write_zarr: str,
    blocksize: Tuple[int, ...],
    model_kwargs: Dict[str, Any],
    eval_kwargs: Dict[str, Any],
    cluster_kwargs: Dict[str, Any],
    args: argparse.Namespace,
    channel_zarrs: Optional[List[Any]] = None,
    log_file: Optional[str] = None,
) -> Tuple[Any, List[Any]]:
    """Run `ds.distributed_eval`, attempting a proactively patched cluster.

    Attempts to create a `LocalCluster` and `Client` to patch
    worker environments for compatibility. Falls back to a direct
    call or a subprocess-based fallback when necessary.

    Parameters
    ----------
    ds : module
        The imported `cellpose.contrib.distributed_segmentation` module.
    input_zarr : Any
        The input zarr array or proxy.
    write_zarr : str
        Path where the stitched Zarr will be written.
    blocksize : Tuple[int, ...]
        Block size for segmentation.
    model_kwargs : Dict[str, Any]
        Model construction keyword arguments.
    eval_kwargs : Dict[str, Any]
        Evaluation keyword arguments.
    cluster_kwargs : Dict[str, Any]
        Cluster configuration passed to the LocalCluster constructor.
    args : argparse.Namespace
        Parsed CLI arguments.
    channel_zarrs : List[Any], optional
        List of zarr arrays for each channel.
    log_file : str, optional
        Path to the log file for Tee output.

    Returns
    -------
    Tuple[Any, List[Any]]
        A tuple `(out_zarr, boxes)` as returned by `ds.distributed_eval`.
    """

    # Apply patches globally in the main process first
    print("Applying zarr compatibility patches in main process...")
    _patch_worker_all()

    # Create a temporary module file for worker preloading
    preload_file = None
    try:
        # Get the calculated thread limit from cluster_kwargs (prefer threads_per_worker)
        thread_limit = cluster_kwargs.get(
            "threads_per_worker", cluster_kwargs.get("ncpus", 1)
        )

        # Create a temporary Python file with the patch code by embedding the
        # source from the central helper module `worker_patches.py` (simpler to maintain).
        preload_file = None
        try:
            module_dir = os.path.dirname(__file__)
            wp_path = os.path.join(module_dir, "worker_patches.py")
            with open(wp_path, "r") as _f:
                wp_source = _f.read()

            preload_content = (
                wp_source + "\n\n" + f"dask_setup = setup_worker({thread_limit})\n"
            )
        except Exception:
            # Last-resort fallback inline minimal script
            preload_content = f"""
import os

def dask_setup(worker):
    nthreads = {thread_limit}
    os.environ['OMP_NUM_THREADS'] = str(nthreads)
    os.environ['MKL_NUM_THREADS'] = str(nthreads)
    os.environ['OPENBLAS_NUM_THREADS'] = str(nthreads)
    os.environ['NUMEXPR_NUM_THREADS'] = str(nthreads)
    print(f"Worker {{getattr(worker, 'id', '<unknown>')}}: Thread limit enforced to {{nthreads}} threads")
"""
        # Write to temp file
        fd, preload_file = tempfile.mkstemp(suffix=".py", prefix="zarr_patches_")
        os.write(fd, preload_content.encode("utf-8"))
        os.close(fd)

        # Use dask configuration to set worker preload globally
        # This will affect any LocalCluster created after this point
        if dask is not None:
            dask.config.set({"distributed.worker.preload": [preload_file]})
        print(f"Created worker preload script: {preload_file}")
        print("Configured dask to preload patches in all workers")
        sys.stdout.flush()
    except Exception as e:
        print(f"Warning: Could not create worker preload script: {e}")
        sys.stdout.flush()
        preload_file = None

    try:
        # Create preprocessing_steps if we have multiple channels
        preprocessing_steps = None
        if channel_zarrs and len(channel_zarrs) > 1:
            # Create preprocessing function to stack additional channels
            def stack_channels(image, crop):
                """Stack additional channels onto the base channel.
                Following cellpose documentation pattern for multi-channel segmentation.
                """
                channels_to_stack = [image]  # Start with base channel
                for ch_idx in range(1, len(channel_zarrs)):
                    channels_to_stack.append(channel_zarrs[ch_idx][crop])
                # Determine where to insert the channel axis.
                # If image is 3D (Z, Y, X), we want (Z, C, Y, X) -> axis 1
                # If image is 2D (Y, X), we want (C, Y, X) -> axis 0
                if image.ndim == 3:
                    # In cellpose.contrib.distributed_segmentation, the image passed
                    # to preprocessing_steps matches the input_zarr's rank.
                    # Celpose's model.eval(do_3D=True) expects (Z, C, Y, X)
                    return np.stack(channels_to_stack, axis=1)
                else:
                    # For 2D model, cellpose expects (C, Y, X)
                    return np.stack(channels_to_stack, axis=0)

            preprocessing_steps = [(stack_channels, {})]
            print(f"Created preprocessing_steps to stack {len(channel_zarrs)} channels")
            sys.stdout.flush()

        # Let cellpose create its own cluster with our preload script
        kwargs = dict(
            blocksize=blocksize,
            write_path=write_zarr,
            model_kwargs=model_kwargs,
            eval_kwargs=eval_kwargs,
            cluster_kwargs=cluster_kwargs,
            temporary_directory=args.temporary_directory,
        )

        # Add preprocessing_steps if multi-channel
        if preprocessing_steps:
            kwargs["preprocessing_steps"] = preprocessing_steps

        print(
            "Running distributed_eval (cellpose will create cluster with preload patches)..."
        )
        print(f"Cluster config: {cluster_kwargs}")
        print(f"Model config: {model_kwargs}")
        print(f"Eval config: {eval_kwargs}")
        # Input may be a path-like string or a minimal proxy without a `shape`
        try:
            inp_shape = getattr(input_zarr, "shape", None)
        except Exception:
            inp_shape = None
        print(f"Input shape: {inp_shape}, blocksize: {blocksize}")

        # Calculate expected number of blocks

        try:
            shapes = getattr(input_zarr, "shape", None)
            if shapes is None:
                raise AttributeError("input_zarr has no shape")
            num_blocks = math.prod(
                [math.ceil(shapes[i] / blocksize[i]) for i in range(len(blocksize))]
            )
            print(f"Expected number of blocks to process: {num_blocks}")
        except Exception:
            print(
                "Could not determine expected number of blocks (input_zarr has no shape)"
            )
        sys.stdout.flush()

        # Set environment variables to force Dask scheduler and workers to use
        # the safe temporary directory. The scheduler's WorkSpace is created
        # from the DASK_TEMPORARY_DIRECTORY env var (or falls back to tempfile.gettempdir()).
        # Setting TMP/TEMP/TMPDIR ensures all temp lookups use the safe location.
        if args.temporary_directory:
            os.environ["DASK_TEMPORARY_DIRECTORY"] = args.temporary_directory
            os.environ["TMPDIR"] = args.temporary_directory
            os.environ["TEMP"] = args.temporary_directory
            os.environ["TMP"] = args.temporary_directory
            print(
                f"Set DASK_TEMPORARY_DIRECTORY and temp env vars to: {args.temporary_directory}"
            )
            sys.stdout.flush()

        print("Starting distributed_eval - cluster initialization may take a minute...")
        sys.stdout.flush()

        # Prefer creating a LocalCluster and Client here so we can explicitly
        # apply worker patches via `client.run()` and pass `client`/`cluster`
        # into `ds.distributed_eval` when supported. Fall back to letting
        # cellpose create its own cluster if cluster construction fails.
        client = None
        created = None
        try:
            cluster_kwargs_local = dict(cluster_kwargs) if cluster_kwargs else {}
            # Ensure local_directory is honored
            if (
                args.temporary_directory
                and "local_directory" not in cluster_kwargs_local
            ):
                cluster_kwargs_local["local_directory"] = args.temporary_directory

            try:
                created = LocalCluster(**cluster_kwargs_local)
            except TypeError:
                # Retry common fallbacks (some dask versions reject 'ncpus' or 'preload')
                try:
                    cluster_kwargs_local.pop("ncpus", None)
                    created = LocalCluster(**cluster_kwargs_local)
                except Exception:
                    try:
                        cluster_kwargs_local.pop("preload", None)
                        created = LocalCluster(**cluster_kwargs_local)
                    except Exception:
                        created = None

            if created is not None:
                client = Client(created)
                # Print Dask dashboard address for visibility (helps when launched from Fiji)
                try:
                    dashboard = getattr(created, "dashboard_link", None) or getattr(
                        client, "dashboard_link", None
                    )
                    if not dashboard:
                        try:
                            services = created.scheduler_info().get("services", {})
                            if "dashboard" in services:
                                port = services["dashboard"]
                                addr = getattr(
                                    created, "scheduler_address", None
                                ) or getattr(created, "address", None)
                                host = None
                                if addr:
                                    try:
                                        host = addr.split("//", 1)[-1].split(":")[0]
                                    except Exception:
                                        host = None
                                if host:
                                    dashboard = f"http://{host}:{port}"
                        except Exception:
                            pass
                    if dashboard:
                        print(f"Dask dashboard: {dashboard}")
                        try:
                            if webbrowser is not None and getattr(
                                args, "open_dashboard", False
                            ):
                                webbrowser.open(dashboard)
                        except Exception:
                            pass
                except Exception:
                    pass

                # Register a simple worker plugin that applies zarr/mkldnn
                # patches on worker startup.
                try:

                    class _ZarrPatchPlugin:
                        def setup(self, worker):
                            _patch_worker_all()

                    try:
                        client.register_plugin(_ZarrPatchPlugin(), name="zarr_patches")
                        print("Registered zarr patch worker plugin")
                    except Exception:
                        pass
                except Exception:
                    pass

                try:
                    res = client.run(_patch_worker_all)
                    print("Applied worker patches via client.run(): %r" % (res,))

                    # Also redirect worker logs to the same file if possible
                    if log_file:
                        try:
                            client.run(_set_worker_logging, log_file)
                            print(f"Workers now redirecting output to: {log_file}")
                        except Exception as e:
                            print(f"Warning: Could not redirect worker logging: {e}")
                except Exception as e:
                    print("Warning: client.run(_patch_worker_all) failed:", e)

                # Determine whether to pass client/cluster to distributed_eval
                sig = _inspect.signature(ds.distributed_eval)
                if "client" in sig.parameters:
                    kwargs["client"] = client
                elif "cluster" in sig.parameters:
                    # Some APIs expect a cluster-like with .client attr
                    cluster_like = (
                        created
                        if hasattr(created, "client")
                        else type("ClusterLike", (), {})()
                    )
                    if not hasattr(cluster_like, "client"):
                        cluster_like.client = client
                    kwargs["cluster"] = cluster_like

                # Safety check: ensure write_path does not collide with input and remove stale outputs
                try:
                    write_path = kwargs.get("write_path")
                    input_store_path = None
                    try:
                        store = getattr(input_zarr, "store", None)
                        if isinstance(store, str):
                            input_store_path = store
                        elif hasattr(store, "path"):
                            input_store_path = store.path
                    except Exception:
                        input_store_path = None

                    if write_path and input_store_path:
                        try:
                            abs_in = os.path.abspath(input_store_path)
                            abs_out = os.path.abspath(write_path)
                        except Exception:
                            abs_in = input_store_path
                            abs_out = write_path
                        if abs_in == abs_out or (
                            isinstance(abs_out, str)
                            and abs_out.startswith(abs_in + os.sep)
                        ):
                            base = os.path.splitext(write_path)[0]
                            new_write = base + "_labels.zarr"
                            kwargs["write_path"] = new_write
                            print(
                                f"Adjusted write_path to avoid overwriting input: {new_write}"
                            )
                            write_path = new_write
                    if write_path and os.path.exists(write_path):
                        try:
                            if os.path.isdir(write_path):
                                shutil.rmtree(write_path, ignore_errors=True)
                            else:
                                os.remove(write_path)
                            print(
                                f"Removed existing write_path before running: {write_path}"
                            )
                        except Exception as _e:
                            print(
                                f"Warning: could not remove existing write_path {write_path}: {_e}"
                            )
                except Exception:
                    pass

                # Call distributed_eval with our created cluster/client
                result = ds.distributed_eval(input_zarr, **kwargs)

                # Clean up client/cluster after run
                try:
                    client.close()
                except Exception:
                    pass
                try:
                    created.close()
                except Exception:
                    pass

                # Clean up preload file
                if preload_file and os.path.exists(preload_file):
                    try:
                        os.unlink(preload_file)
                    except Exception:
                        pass

                return result

        except Exception:
            # Couldn't create a LocalCluster or Client; fall back to letting
            # cellpose create its own cluster using our configured preload.
            client = None
            created = None

        # Final fallback: let cellpose create its own cluster
        try:
            result = ds.distributed_eval(input_zarr, **kwargs)
        finally:
            if preload_file and os.path.exists(preload_file):
                try:
                    os.unlink(preload_file)
                except Exception:
                    pass

        return result

    except TypeError as e:
        # Clean up preload file on error
        if preload_file and os.path.exists(preload_file):
            try:
                os.unlink(preload_file)
            except Exception:
                pass

        msg = str(e)
        # If TypeError indicates zarr slicing/indexing issues, attempt a quick
        # defensive retry by coercing diameter to an integer (so overlap/block
        # arithmetic yields integer slice bounds) before falling back to the
        # heavier subprocess fallback. This mirrors upstream intent and avoids
        # unnecessary subprocess work when a simple numeric coercion fixes it.
        if (
            "open() takes" in msg
            or "positional" in msg
            or "slice indices must be integers" in msg
        ):
            print(
                f"Detected TypeError ({msg[:100]}...). "
                "Attempting quick coercion retry before subprocess fallback."
            )

            try:
                # Only try if eval_kwargs has a diameter-like parameter
                diam = (
                    eval_kwargs.get("diameter")
                    if isinstance(eval_kwargs, dict)
                    else None
                )
                if diam is not None and not isinstance(diam, int):
                    coerced = dict(eval_kwargs)
                    coerced["diameter"] = int(math.ceil(float(diam)))
                    print(
                        f"Retrying distributed_eval with coerced diameter={coerced['diameter']}"
                    )
                    sys.stdout.flush()
                    new_kwargs = dict(kwargs)
                    new_kwargs["eval_kwargs"] = coerced
                    try:
                        result = ds.distributed_eval(input_zarr, **new_kwargs)
                        print("Retry with coerced diameter succeeded")
                        return result
                    except TypeError:
                        print(
                            "Retry with coerced diameter still failed; falling back to subprocess"
                        )
                        sys.stdout.flush()
            except Exception:
                # be defensive: ignore issues here and fall back to subprocess
                pass

            print(
                f"Detected TypeError ({msg[:100]}...). "
                "Falling back to subprocess with patched workers."
            )
            # Fall through to subprocess fallback below
            # Determine a path-like input zarr
            input_path = None
            try:
                if hasattr(input_zarr, "store"):
                    store = input_zarr.store
                    if hasattr(store, "path"):
                        input_path = store.path
                    elif isinstance(store, str):
                        input_path = store
            except Exception:
                input_path = None

            created_input_copy = False
            created_input_path = None
            # If input is temporary, we need to copy it to disk for subprocess
            if input_path is None:
                print(
                    "Input zarr is temporary; creating on-disk copy for subprocess fallback..."
                )
                temp_dir = (
                    args.temporary_directory
                    if args.temporary_directory
                    else tempfile.gettempdir()
                )
                input_path = os.path.join(
                    temp_dir, f"distributed_cellpose_input_{os.getpid()}.zarr"
                )
                # Copy the temporary zarr to disk
                dest_zarr = zarr.open(
                    input_path,
                    mode="w",
                    shape=input_zarr.shape,
                    chunks=input_zarr.chunks,
                    dtype=input_zarr.dtype,
                )
                dest_zarr[:] = input_zarr[:]
                print(f"Created on-disk copy at {input_path}")
                created_input_copy = True
                created_input_path = input_path

            # Call subprocess fallback with positional args only so that
            # test doubles or alternate implementations without debug
            # keyword parameters remain compatible.
            _run_distributed_in_subprocess(
                input_path,
                write_zarr,
                blocksize,
                model_kwargs,
                eval_kwargs,
                cluster_kwargs,
                args.temporary_directory,
                args.n_workers,
                cluster_kwargs.get("threads_per_worker", args.ncpus),
            )

            # Clean up copied input Zarr if we created one for the subprocess
            if created_input_copy and created_input_path:
                try:
                    shutil.rmtree(created_input_path, ignore_errors=True)
                    print(f"Removed temporary input zarr copy: {created_input_path}")
                except Exception:
                    pass

            # After subprocess completed, open the output and return reference
            out = zarr.open(write_zarr)
            return out, []
        raise
    finally:
        # Clean up preload file if it exists
        if "preload_file" in locals() and preload_file and os.path.exists(preload_file):
            try:
                os.unlink(preload_file)
            except Exception:
                pass


def main():
    """Command-line entry point for the distributed Cellpose helper.

    Parses CLI arguments, prepares an input Zarr (from a TIFF or a
    directory of TIFFs), applies compatibility patches (`zarr.open`
    and `Array.__getitem__`), and runs distributed evaluation to
    produce a stitched labeled Zarr and optionally a TIFF output.

    Returns
    -------
    None
    """
    parser = argparse.ArgumentParser(description="Distributed Cellpose CLI helper")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--input_file", help="Single TIFF input file")
    group.add_argument(
        "--input_dir", help="Folder of tiff tiles (optional pattern support)"
    )
    parser.add_argument(
        "--output_tif",
        required=False,
        help="Path to write labeled TIFF result (optional, if omitted use --output_dir)",
    )
    parser.add_argument(
        "--output_dir",
        required=False,
        help="Directory where output TIFF (and default Zarr) will be written",
    )
    parser.add_argument(
        "--blocksize", default="32,64,64", help="Block size as comma separated ints"
    )
    parser.add_argument(
        "--model", default="cyto", help="Cellpose pretrained model name or path"
    )
    parser.add_argument(
        "--diameter", default=30, type=float, help="Diameter for evaluation"
    )
    parser.add_argument(
        "--chan", default=0, type=int, help="Channel index to use (0-based)"
    )
    parser.add_argument(
        "--chan2", default=-1, type=int, help="Second channel index or -1"
    )
    parser.add_argument(
        "--flow_threshold",
        default=0.4,
        type=float,
        help="Flow threshold for mask reconstruction",
    )
    parser.add_argument(
        "--cellprob_threshold",
        default=0.0,
        type=float,
        help="Cell probability threshold",
    )
    parser.add_argument(
        "--stitch_threshold",
        default=0.0,
        type=float,
        help="Stitch threshold for 3D volumes",
    )
    parser.add_argument(
        "--min_size",
        default=15,
        type=int,
        help="Minimum size of detected objects in pixels",
    )
    parser.add_argument(
        "--anisotropy",
        default=1.0,
        type=float,
        help="Anisotropy factor (Z pixel size / XY pixel size)",
    )
    parser.add_argument(
        "--n_workers",
        default=None,
        type=int,
        help="Number of workers for the local cluster (auto-detected if not specified)",
    )
    parser.add_argument(
        "--ncpus",
        default=None,
        type=int,
        help="Number of cpus per worker (auto-detected if not specified)",
    )
    parser.add_argument(
        "--use_gpu",
        action="store_true",
        help="Enable GPU acceleration (requires CUDA/PyTorch GPU support)",
    )
    parser.add_argument(
        "--gpu_memory_limit",
        default=None,
        type=float,
        help="Maximum GPU memory to use in GB (auto-detected if not specified)",
    )
    parser.add_argument(
        "--temporary_directory", default=None, help="Temporary directory to use"
    )
    parser.add_argument(
        "--open_dashboard",
        action="store_true",
        default=False,
        help="If set, attempt to open the Dask dashboard in the default web browser",
    )
    parser.add_argument(
        "--debug_save_script",
        default=None,
        help="Path to save the debug params JSON for manual inspection and testing",
    )
    parser.add_argument(
        "--input_zarr",
        default=None,
        help="Optional path to save the input Zarr (used when --input_file is supplied)",
    )
    parser.add_argument(
        "--batch_size",
        default=1,
        type=int,
        help="Batch size for each worker (default: 1, to avoid Cellpose 3 tiling bugs)",
    )
    parser.add_argument(
        "--write_zarr",
        default=None,
        help="Path to write the output stitched Zarr (default: <output_tif>.zarr)",
    )
    parser.add_argument(
        "--optimize_parallel",
        action="store_true",
        default=False,
        help="If set, automatically find the best blocksize and worker count for speed. If unset, uses 1 worker.",
    )
    parser.add_argument(
        "--keep_intermediate",
        action="store_true",
        default=False,
        help="If set, do not delete the intermediate stitched Zarr after TIFF conversion",
    )
    parser.add_argument(
        "--log_dir",
        default=None,
        help="Directory where the log file should be saved",
    )
    args, unknown_args = parser.parse_known_args()

    blocksize = parse_blocksize(args.blocksize)

    # Ensure all input/output paths are absolute before potentially changing directory
    if args.input_file:
        args.input_file = os.path.abspath(args.input_file)
    if args.input_dir:
        args.input_dir = os.path.abspath(args.input_dir)
    if args.output_tif:
        args.output_tif = os.path.abspath(args.output_tif)
    if args.output_dir:
        args.output_dir = os.path.abspath(args.output_dir)
    if args.write_zarr:
        args.write_zarr = os.path.abspath(args.write_zarr)
    if args.model and os.path.exists(args.model):
        args.model = os.path.abspath(args.model)

    # Choose a safe temporary directory if none provided. Prefer a Fiji
    # installation directory (when running from Fiji), otherwise fall back
    # to the user's home directory. Ensure the chosen directory is writable
    # and create a per-user hidden subfolder to avoid creating folders in
    # protected system locations (e.g., C:\Windows).
    def _choose_safe_tempdir(provided):
        if provided:
            return provided
        candidates = [
            os.environ.get("FIJI_HOME"),
            os.environ.get("FIJI_APPDIR"),
            os.environ.get("FIJI_INSTALL_DIR"),
            os.environ.get("Fiji.app"),
            os.path.expanduser("~"),
        ]
        name = f".{getpass.getuser()}_distributed_cellpose"
        for base in candidates:
            if not base:
                continue
            try:
                if os.path.isfile(base):
                    base = os.path.dirname(base)
                if not os.path.exists(base):
                    continue
                candidate = os.path.join(base, name)
                os.makedirs(candidate, exist_ok=True)
                testfile = os.path.join(candidate, ".write_test")
                with open(testfile, "w") as f:
                    f.write("x")
                os.remove(testfile)
                return candidate
            except Exception:
                continue

        # Last-resort: use system temp dir under a user-specific folder
        try:
            sys_tmp = tempfile.gettempdir()
            candidate = os.path.join(sys_tmp, name)
            os.makedirs(candidate, exist_ok=True)
            return candidate
        except Exception:
            return None

    args.temporary_directory = _choose_safe_tempdir(args.temporary_directory)

    # Determine log directory (prefer --log_dir, then --output_dir, then --temporary_directory)
    log_dir = args.log_dir or args.output_dir or args.temporary_directory
    log_file = None
    log_handle = None
    stop_heartbeat = None
    hb_thread = None

    if log_dir:
        try:
            os.makedirs(log_dir, exist_ok=True)
            # Force the working directory to a sane, writable location.
            if args.temporary_directory:
                os.chdir(args.temporary_directory)
                print(
                    f"Changed working directory to safe location: {args.temporary_directory}"
                )

            log_file = os.path.join(log_dir, f"cellpose_dist_{int(time.time())}.log")

            # Open a single file handle for all redirections to avoid conflicts
            log_handle = open(log_file, "a", encoding="utf-8")
            log_lock = threading.Lock()

            # Redirect stdout and stderr using our thread-safe Tee class
            sys.stdout = Tee(sys.stdout, log_handle, lock=log_lock)
            sys.stderr = Tee(sys.stderr, log_handle, lock=log_lock)

            # We don't add a separate FileHandler to the logger because the logger
            # already writes to sys.stderr (via StreamHandler), which we just redirected.
            # Adding another handler would result in duplicate entries.
            print(f"Persistent log file created at: {log_file}")
            sys.stdout.flush()

            # Start heartbeat thread so the user can see it hasn't crashed even if
            # no dask output happens for a while.
            stop_heartbeat = threading.Event()
            hb_thread = threading.Thread(
                target=heartbeat, args=(stop_heartbeat, log_file), daemon=True
            )
            hb_thread.start()

        except Exception as e:
            print(f"Warning: Could not setup persistent logging: {e}")
            sys.stdout.flush()
            log_file = None
            log_handle = None
            stop_heartbeat = None
            hb_thread = None

    # one of --output_tif or --output_dir must be provided
    if not args.output_tif and not args.output_dir:
        parser.error("Either --output_tif or --output_dir must be provided")

    # Validate that the Python environment has the required modules
    ds, cellpose_version = validate_runtime_requirements()

    # Apply compatibility wrapper early...
    try:
        print("Applying zarr compatibility patches...")
        sys.stdout.flush()
        _apply_zarr_open_compat(zarr, ds, reload_ds=True)
    except Exception as e:
        print(f"Warning: Could not apply patches: {e}")
        pass

    # Prepare input zarr
    print("Preparing input data...")
    sys.stdout.flush()
    tmpdir = None
    channel_zarrs = []  # Will be populated for multi-channel inputs

    if args.input_file:
        print(f"Opening TIFF file: {args.input_file}")
        sys.stdout.flush()
        # Load TIFF lazily using aszarr=True to avoid memory pressure
        im = tifffile.imread(args.input_file, aszarr=True)
        # Wrap in a dask array for convenient manipulation if needed, or use directly
        if hasattr(im, "shape"):
            print(
                f"Loaded input {args.input_file} lazily as zarr-like object: {im.shape}"
            )
        else:
            # Fallback for unexpected formats
            with tifffile.TiffFile(args.input_file) as tif:
                im = tif.asarray()

        # warn about input dtype but accept common integer/float types
        if im.dtype not in ("uint8", "uint16", "uint32", "float32", "float64"):
            print(
                f"Warning: input image dtype is {im.dtype}. This script preserves the dtype in the input Zarr; results may be unexpected."
            )

        print(f"Input shape: {im.shape}, blocksize: {blocksize}")

        # Detect multi-channel input and prepare for preprocessing_steps approach.
        # Prefer explicit 4D layout (Z, C, Y, X) with c_axis=1, but also try to
        # auto-detect a small channel axis in multi-dimensional inputs.
        c_axis_pos = None
        if im.ndim >= 4:
            # For 4D (Z, C, Y, X) or 5D (T, Z, C, Y, X), channel is typically at axis -3
            # but we can also check for the smallest dimension among the first ndim-2 axes.
            spatial_dims = 2
            possible_axes = list(range(im.ndim - spatial_dims))
            # Heuristic: choose axis with size <= 4 if plural, or choose axis 1 for 4D
            if im.ndim == 4:
                c_axis_pos = 1
            else:
                # 5D or more: find smallest candidate
                candidates = [i for i in possible_axes if im.shape[i] <= 4]
                if candidates:
                    # Prefer the one closest to spatial dims but not spatial
                    c_axis_pos = candidates[-1]
        elif im.ndim == 3:
            # Heuristic: treat a small axis (<=4) as channel axis when the other
            # axes are significantly larger (to avoid confusing thin spatial
            # dimensions with channels).
            sizes = im.shape
            candidates = [
                i
                for i, s in enumerate(sizes)
                if s <= 4 and max(sizes[j] for j in range(len(sizes)) if j != i) > 10
            ]
            if candidates:
                c_axis_pos = candidates[0]

        if c_axis_pos is not None and im.shape[c_axis_pos] > 1:
            # Multi-channel data: extract ONLY the channels required by the user.
            num_channels_total = im.shape[c_axis_pos]

            # Determine which 0-based indices are needed.
            # args.chan and args.chan2 are 1-based (0 = grayscale or None).
            needed_indices = []
            c1_idx = (args.chan - 1) if args.chan > 0 else 0
            needed_indices.append(c1_idx)

            if args.chan2 > 0:
                c2_idx = args.chan2 - 1
                if c2_idx != c1_idx:
                    needed_indices.append(c2_idx)

            print(
                f"Detected {num_channels_total} channels total at axis {c_axis_pos} (shape {im.shape})"
            )
            print(
                f"Extracting only required channels: {[idx + 1 for idx in needed_indices]}"
            )
            sys.stdout.flush()

            # Extract spatial dimensions (remove channel axis)
            spatial_shape = tuple(s for i, s in enumerate(im.shape) if i != c_axis_pos)

            # Match blocksize rank to spatial_shape rank for Zarr chunks
            zarr_chunks = blocksize
            if len(zarr_chunks) > len(spatial_shape):
                zarr_chunks = zarr_chunks[-len(spatial_shape) :]
            elif len(zarr_chunks) < len(spatial_shape):
                zarr_chunks = (max(spatial_shape),) * (
                    len(spatial_shape) - len(zarr_chunks)
                ) + zarr_chunks

            # Create zarr for each channel
            if args.input_zarr:
                parent = os.path.dirname(args.input_zarr) or "."
                os.makedirs(parent, exist_ok=True)
            else:
                tmpdir = tempfile.TemporaryDirectory(
                    prefix="distributed_cellpose_tmp_", dir=args.temporary_directory
                )
                parent = tmpdir.name

            for i, ch_idx in enumerate(needed_indices):
                if ch_idx >= num_channels_total:
                    print(
                        f"Warning: requested channel {ch_idx + 1} exceeds image depth {num_channels_total}. Falling back to channel 1."
                    )
                    ch_idx = 0

                # Create zarr for this channel
                ch_path = os.path.join(parent, f"channel_{ch_idx}.zarr")

                # Robustly extract channel data lazily using dask
                # We use rechunk(zarr_chunks) before to_zarr to satisfy Zarr requirements
                # and avoid "multiple values for keyword argument 'chunks'" errors.
                # Use as_zarr=True if it was loaded as such via tifffile
                print(f"  Channel {ch_idx + 1}: extracting to {ch_path}...")
                sys.stdout.flush()

                try:
                    ch_dask = da.from_array(im, chunks="auto").take(
                        ch_idx, axis=c_axis_pos
                    )
                    ch_dask.rechunk(zarr_chunks).to_zarr(ch_path, overwrite=True)
                    z_ch = zarr.open(ch_path, mode="r")
                    channel_zarrs.append(z_ch)
                    print(f"  Channel {ch_idx + 1}: success (shape={z_ch.shape})")
                except Exception as e:
                    print(
                        f"  Channel {ch_idx + 1}: failed to extract lazily ({e}). Trying eager..."
                    )
                    ch_data = np.take(im, ch_idx, axis=c_axis_pos)
                    z_ch = zarr.open(
                        ch_path,
                        mode="w",
                        shape=spatial_shape,
                        chunks=zarr_chunks,
                        dtype=im.dtype,
                    )
                    z_ch[...] = ch_data
                    channel_zarrs.append(z_ch)

            # Use first requested channel as input_zarr
            input_zarr = channel_zarrs[0]
            # Ensure blocksize matches the rank of the spatial zarr
            blocksize = zarr_chunks
            print(
                f"Using input channel {needed_indices[0] + 1} as base, shape: {input_zarr.shape}, blocksize: {blocksize}"
            )
            sys.stdout.flush()
        else:
            # Single channel or 3D input: standard flow
            # Match blocksize rank to image rank
            if len(blocksize) > im.ndim:
                blocksize = blocksize[-im.ndim :]
            elif len(blocksize) < im.ndim:
                blocksize = (max(im.shape),) * (im.ndim - len(blocksize)) + blocksize

            if args.input_zarr:
                zpath = args.input_zarr
                parent = os.path.dirname(zpath) or "."
                os.makedirs(parent, exist_ok=True)

                print(f"Writing input Zarr to {zpath} (dtype={im.dtype})...")
                sys.stdout.flush()
                try:
                    da.from_array(im, chunks="auto").rechunk(blocksize).to_zarr(
                        zpath, overwrite=True
                    )
                    input_zarr = zarr.open(zpath, mode="r")
                except Exception as e:
                    print(f"Lazy write failed ({e}), using eager fallback...")
                    z = zarr.open(
                        zpath,
                        mode="w",
                        shape=im.shape,
                        chunks=blocksize,
                        dtype=im.dtype,
                    )
                    z[...] = im
                    input_zarr = z

                print(
                    f"Input zarr shape: {input_zarr.shape}, chunks: {input_zarr.chunks}"
                )
                sys.stdout.flush()
            else:
                tmpdir = tempfile.TemporaryDirectory(
                    prefix="distributed_cellpose_tmp_", dir=args.temporary_directory
                )
                zpath = os.path.join(tmpdir.name, "input.zarr")

                print(f"Writing temporary input Zarr to {zpath}...")
                sys.stdout.flush()
                try:
                    da.from_array(im, chunks="auto").rechunk(blocksize).to_zarr(
                        zpath, overwrite=True
                    )
                    input_zarr = zarr.open(zpath, mode="r")
                except Exception as e:
                    print(f"Lazy write failed ({e}), using eager fallback...")
                    z = zarr.open(
                        zpath,
                        mode="w",
                        shape=im.shape,
                        chunks=blocksize,
                        dtype=im.dtype,
                    )
                    z[...] = im
                    input_zarr = z
                print(f"Created temporary input Zarr at {zpath} (dtype={im.dtype})")
                print(
                    f"Input zarr shape: {input_zarr.shape}, chunks: {input_zarr.chunks}"
                )
                sys.stdout.flush()
    else:
        # Wrap folder of TIFFs (this uses tifffile.imread with aszarr=True)
        # allow a simple glob pattern
        filename_pattern = os.path.join(args.input_dir, "*.tif")
        input_zarr = ds.wrap_folder_of_tiffs(filename_pattern)
        print(f"Wrapped folder into Zarr (pattern={filename_pattern})")
        tmpdir = None

    # Worker patching handles float slice coercion, no proxy needed

    # Determine model_kwargs
    model_kwargs = (
        {"model_type": args.model, "gpu": args.use_gpu}
        if isinstance(args.model, str)
        else args.model
    )

    # GUI and CLI now use 1-based indexing consistently (1=First, 2=Second, 0=None).
    # However, if we subsetted the channels during extraction (len(channel_zarrs) > 0),
    # the 'channels' indices must refer to the indices in the stack we created.
    if len(channel_zarrs) >= 1:
        # If we have only 1 channel in our list, it's [1, 0]
        # If we have 2, it's [1, 2] (following the order in needed_indices)
        c1 = 1
        c2 = 2 if len(channel_zarrs) > 1 else 0
        channels = [c1, c2]
        print(f"Subsetting/Stacking mode: mapping channels to stack indices {channels}")
    else:
        # Single channel or folder of TIFFs: use user-provided indices
        c1 = args.chan if args.chan > 0 else 1
        c2 = args.chan2 if args.chan2 > 0 else 0
        channels = [c1, c2]

    # Ensure diameter is an integer to avoid float-based slice indices
    try:
        coerced_diameter = int(math.ceil(float(args.diameter)))
    except Exception:
        coerced_diameter = args.diameter

    eval_kwargs = {
        "diameter": coerced_diameter,
        "channels": channels,
        "flow_threshold": args.flow_threshold,
        "cellprob_threshold": args.cellprob_threshold,
        "stitch_threshold": args.stitch_threshold,
        "min_size": args.min_size,
        "batch_size": args.batch_size,
        # Enable 3D segmentation
        "do_3D": True,
    }

    if args.anisotropy != 1.0:
        eval_kwargs["anisotropy"] = args.anisotropy
        print(f"Added anisotropy to eval_kwargs: {args.anisotropy}")

    # When using preprocessing_steps to stack channels, we specify the axis
    # parameters to match the stack produced by stack_channels.
    if len(channel_zarrs) > 1:
        # stack_channels uses axis=1 for 3D (Z, C, Y, X) and axis=0 for 2D (C, Y, X)
        if input_zarr.ndim == 3:
            eval_kwargs["channel_axis"] = 1
            eval_kwargs["z_axis"] = 0
            print(
                f"Multi-channel 3D stack detected: setting channel_axis=1, z_axis=0 (channels={channels})"
            )
        else:
            eval_kwargs["channel_axis"] = 0
            eval_kwargs["do_3D"] = False
            print(
                f"Multi-channel 2D stack detected: setting channel_axis=0, do_3D=False (channels={channels})"
            )
    else:
        # For single channel 3D images (Z, Y, X), Cellpose handles it natively.
        # But for Cellpose 4.x we can be explicit.
        if (
            cellpose_version
            and cellpose_version.startswith("4")
            and input_zarr.ndim == 3
        ):
            eval_kwargs["z_axis"] = 0
            print("Cellpose 4.x detected: setting z_axis=0")

    # Normalize diameter to integer to avoid float slice indices in zarr
    try:
        if "diameter" in eval_kwargs:
            try:
                eval_kwargs["diameter"] = int(math.ceil(float(eval_kwargs["diameter"])))
                print(
                    f"Normalized eval_kwargs['diameter'] to {eval_kwargs['diameter']}"
                )
                sys.stdout.flush()
            except Exception:
                pass
    except Exception:
        pass

    # Parse custom parameters from remaining unknown arguments
    # Can be boolean flags: ['--do_3D', '--verbose']
    # Or key-value pairs: ['--flow3D_smooth', '3', '--anisotropy', '2.0']

    # We force do_3D=False for 2D images to avoid logic errors in Cellpose
    if input_zarr.ndim == 2:
        eval_kwargs["do_3D"] = False
        print("2D input detected: setting eval_kwargs['do_3D'] = False")

    i = 0
    while i < len(unknown_args):
        arg = unknown_args[i]
        if arg.startswith("--"):
            key = arg[2:]  # Remove '--' prefix

            # Check if next arg is a value or another flag
            if i + 1 < len(unknown_args) and not unknown_args[i + 1].startswith("--"):
                # This is a key-value pair
                value = unknown_args[i + 1]
                # Try to convert to appropriate type
                try:
                    # Try boolean
                    if value.lower() in ("true", "false"):
                        eval_kwargs[key] = value.lower() == "true"
                    # Try float
                    elif "." in value:
                        eval_kwargs[key] = float(value)
                    # Try int
                    else:
                        eval_kwargs[key] = int(value)
                except (ValueError, AttributeError):
                    # Keep as string if conversion fails
                    eval_kwargs[key] = value
                i += 2
            else:
                # This is a boolean flag (store_true)
                eval_kwargs[key] = True
                i += 1
        else:
            i += 1

    # Auto-detect n_workers and ncpus if not specified
    if args.n_workers is None:
        if args.use_gpu:
            # GPU mode: start with 1 worker, will be optimized based on GPU memory
            args.n_workers = 1
        else:
            # CPU mode: use half of available CPU cores
            args.n_workers = max(1, os.cpu_count() // 2)
        print(f"Auto-detected n_workers: {args.n_workers}")

    if args.ncpus is None:
        if args.use_gpu:
            # GPU mode: more CPUs for data loading/preprocessing
            args.ncpus = max(2, os.cpu_count() // 4)
        else:
            # CPU mode: 1 CPU per worker
            args.ncpus = 1
        print(f"Auto-detected ncpus: {args.ncpus}")

    # If optimize_parallel is false, and n_workers is 0/None, we keep n_workers at 1
    # to maintain a safe, single-worker baseline for the user.
    if not args.optimize_parallel and (args.n_workers is None or args.n_workers == 0):
        print(
            "Parallelism optimization disabled. Using 1 worker for baseline stability."
        )
        args.n_workers = 1

    # Optimize n_workers and threads_per_worker based on GPU memory availability
    optimal_n_workers, dask_threads, internal_threads = get_optimal_n_workers(
        use_gpu=args.use_gpu,
        requested_n_workers=args.n_workers,
        blocksize=blocksize,
        model_type=args.model if isinstance(args.model, str) else "cyto3",
        diameter=args.diameter,
    )

    # If optimize_parallel is set, check if we can improve blocksize to allow more parallelism
    if args.optimize_parallel and optimal_n_workers < 2 and args.use_gpu:
        print("Attempting to find more optimal blocksize for parallel execution...")
        z, y, x = blocksize
        if y > 256 or x > 256:
            new_y, new_x = min(y, 256), min(x, 256)
            new_blocksize = (z, new_y, new_x)
            print(f"Testing smaller blocksize: {new_blocksize}")

            n_p, d_p, i_p = get_optimal_n_workers(
                use_gpu=args.use_gpu,
                requested_n_workers=args.n_workers,
                blocksize=new_blocksize,
                model_type=args.model if isinstance(args.model, str) else "cyto3",
                diameter=args.diameter,
            )

            if n_p > optimal_n_workers:
                print(
                    f"Found better parameters: workers={n_p}, blocksize={new_blocksize}"
                )
                optimal_n_workers = n_p
                dask_threads = d_p
                internal_threads = i_p
                blocksize = new_blocksize

    # CRITICAL: Set environment variables in main process BEFORE creating cluster
    # These control thread limits for worker processes that will be spawned
    print("\n=== Setting global thread limits in main process ===")
    print(
        f"Workers: {optimal_n_workers}, Dask threads: {dask_threads}, Internal threads: {internal_threads}"
    )
    os.environ["DASK_DISTRIBUTED__WORKER__THREADS"] = str(dask_threads)
    os.environ["OMP_NUM_THREADS"] = str(internal_threads)
    os.environ["MKL_NUM_THREADS"] = str(internal_threads)
    os.environ["OPENBLAS_NUM_THREADS"] = str(internal_threads)
    os.environ["NUMEXPR_NUM_THREADS"] = str(internal_threads)
    # Improve PyTorch CUDA allocation behavior to reduce fragmentation on small GPUs
    if args.use_gpu:
        try:
            os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"
            print(
                "Set PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True to reduce fragmentation"
            )
        except Exception:
            pass
    print(
        f"Environment variables set - internal threads will be limited to {internal_threads}"
    )
    sys.stdout.flush()

    cluster_kwargs = {
        "n_workers": optimal_n_workers,
        "threads_per_worker": dask_threads,  # Use threads_per_worker instead of ncpus
        # Do not pass 'ncpus' here; newer distributed versions reject it.
        # Increase worker death timeout - GPU workers need more time to release CUDA contexts
        "death_timeout": 60 if args.use_gpu else 15,
        # Ask the cluster to use our safe temporary directory for worker files
        "local_directory": args.temporary_directory,
        # On Windows/Networked machines, fixed ports can cause hangs.
        # dashboard_address=None disables the dashboard if not explicitly requested.
        "dashboard_address": ":0" if getattr(args, "show_dashboard", False) else None,
    }

    # decide where to write the final output TIFF and stitched Zarr
    if args.output_tif:
        output_tif = args.output_tif
        # We no longer enforce _labels suffix if a specific path is provided,
        # to respect programmatic calls from Java/Fiji that expect a specific path.
    else:
        # derive basename from input and append a suffix to indicate labels
        if args.input_file:
            input_base = os.path.basename(args.input_file)
            if input_base.lower().endswith(".ome.tif"):
                base = input_base[: -len(".ome.tif")]
                suffix = ".ome_labels.ome.tif"
            else:
                base = os.path.splitext(input_base)[0]
                suffix = "_labels.tif"
        else:
            base = os.path.basename(os.path.normpath(args.input_dir))
            suffix = "_labels.tif"
        output_dir = args.output_dir
        os.makedirs(output_dir, exist_ok=True)
        output_tif = os.path.join(output_dir, base + suffix)

    # Derive stitched Zarr path from output TIFF base if not explicitly provided
    if args.write_zarr:
        write_zarr = args.write_zarr
    else:
        write_zarr = os.path.splitext(output_tif)[0] + ".zarr"

    # Safety check: avoid writing the output Zarr into the same path as the
    # input Zarr or inside the input directory. In-place overwrite of an input
    # store may cause repeated reads/writes leading to apparent infinite loops
    # or corrupted output when cellpose/processes read while writing. If a
    # conflict is detected, choose a distinct path with a `_labels` suffix.
    try:
        input_store_path = None
        try:
            if "input_zarr" in locals() and input_zarr is not None:
                store = getattr(input_zarr, "store", None)
                if isinstance(store, str):
                    input_store_path = store
                elif hasattr(store, "path"):
                    input_store_path = store.path
        except Exception:
            input_store_path = None

        if input_store_path:
            abs_in = os.path.abspath(input_store_path)
            abs_out = os.path.abspath(write_zarr)
            if abs_in == abs_out or abs_out.startswith(abs_in + os.sep):
                # derive a safe non-conflicting path
                base = os.path.splitext(output_tif)[0]
                write_zarr = base + "_labels.zarr"
                # ensure TIFF also reflects labels suffix when not explicitly provided
                if not args.output_tif:
                    output_tif = base + "_labels.tif"
                logger.warning(
                    "Adjusted write_zarr to avoid overwriting input: %s", write_zarr
                )
    except Exception:
        # best-effort only; if this fails, continue with the original write_zarr
        pass

    if os.path.exists(write_zarr):
        print(f"Warning: will overwrite existing Zarr at {write_zarr}")
    # Some versions of zarr changed the signature of zarr.open to make
    # the 'mode' parameter keyword-only. Upstream distributed_segmentation
    # calls zarr.open(path, 'w', ...). Detect that case and monkeypatch
    # zarr.open to accept a positional mode to remain compatible.
    try:
        # Always wrap zarr.open to accept positional mode argument for compatibility.
        _orig_zarr_open = zarr.open

        def _zarr_open_compat(*args, **kwargs):
            # If second positional arg looks like a mode string, forward as keyword
            if len(args) >= 2 and isinstance(args[1], str):
                return _orig_zarr_open(store=args[0], mode=args[1], *args[2:], **kwargs)
            # otherwise try to forward as-is (works if signature accepts positional store only)
            return _orig_zarr_open(*args, **kwargs)

        zarr.open = _zarr_open_compat
        # patch the reference inside the distributed_segmentation module as well, if present
        try:
            if hasattr(ds, "zarr") and hasattr(ds.zarr, "open"):
                ds.zarr.open = zarr.open
        except Exception:
            pass

        # Apply patches for Zarr compatibility (float slice handling)
        # Prefer using the shared worker_patches module if available
        patches_applied = False
        try:
            if worker_patches is not None:
                worker_patches.apply_zarr_patches()
                patches_applied = True
                logger.info("Applied zarr patches via worker_patches module")
        except Exception:
            pass

        if not patches_applied:
            logger.info("worker_patches not available; applying inline zarr patches")
            # Inline fallback: monkeypatch Array.__getitem__ and __setitem__
            try:
                if _zarr_array is not None:
                    _orig_get_main = _zarr_array.Array.__getitem__
                    _orig_set_main = _zarr_array.Array.__setitem__

                    def _coerce(sel, mode="expand"):
                        # Minimal recursive coercion with mode support
                        if isinstance(sel, slice):
                            if sel.start is None:
                                start = 0
                            else:
                                val = float(sel.start)
                                start = (
                                    int(round(val))
                                    if mode == "nearest"
                                    else int(math.floor(val))
                                )

                            if sel.stop is None:
                                stop = None  # zarr handles None, or we can't easily know dim_len here without 'self'
                            else:
                                val = float(sel.stop)
                                if mode == "nearest" and sel.start is not None:
                                    # Shape preserving
                                    try:
                                        length = val - float(sel.start)
                                        stop = start + int(round(length))
                                    except:
                                        stop = int(round(val))
                                else:
                                    stop = (
                                        int(round(val))
                                        if mode == "nearest"
                                        else int(math.ceil(val))
                                    )

                            # Handle step
                            step = sel.step
                            if step is not None:
                                try:
                                    step = int(float(step))
                                except:
                                    pass

                            return slice(start, stop, step)
                        elif isinstance(sel, (tuple, list)):
                            return tuple(_coerce(s, mode) for s in sel)
                        elif hasattr(sel, "astype"):
                            return sel.astype(int)
                        elif isinstance(
                            sel, (numbers.Number, np.generic)
                        ) and not isinstance(sel, int):
                            return int(float(sel))
                        return sel

                    def _getitem_main(self, selection):
                        # Use floor for Zarr getitem as well to stay consistent with block boundaries
                        new_sel = _coerce(selection, mode="expand")
                        return _orig_get_main(self, new_sel)

                    def _setitem_main(self, selection, value):
                        # Force slice length to match data length to prevent Zarr crash on mismatch
                        try:
                            if isinstance(selection, tuple) and hasattr(value, "shape"):
                                slices_count = sum(
                                    1 for s in selection if isinstance(s, slice)
                                )
                                if slices_count == len(value.shape):
                                    new_sel_list = []
                                    v_idx = 0
                                    for s in selection:
                                        if isinstance(s, slice):
                                            length = value.shape[v_idx]
                                            if s.start is None:
                                                start = 0
                                            else:
                                                try:
                                                    # Use math.floor for start to match grid alignment
                                                    start = int(
                                                        math.floor(float(s.start))
                                                    )
                                                except:
                                                    start = 0

                                            stop = start + length

                                            step = s.step
                                            if step is not None:
                                                try:
                                                    step = int(float(step))
                                                except:
                                                    pass

                                            new_sel_list.append(
                                                slice(start, stop, step)
                                            )
                                            v_idx += 1
                                        else:
                                            new_sel_list.append(
                                                _coerce(s, mode="nearest")
                                            )
                                    return _orig_set_main(
                                        self, tuple(new_sel_list), value
                                    )
                        except Exception:
                            pass

                        new_sel = _coerce(selection, mode="nearest")
                        return _orig_set_main(self, new_sel, value)

                    _zarr_array.Array.__getitem__ = _getitem_main
                    _zarr_array.Array.__setitem__ = _setitem_main
                    logger.info("Patched Array.__getitem__ and __setitem__ (inline)")

                # Also patch SliceDimIndexer if available
                if _z_idx is not None:
                    _z_idx_main = _z_idx
                    _orig_slice_init_main = _z_idx_main.SliceDimIndexer.__init__

                    def _slice_init_compat_main(self, dim_sel, dim_len, dim_chunk_len):
                        def _coerce_dim(sel):
                            if isinstance(sel, slice):
                                # Coerce to integer bounds and clamp to dimension length
                                # Using mode='nearest' logic: round floats to integers
                                if sel.start is None:
                                    start = 0
                                else:
                                    start = int(round(float(sel.start)))

                                if sel.stop is None:
                                    stop = dim_len
                                else:
                                    stop = int(round(float(sel.stop)))

                                # Clamp
                                if start < 0:
                                    start = max(0, dim_len + start)
                                if stop < 0:
                                    stop = max(0, dim_len + stop)
                                stop = min(stop, dim_len)
                                # Fix potential empty slice if bounds crossed due to rounding
                                if stop < start:
                                    stop = start

                                step = sel.step
                                if step is not None:
                                    try:
                                        step = int(float(step))
                                    except:
                                        step = 1
                                else:
                                    step = 1
                                return slice(start, stop, step)
                            return sel  # Assume other types handled or passed through

                        coerced = _coerce_dim(dim_sel)
                        return _orig_slice_init_main(
                            self, coerced, dim_len, dim_chunk_len
                        )

                    _z_idx_main.SliceDimIndexer.__init__ = _slice_init_compat_main
            except Exception:
                pass

        # Reload the distributed_segmentation module to ensure any imported references
        # to zarr.open inside the module pick up our wrapper.
        try:
            importlib.reload(ds)
            logger.info("Reloaded distributed_segmentation after zarr.open patch.")
        except Exception as e:
            print("Could not reload distributed_segmentation:", e)

        logger.info(
            "Patched zarr.open to be compatible with older callers (accepts positional mode)."
        )
    except Exception as e:
        print("Could not apply zarr.open compatibility patch:", e)

    logger.info("Starting distributed segmentation...")
    out_zarr, boxes = _run_distributed_eval(
        ds,
        input_zarr,
        write_zarr,
        blocksize,
        model_kwargs,
        eval_kwargs,
        cluster_kwargs,
        args,
        channel_zarrs,
        log_file,
    )

    logger.debug(f"Stitched Zarr written to {write_zarr}")

    # Convert the zarr to a single TIFF file (uint32 labels)
    try:
        # Use streaming write for large volumes to avoid loading everything into RAM
        # Use the global tifffile imported at module level

        # Ensure we have a valid numpy-like shape
        shape = out_zarr.shape
        dtype = np.uint32  # Labels are typically uint32

        nbytes = np.prod(shape) * np.dtype(dtype).itemsize
        big_threshold = 4 * 1024**3  # 4 GiB
        is_bigtiff = nbytes > big_threshold

        logger.info(
            f"Writing output TIFF (streaming) to {output_tif} (shape={shape}, bigtiff={is_bigtiff})"
        )

        # Prepare ImageJ or OME metadata
        axes = "YX" if len(shape) == 2 else ("ZYX" if len(shape) == 3 else "ZCYX")
        metadata = {"axes": axes}
        is_ome = output_tif.lower().endswith(".ome.tif")

        with tifffile.TiffWriter(output_tif, bigtiff=is_bigtiff, ome=is_ome) as tw:
            if len(shape) == 2:
                tw.write(out_zarr[:].astype(dtype), metadata=metadata)
            elif len(shape) == 3:
                # 3D: Write slice by slice to save memory
                for z in range(shape[0]):
                    # Only add metadata to the first page for ImageJ/OME compatibility
                    page_metadata = metadata if z == 0 else None
                    tw.write(
                        out_zarr[z, :, :].astype(dtype),
                        metadata=page_metadata,
                        contiguous=True,
                    )
            elif len(shape) == 4:
                # 4D (e.g. Z, C, Y, X): Write plane by plane
                first_plane = True
                for i in range(shape[0]):
                    for j in range(shape[1]):
                        page_metadata = metadata if first_plane else None
                        tw.write(
                            out_zarr[i, j, :, :].astype(dtype),
                            metadata=page_metadata,
                            contiguous=True,
                        )
                        first_plane = False
            else:
                # Higher dims: fall back to eager for now if rare, or implement more loops
                tw.write(out_zarr[...].astype(dtype), metadata=metadata)

        logger.debug(f"Successfully wrote streaming TIFF to {output_tif}")
        logger.debug(f"Stitched Zarr remains at: {write_zarr}")
        # Remove intermediate stitched Zarr to avoid leaving large temporary stores
        try:
            if write_zarr and os.path.exists(write_zarr) and not args.keep_intermediate:
                logger.info(f"Removing intermediate Zarr: {write_zarr}")
                try:
                    if os.path.isdir(write_zarr):
                        shutil.rmtree(write_zarr, ignore_errors=True)
                    else:
                        os.remove(write_zarr)
                except Exception as _e:
                    logger.warning(
                        "Could not remove intermediate Zarr %s: %s", write_zarr, _e
                    )
            elif write_zarr and os.path.exists(write_zarr) and args.keep_intermediate:
                logger.info(f"Retaining intermediate Zarr for debugging: {write_zarr}")
        except Exception:
            pass
    except Exception as e:
        # Provide more context in the error message to help debugging in Fiji
        print("Could not write output TIFF:", e)
        logger.exception("TIFF write failed")

    if tmpdir is not None:
        tmpdir.cleanup()

    # Ensure any Dask clients are closed and logging is flushed so the
    # process can exit cleanly. This prevents lingering scheduler/worker
    # threads from keeping the Fiji launcher process alive after work
    # completes when invoked from the GUI.
    try:
        if default_client is not None:
            try:
                c = default_client()
                try:
                    c.close()
                except Exception:
                    pass
            except Exception:
                pass
    except Exception:
        # dask not available or no active client
        pass

    try:
        logging.shutdown()
    except Exception:
        pass

    try:
        print("Finished distributed segmentation.")
        sys.stdout.flush()
    except Exception:
        pass

    # Stop heartbeat thread and close log handle before exiting
    try:
        if stop_heartbeat is not None:
            stop_heartbeat.set()
        if hb_thread is not None:
            hb_thread.join(timeout=1.0)
    except Exception:
        pass

    try:
        if log_handle is not None:
            log_handle.close()
    except Exception:
        pass

    # Make sure the process terminates when run as a script (safe because
    # `main()` is only invoked in the __main__ guard). Use sys.exit to set
    # an explicit exit code and avoid leaving lingering non-daemon threads.
    try:
        sys.exit(0)
    except Exception:
        return


if __name__ == "__main__":
    # Ensure multiprocessing method is compatible with Dask/distributed
    # We do NOT force spawn here because it might be causing initialization
    # failures or stale object references in certain environments.
    # Let Dask/System decide the safest method.
    multiprocessing.freeze_support()
    main()
