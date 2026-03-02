#!/usr/bin/env python3
"""Cellpose Distributed CLI helper.

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
import os
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import webbrowser
from typing import Any, Dict, List, Optional, Tuple, Union

import numpy as np

try:
    import scipy.ndimage as ndimage
except ImportError:
    ndimage = None

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
    import distributed
    from dask.distributed import Client, LocalCluster, default_client
    from distributed import WorkerPlugin as _WP

    # Use 'tasks' method for rechunk to avoid memory explosion on the scheduler
    # when dealing with complex graphs or many small chunks.
    dask.config.set({"array.rechunk.method": "tasks"})
except ImportError:
    dask = None
    da = None
    Client = None
    LocalCluster = None
    default_client = None
    distributed = None
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


def _update_log_handlers():
    """Update all active logging handlers to use the current sys.stderr."""
    try:
        # Update root logger
        root = logging.getLogger()
        for handler in root.handlers:
            if isinstance(handler, logging.StreamHandler):
                handler.setStream(sys.stderr)

        # Explicitly update all registered loggers
        for name in logging.Logger.manager.loggerDict:
            log_instance = logging.getLogger(name)
            for handler in log_instance.handlers:
                if isinstance(handler, logging.StreamHandler):
                    handler.setStream(sys.stderr)
    except Exception:
        pass


def _require_ndimage(func):
    """Decorator to skip functions that require scipy.ndimage if it is missing."""

    def wrapper(*args, **kwargs):
        if ndimage is None:
            print(
                f"Warning: {func.__name__} requires scipy.ndimage (missing). Skipping."
            )
            return None
        return func(*args, **kwargs)

    return wrapper


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
            _update_log_handlers()
            print(f"Worker {os.getpid()} logging redirected to {log_file}")
        except Exception as e:
            print(f"Worker {os.getpid()} could not redirect logging: {e}")


def is_port_in_use(port: int) -> bool:
    """Check if a TCP port is currently being used on localhost."""
    with socket.socket(socket.socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


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


def _get_pixel_size_from_tiff(
    tiff_path,
) -> Tuple[Optional[float], Optional[float], Optional[float]]:
    """Try to extract pixel resolution from TIFF metadata using tifffile."""
    vx, vy, vz = None, None, None
    try:
        with tifffile.TiffFile(tiff_path) as tif:
            page = tif.pages[0]
            # Handle standard TIFF resolution tags
            resx = page.tags.get("XResolution")
            resy = page.tags.get("YResolution")
            unit = page.tags.get("ResolutionUnit")

            if resx and resy:
                vx = resx.value[1] / resx.value[0] if resx.value[0] != 0 else None
                vy = resy.value[1] / resy.value[0] if resy.value[0] != 0 else None
                # Check for unit (2=inch, 3=cm). Convert to um if known.
                if unit and unit.value == 3:  # cm
                    if vx:
                        vx *= 10000
                    if vy:
                        vy *= 10000
                elif unit and unit.value == 2:  # inch
                    if vx:
                        vx *= 25400
                    if vy:
                        vy *= 25400

            # Try to read ImageJ metadata (if present)
            if hasattr(page, "imagej_metadata") and page.imagej_metadata:
                ij_meta = page.imagej_metadata
                if "spacing" in ij_meta:
                    vz = ij_meta["spacing"]
                    # Usually spacing is in the same unit as X/Y resolution
                    # but ImageJ sometimes uses something else. We assume it's scaled correctly.

            # Fallback: check ImageDescription for IJ metadata
            desc = page.tags.get("ImageDescription")
            if desc:
                val = str(desc.value)
                if "spacing=" in val:
                    try:
                        pts = val.split("\n")
                        for p in pts:
                            p = p.strip()
                            if p.startswith("spacing="):
                                vz = float(p.split("=")[1])
                    except Exception:
                        pass
    except Exception:
        pass
    return vx, vy, vz


def _get_pixel_size_from_zattrs(zattrs: Dict) -> Tuple[Optional[float], ...]:
    """Try to extract pixel resolution from OME-Zarr .zattrs."""
    try:
        multiscales = zattrs.get("multiscales", [])
        if not multiscales:
            return None, None, None

        # Look for datasets/coordinateTransformations
        for ms in multiscales:
            datasets = ms.get("datasets", [])
            for ds in datasets:
                trans = ds.get("coordinateTransformations", [])
                for t in trans:
                    if t.get("type") == "scale":
                        return tuple(t.get("scale"))
    except Exception:
        pass
    return None, None, None


def _extract_calibration_metadata(
    input_file: str, z_root: Optional[Any] = None
) -> Tuple[float, float]:
    """
    Attempt to automatically discover pixel size and anisotropy from file metadata.
    Returns (pixel_size, anisotropy).
    """
    pixel_size = None
    anisotropy = None

    # 1. Try OME-Zarr metadata or "pixel_size" (written by image_to_zarr.py)
    if z_root is not None and hasattr(z_root, "attrs"):
        # Explicit check for "pixel_size" attribute used by image_to_zarr.py
        if "pixel_size" in z_root.attrs:
            ps = z_root.attrs["pixel_size"]
            # Typically [C, Z, Y, X] or [Z, Y, X]
            ps_vals = [float(v) for v in ps]
            if len(ps_vals) == 4:
                vz, vx = ps_vals[1], ps_vals[3]
            elif len(ps_vals) == 3:
                vz, vx = ps_vals[0], ps_vals[2]
            else:
                vz, vx = 1.0, 1.0

            if vz > 0 and vx > 0:
                pixel_size = vx
                anisotropy = vz / vx

                # REFINED HEURISTIC: Handle common unit mismatches (mm vs um vs m)
                # target roughly 0.05 to 5.0 um/pixel
                if pixel_size < 0.1:
                    # Is it Millimeters? (e.g. 0.0001 mm -> 0.1 um)
                    if 0.01 <= (pixel_size * 1000.0) <= 20.0:
                        print(
                            f"DEBUG: Calibration {pixel_size:.8f} looks like Millimeters. Scaling to Micrometers (x1000)."
                        )
                        pixel_size *= 1000.0
                    # Is it Meters? (e.g. 1e-7 m -> 0.1 um)
                    elif 0.01 <= (pixel_size * 1e6) <= 20.0:
                        print(
                            f"DEBUG: Calibration {pixel_size:.8f} looks like Meters. Scaling to Micrometers (x1e6)."
                        )
                        pixel_size *= 1e6

                print(
                    f"Discovered Zarr calibration (pixel_size): pixel_size={pixel_size:.4f}, anisotropy={anisotropy:.4f}"
                )

        if pixel_size is None:
            scales = _get_pixel_size_from_zattrs(dict(z_root.attrs))
            if scales and any(s is not None for s in scales):
                # scales is usually (t, c, z, y, x) or (z, y, x)
                if len(scales) >= 3:
                    # Last two are Y, X. The one before is Z.
                    px_z, px_y, px_x = scales[-3], scales[-2], scales[-1]
                    if px_y and px_x:
                        pixel_size = (px_y + px_x) / 2.0
                        anisotropy = px_z / pixel_size if px_z else 1.0
                        print(
                            f"Discovered OME-Zarr calibration: pixel_size={pixel_size:.4f}, anisotropy={anisotropy:.4f}"
                        )

    # 2. Try TIFF metadata fallback
    if pixel_size is None and input_file.lower().endswith((".tif", ".tiff")):
        vx, vy, vz = _get_pixel_size_from_tiff(input_file)
        if vx and vy:
            pixel_size = (vx + vy) / 2.0
            if vz:
                anisotropy = vz / pixel_size
                print(
                    f"DEBUG: Discovered TIFF calibration: pixel_size={pixel_size:.4f}, anisotropy={anisotropy:.4f}"
                )
            else:
                anisotropy = 1.0  # Default to isotropic if Z-spacing missing
                print(
                    f"DEBUG: Discovered TIFF calibration (XY): pixel_size={pixel_size:.4f}"
                )

    # Final defaults
    if pixel_size is None:
        print(
            "DEBUG: Calibration discovery failed completely. Using defaults (30, 1.0)"
        )
        pixel_size = 30.0
    if anisotropy is None:
        anisotropy = 1.0

    return float(pixel_size), float(anisotropy)


def _upscale_labels_to_level0(da_labels, target_level: int) -> da.Array:
    """Upscale labels to Level 0 resolution using nearest-neighbor (da.repeat)."""
    if target_level <= 0:
        return da_labels

    scale_factor = 2**target_level
    # Upscale spatially (last 2 or 3 dims)
    ndim = da_labels.ndim
    # Usually Z, Y, X (3D) or Y, X (2D)
    for i in range(ndim - (2 if ndim >= 2 else 0), ndim):
        da_labels = da_labels.repeat(scale_factor, axis=i)

    return da_labels


def _get_pyramid_level(z_root: Any, desired_level: int) -> Any:
    """
    Safely get a lower-resolution Zarr array from a multiscale image.
    If the requested level is missing, search for the next closest (smaller) level available.
    """
    # 0. Check if z_root is actually a group/hierarchy and not a single Array or Dask array
    if (
        z_root is None
        or hasattr(z_root, "compute")
        or not (hasattr(z_root, "keys") or hasattr(z_root, "__contains__"))
    ):
        return None

    try:
        # 1. Try exact level
        for path in [str(desired_level), f"/{desired_level}"]:
            if path in z_root:
                return z_root[path]

        # 2. Try to find the next available smaller level (larger index)
        # Scan indices from desired_level+1 upwards (up to 10)
        for i in range(desired_level + 1, 10):
            for path in [str(i), f"/{i}"]:
                if path in z_root:
                    print(
                        f"  Note: Requested Level {desired_level} missing, using Level {i} instead."
                    )
                    return z_root[path]

        # 3. Try to find any available level above Level 0 but below desired (climb back down)
        for i in range(desired_level - 1, 0, -1):
            for path in [str(i), f"/{i}"]:
                if path in z_root:
                    print(
                        f"  Note: Requested Level {desired_level} missing, using Level {i}."
                    )
                    return z_root[path]

    except Exception:
        pass
    return None


def _get_array_stats(
    da_array: Any,
    c_axis: Optional[int] = None,
    c_idx: int = 0,
) -> Tuple[float, float, float, float]:
    """Calculate min, max, and percentiles (p1, p99) for an array using efficient sampling."""
    try:
        # OPTIMIZATION: For huge networked Zarrs, avoid uniform strides which hit too many chunks.
        # Instead, sample random 128x128 blocks if the array is large.

        # The nbytes check is unreliable for Zarr arrays (it often returns 0).
        size = getattr(da_array, "size", 0)
        itemsize = getattr(getattr(da_array, "dtype", None), "itemsize", 2)
        total_nbytes = size * itemsize

        # 1GB threshold check for "huge" sampling
        is_huge = total_nbytes > 1 * 1024**3

        shape = da_array.shape
        ndim = da_array.ndim

        if is_huge:
            print(
                f"  Detected huge array ({total_nbytes / 1e9:.2f} GB). Using very sparse random block sampling..."
            )
            num_samples = 100
            block_size = 128
            samples = []

            # Assuming spatial dims are the last two: (..., Y, X)
            h, w = shape[-2], shape[-1]
            import random

            for _ in range(num_samples):
                y = random.randint(0, max(0, h - block_size))
                x = random.randint(0, max(0, w - block_size))

                # Slice extraction
                effective_h = min(h, block_size)
                effective_w = min(w, block_size)

                # Prepare the slice
                slc = [slice(None)] * ndim

                # Apply channel slicing if provided
                if c_axis is not None and 0 <= c_axis < ndim:
                    slc[c_axis] = c_idx

                # Apply spatial slicing
                slc[-2] = slice(y, y + effective_h)
                slc[-1] = slice(x, x + effective_w)

                # Pick random Z or other spatial dimensions
                remaining_indices = [i for i in range(ndim - 2) if i != c_axis]
                for idx in remaining_indices:
                    slc[idx] = random.randint(0, shape[idx] - 1)

                s = da_array[tuple(slc)]

                if hasattr(s, "compute"):
                    s = s.compute()
                samples.append(np.asanyarray(s).flatten())

            sample = np.concatenate(samples)
        else:
            # Original striding logic for smaller arrays or if nbytes unavailable
            # First, filter to channel if provided
            if c_axis is not None and 0 <= c_axis < ndim:
                slc_c = [slice(None)] * ndim
                slc_c[c_axis] = c_idx
                da_array = da_array[tuple(slc_c)]
                shape = da_array.shape
                ndim = da_array.ndim

            total_pixels = da_array.size
            sample_ratio = max(1, int(total_pixels / 10_000_000))

            if sample_ratio > 1:
                print(f"  Sampling 1/{sample_ratio} pixels for statistics...")
                if ndim == 3:
                    z_stride = 2 if shape[0] > 10 else 1
                    xy_stride = int(math.sqrt(sample_ratio / z_stride))
                    sample = da_array[::z_stride, ::xy_stride, ::xy_stride]
                elif ndim == 4:
                    z_stride = 2 if shape[1] > 10 else 1
                    xy_stride = int(math.sqrt(sample_ratio / z_stride))
                    sample = da_array[:, ::z_stride, ::xy_stride, ::xy_stride]
                else:
                    sample = da_array.flatten()[::sample_ratio]

                if hasattr(sample, "compute"):
                    sample = sample.compute()
            else:
                sample = (
                    da_array.compute() if hasattr(da_array, "compute") else da_array
                )

        # Ensure that if the input is a Zarr-like object (not Dask), and we calculate stats,
        # we convert it to a local numpy array ONCE before calling stats.
        sample = np.asanyarray(sample)

        v_min = float(np.min(sample))
        v_max = float(np.max(sample))
        p1, p99 = np.percentile(sample, [1, 99])

        return v_min, v_max, float(p1), float(p99)
    except Exception as e:
        print(f"Warning: sampled stats failed ({e}). Falling back to simple compute...")
        # Fallback for small arrays

        # Apply channel slicing if provided
        safe_da = da_array
        if c_axis is not None and 0 <= c_axis < getattr(safe_da, "ndim", 0):
            try:
                slc_err = [slice(None)] * safe_da.ndim
                slc_err[c_axis] = c_idx
                safe_da = safe_da[tuple(slc_err)]
            except Exception:
                pass

        # If it's a huge array and we are in the fallback, we MUST use a very sparse stride
        # otherwise we'll definitely hit RAM limits (this is the last resort)
        total_voxels = getattr(safe_da, "size", 0)
        safety_stride = max(1, int(total_voxels / 10_000_000))
        if safety_stride > 1:
            print(
                f"  Note: using safety stride {safety_stride} for fallback statistics."
            )
            # For dask/zarr, try to apply stride before computing
            try:
                if len(safe_da.shape) >= 2:
                    slc_safe = [slice(None, None, safety_stride)] * safe_da.ndim
                    safe_da = safe_da[tuple(slc_safe)]
            except Exception:
                pass

        computed = (
            np.asanyarray(safe_da.compute())
            if hasattr(safe_da, "compute")
            else np.asanyarray(safe_da)
        )
        return (
            float(np.min(computed)),
            float(np.max(computed)),
            float(np.percentile(computed, 1)),
            float(np.percentile(computed, 99)),
        )


@_require_ndimage
def _dilate_mask(mask: np.ndarray, iterations: int) -> np.ndarray:
    """Dilate a binary mask using ndimage.binary_dilation."""
    if iterations <= 0:
        return mask
    # For speed on large masks, use simple structure
    return ndimage.binary_dilation(mask, iterations=iterations)


def _create_optimized_segmentation_mask(
    z_root: Any,
    min_intensity: Optional[Union[float, str]],
    threshold_rel: float = 0.5,
    c_axis: Optional[int] = None,
    c_idx: int = 0,
    skip_if_huge: bool = False,
) -> Tuple[Optional[da.Array], float, float, float]:
    """
    Generate a low-resolution binary mask for block exclusion and calculate image stats.
    Targets Level+4 for stats and Level+3 for the mask to save significant I/O.
    """
    stats_level = 4
    mask_level = 3

    # If z_root is actually an array (already drilled down to '0'), we can't use it to find other levels.
    # We should use the ACTUAL root of the Zarr group if it's available.
    actual_root = z_root
    if hasattr(z_root, "store") and hasattr(z_root, "path"):
        try:
            # If path ends in '/0', '/1', etc., move up one level
            parts = z_root.path.strip("/").split("/")
            if parts[-1].isdigit():
                parent_path = "/".join(parts[:-1])
                actual_root = zarr.open_group(z_root.store, path=parent_path, mode="r")
        except Exception:
            pass

    # Attempt to find low-res levels
    z_stats = _get_pyramid_level(actual_root, stats_level)
    z_mask = _get_pyramid_level(actual_root, mask_level)

    # If no pyramids are found and image is massive, skip mask to prevent minutes of I/O
    if z_mask is None and skip_if_huge:
        print(
            "  Warning: No pyramid found for massive array (>100GB). Skipping spatial mask to save I/O."
        )
        # We still need stats. _get_array_stats is already sparse-optimized.
        v_min, v_max, p1, p99 = _get_array_stats(z_root, c_axis=c_axis, c_idx=c_idx)
        val = p1 + threshold_rel * (p99 - p1) if min_intensity in ("auto", "Auto") else 0.0
        return None, v_min, v_max, val

    # Fallback to level 0 if still needed
    if z_stats is None:
        z_stats = (
            actual_root["0"]
            if (hasattr(actual_root, "__contains__") and "0" in actual_root)
            else z_root
        )

    # Track if z_mask is actually Level 0 to avoid full volume compute later
    is_mask_level0 = False
    if z_mask is None:
        z_mask = (
            actual_root["0"]
            if (hasattr(actual_root, "__contains__") and "0" in actual_root)
            else z_root
        )
        # Check if the resolved z_mask is the same as the original Level 0 (huge one)
        # We check path/shape as a proxy for 'is Level 0'
        is_mask_level0 = getattr(z_mask, "size", 0) == getattr(z_root, "size", 0)

    # Multi-channel slicing logic
    # STOP EAGER SLICING for Zarr objects to avoid loading the whole channel into RAM
    # We pass c_axis/c_idx to _get_array_stats instead.
    if c_axis is not None:
        print(f"  Channel context: index {c_idx} at axis {c_axis}")

    # Get statistics (min/max/percentiles)
    print(f"Calculating image statistics (level {stats_level})...")
    # Pass Zarr object directly to avoid dask auto-rechunking bottleneck on huge files
    v_min, v_max, p1, p99 = _get_array_stats(z_stats, c_axis=c_axis, c_idx=c_idx)

    # Compute binary mask for empty-space early-exit
    grid_mask = None
    val = 0.0
    if min_intensity is not None and min_intensity != "None":
        if min_intensity == "auto" or min_intensity == "Auto":
            # Heuristic: 1% above p1
            val = p1 + 0.01 * (p99 - p1)
        else:
            try:
                val = float(min_intensity)
            except (ValueError, TypeError):
                val = 0.0

        print(f"Creating block-level exclusion mask (threshold={val:.2f})...")
        sys.stdout.flush()

        # Step 1: Create binary mask at low resolution
        size_mask = getattr(z_mask, "size", 0)
        itemsize_mask = getattr(getattr(z_mask, "dtype", None), "itemsize", 2)
        total_nbytes_mask = size_mask * itemsize_mask

        # If Level 0 is used and it is huge (>1GB), we MUST use strided loading
        if is_mask_level0 and total_nbytes_mask > 1 * 1024**3:
            print(
                f"  Note: Volume is huge ({total_nbytes_mask / 1e9:.2f} GB) and Level 0 is used for mask. Forcing strided load."
            )

            # Prepare the slice for strided load
            slc_m = [slice(None)] * z_mask.ndim
            if c_axis is not None and 0 <= c_axis < z_mask.ndim:
                slc_m[c_axis] = c_idx

            # Stride factor: 16 by default, 32 if > 50GB, 64 if > 200GB (roughly)
            stride = 16
            if total_nbytes_mask > 200 * 1024**3:
                stride = 64
            elif total_nbytes_mask > 50 * 1024**3:
                stride = 32

            print(f"  Using stride {stride} for mask calculation.")

            # Apply stride to spatial dimensions (last 3 or 2)
            spatial_start = max(0, z_mask.ndim - 3)
            for i in range(spatial_start, z_mask.ndim):
                if i != c_axis:
                    slc_m[i] = slice(None, None, stride)

            # Zarr slicing with step is efficient (doesn't load everything)
            da_mask_src = da.from_array(z_mask[tuple(slc_m)], chunks="auto")
        else:
            # Standard path: wrap in dask and slice channel lazily
            da_mask_src = da.from_array(z_mask, chunks="auto")
            if c_axis is not None and 0 <= c_axis < z_mask.ndim:
                slc_m = [slice(None)] * z_mask.ndim
                slc_m[c_axis] = c_idx
                da_mask_src = da_mask_src[tuple(slc_m)]

        bin_mask = da_mask_src > val
        grid_mask = bin_mask

    # OPTIMIZATION: Compute the coarse mask now (it's small) to avoid lazy re-compute later.
    if grid_mask is not None:
        try:
            print("  Computing coarse mask...")
            sys.stdout.flush()
            # If it's dask, compute it. If it's already a numpy array (e.g. from strided zarr slice), just take it.
            if hasattr(grid_mask, "compute"):
                grid_mask = grid_mask.compute()

            # Convert to numpy and then immediately back to Dask to "forget" the large Zarr source.
            # This makes the Dask graph for 'segmentation_mask' contain just the data and no references to Level 0.
            grid_mask = np.asanyarray(grid_mask)
            grid_mask = da.from_array(grid_mask, chunks=grid_mask.shape)
        except Exception as e:
            print(f"  Warning: coarse mask compute failed ({e})")
            grid_mask = None

    return grid_mask, v_min, v_max, val


def get_optimal_n_workers(
    use_gpu: bool,
    requested_n_workers: Optional[int],
    blocksize: Tuple[int, ...],
    model_type: str = "cyto3",
    diameter: float = 30.0,
    mem_multiplier: float = 0.0,
    anisotropy: float = 1.0,
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

    # Robustly unpack blocksize: can be (Y, X) for 2D or (Z, Y, X) for 3D
    if len(blocksize) == 3:
        z, y, x = blocksize
    elif len(blocksize) == 2:
        z = 1
        y, x = blocksize
    else:
        # Fallback for unexpected rank: treat as 2D spatial side
        z = 1
        y = x = blocksize[0]

    # Calculate scaling factor
    model_diam = 17.0 if "nuclei" in str(model_type).lower() else 30.0
    is_3d_mode = z > 1
    eff_diameter = float(diameter) if float(diameter) > 0 else 30.0
    scaling_factor = model_diam / eff_diameter

    # Apply padding factor for volume estimation (default tile_overlap is 0.1)
    # 3D padding has a much larger impact on voxel count than 2D.
    padding = 1.25 if is_3d_mode else 1.15
    padded_voxels = (
        (z * padding) * (y * padding) * (x * padding)
        if is_3d_mode
        else (z * y * padding * x * padding)
    )

    # Calculate effective voxel count after internal resizing
    # If using anisotropy, Z will be upscaled significantly or XY downscaled
    # Effective volume expansion = voxels * (scale_factor^2) * (scale_factor * anisotropy_factor)
    # Where anisotropy_factor matches Z resolution to XY.
    if is_3d_mode and anisotropy != 1.0:
        # Cellpose resize logic roughly: new_z = z * anisotropy
        rescaled_voxels = (
            padded_voxels * (scaling_factor**2) * (scaling_factor * anisotropy)
        )
    else:
        rescaled_voxels = padded_voxels * (scaling_factor ** (3 if is_3d_mode else 2))

    print(
        f"Memory estimation: model_diam={model_diam}, diameter={eff_diameter}, "
        f"scaling_factor={scaling_factor:.2f}, anisotropy={anisotropy:.2f}, 3D={is_3d_mode}"
    )

    total_cpus = os.cpu_count() or 1
    eff_requested_workers = (
        requested_n_workers
        if (requested_n_workers and requested_n_workers > 0)
        else 9999
    )

    # RAM Estimation
    # Multiplier: float32 image (4) + flows (6-10x) + grad/probs + u-net intermediates
    if mem_multiplier > 0:
        multiplier = mem_multiplier
    else:
        # Cellpose 3 needs ~6x the volumetric memory for flows and stitching
        multiplier = 6.0 if is_3d_mode else 3.0

    estimated_ram_per_worker = rescaled_voxels * 4 * multiplier

    max_workers_ram = eff_requested_workers
    if psutil is not None:
        try:
            available_ram = psutil.virtual_memory().available
            # Use 80% of available RAM (which is already free)
            usable_ram = available_ram * 0.8
            max_workers_ram = (
                int(usable_ram // estimated_ram_per_worker)
                if estimated_ram_per_worker > 0
                else 1
            )

            print(
                f"System RAM: {psutil.virtual_memory().total / 1024**3:.2f} GB "
                f"(Available: {available_ram / 1024**3:.2f} GB)"
            )
            print(
                f"Estimated RAM per worker for block {blocksize}: {estimated_ram_per_worker / 1024**2:.2f} MB"
            )
        except Exception:
            pass

    if not use_gpu:
        # CPU mode: maximize process parallelism for GIL avoidance
        n_workers = min(eff_requested_workers, total_cpus, max_workers_ram)
        # Use 1 Dask thread per worker, 1 OMP thread per worker
        return max(0, n_workers), 1, 1

    # GPU mode
    try:
        if torch is None or not torch.cuda.is_available():
            return max(0, min(eff_requested_workers, total_cpus, max_workers_ram)), 1, 1

        num_gpus = torch.cuda.device_count()
        gpu_props = torch.cuda.get_device_properties(0)
        total_memory_single_gpu = gpu_props.total_memory

        torch.cuda.empty_cache()
        free_memory = total_memory_single_gpu - torch.cuda.memory_allocated(0)

        # Target 80% usage for large GPUs, 35% for small GPUs (< 5GB)
        # 4GB cards (GRID) have very little head-room for post-processing peak tensors.
        gpu_target_ratio = 0.35 if total_memory_single_gpu < 5 * 1024**3 else 0.8
        usable_memory_per_gpu = min(
            total_memory_single_gpu * gpu_target_ratio, free_memory * 0.9
        )

        # VRAM multiplier: accounts for model weights and activation buffers.
        # U-Net architectures (Cellpose) require significant VRAM for intermediates.
        if mem_multiplier > 0:
            vram_multiplier = mem_multiplier
        else:
            # 3D passes are memory intensive but 45.0 is sufficient for 8GB+ cards.
            if is_3d_mode:
                vram_multiplier = (
                    120.0 if total_memory_single_gpu < 5 * 1024**3 else 45.0
                )
            else:
                vram_multiplier = (
                    40.0 if total_memory_single_gpu < 5 * 1024**3 else 25.0
                )

        # For 3D mode (Standard Cellpose), VRAM usage is governed by slice-wise passes (XY, YZ, XZ).
        # We must fit the largest rescaled plane into VRAM, while the whole block must fit in RAM.
        if is_3d_mode:
            # Reconstruct scaling factors to isolate rescaled plane sizes.
            # Cellpose rescales the volume, then runs 2D models on each axis.
            s = scaling_factor
            a = anisotropy

            # Padded dimensions in pixels
            pz, py, px = z * padding, y * padding, x * padding

            # Rescaled dimensions
            rz = pz * s * a
            ry = py * s
            rx = px * s

            area_xy = ry * rx
            area_yz = rz * ry
            area_xz = rz * rx

            # Area of the three rescaled orthogonal planes (inference phase bottleneck)
            max_plane_pixels = max(area_xy, area_yz, area_xz)
            if max_plane_pixels < 1:
                max_plane_pixels = 1

            # VRAM per rescaled plane (Inference bottleneck)
            vram_inference = max_plane_pixels * 4 * vram_multiplier

            # NEW: Volume VRAM constraint for GPU post-processing (Stitching bottleneck)
            # Full-res volume must fit several times for flow-stitching on GPU.
            # Use 40x for small cards, 10x for large cards.
            vram_vol_mult = 40.0 if total_memory_single_gpu < 5 * 1024**3 else 10.0
            # VRAM usage is dominated by the RESCALED volume which Cellpose processes
            vram_postprocessing = rescaled_voxels * 4 * vram_vol_mult

            # Total estimated VRAM per block. We assume inference and stitching
            # don't peak simultaneously, but we need the larger of the two.
            estimated_vram_usage = max(vram_inference, vram_postprocessing)
        else:
            estimated_vram_usage = rescaled_voxels * 4 * vram_multiplier

        estimated_vram_per_worker = estimated_vram_usage

        workers_per_gpu = int(usable_memory_per_gpu // estimated_vram_per_worker)

        print(
            f"GPU: {gpu_props.name} | Total: {total_memory_single_gpu / 1024**3:.2f} GB | Free: {free_memory / 1024**3:.2f} GB"
        )
        print(
            f"Estimated GPU memory per worker for block {blocksize}: {estimated_vram_per_worker / 1024**2:.2f} MB"
        )

        # PERFORMANCE TUNE: 1 worker per GPU is the official recommendation for stability/speed.
        # This avoids multi-process contention on the same CUDA context.
        optimal_workers_per_gpu = min(1, workers_per_gpu)

        # Determine number of GPU workers
        n_workers_gpu = num_gpus * optimal_workers_per_gpu
        n_workers = min(eff_requested_workers, n_workers_gpu, max_workers_ram)

        # Internal threads: each worker can use multiple cores for pre/post-processing
        # but we set Dask threads to 1 to ensure blocks are processed sequentially per GPU.
        internal_threads = min(16, max(1, total_cpus // max(1, n_workers)))

        return max(0, n_workers), 1, internal_threads

    except Exception as e:
        print(
            f"Error in GPU worker calculation: {e}. Falling back to 0 workers (will trigger retry)."
        )
        return 0, 1, 1


def validate_runtime_requirements() -> Tuple[Any, str]:
    """Import and validate the presence of required Python libraries.

    Ensures that the environment has `cellpose`, `dask`, `zarr`, and
    `tifffile` modules and that `cellpose.contrib.distributed_segmentation`
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

    modules_to_check = [
        "cellpose",
        "dask",
        "distributed",
        "zarr",
        "tifffile",
        "bokeh",
    ]
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
            print("Checking cellpose distributed module...")
            sys.stdout.flush()
            ds_mod = importlib.import_module(
                "cellpose.contrib.distributed_segmentation"
            )
        except Exception as e:
            print(f"Warning: Could not import cellpose distributed components: {e}")
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

    print("Found required Python packages and cellpose distributed helpers:")
    for m, v in info:
        print(f" - {m} {v}")

    # Get cellpose version for compatibility checks
    cellpose_version = None
    for m, v in info:
        if m == "cellpose":
            cellpose_version = v
            break

    return ds_mod, cellpose_version


def parse_blocksize(s: str) -> Union[Tuple[int, ...], str]:
    """Parse a comma-separated blocksize string into a tuple of ints.

    Also handles concatenated format like `128256256` -> (128, 256, 256).
    Returns the string "auto" if input matches.

    Parameters
    ----------
    s : str
        Comma-separated integers, e.g. `"128,256,256"`, or concatenated
        integers like `128256256` (will be split into equal thirds),
        or the literal string "auto".

    Returns
    -------
    Union[Tuple[int, ...], str]
        The parsed blocksize tuple, or "auto".
    """
    s = s.strip().lower()
    if s == "auto":
        return "auto"

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


def get_auto_blocksize(
    shape: Tuple[int, ...],
    is_3d: bool,
    use_gpu: bool,
    multiplier: float = 0,
    c_axis: int = None,
    diameter: float = 30.0,
    anisotropy: float = 1.0,
) -> Tuple[int, ...]:
    """Calculate an optimistically large blocksize based on available hardware.

    This calculation separates RAM limits (for the 3D volume stitching/flows)
    and VRAM limits (for slice-wise GPU processing).
    """
    try:
        import psutil

        available_ram = psutil.virtual_memory().available
    except Exception:
        available_ram = 8 * 1024**3  # Fallback 8GB

    # 1. RAM constraint (Total Volume)
    # Target 70% of available RAM for the block (it's the only worker on its GPU usually)
    ram_limit = available_ram * 0.7
    # For host RAM, we mainly care about the input + results. 6-8 copies is enough.
    ram_multiplier = multiplier if multiplier > 0 else (6.0 if is_3d else 3.0)
    target_voxels_ram = ram_limit / (4 * ram_multiplier)

    # 2. VRAM constraint (Individual Plane + 3D Post-processing)
    # Target usage: be very conservative with small GPUs
    vram_total = 0
    if use_gpu and torch is not None and torch.cuda.is_available():
        vram_total = torch.cuda.get_device_properties(0).total_memory
        # 4GB cards need a very safe margin (35% usage) to avoid fragmentation OOMs
        ratio = 0.35 if vram_total < 5 * 1024**3 else 0.85
        vram_limit = vram_total * ratio
    else:
        vram_limit = available_ram * 0.15  # Fallback

    # 40-120x is a range for 3D activation buffers.
    if is_3d:
        vram_multiplier = (
            multiplier
            if multiplier > 0
            else (120.0 if vram_total < 5 * 1024**3 else 40.0)
        )
    else:
        vram_multiplier = (
            multiplier
            if multiplier > 0
            else (30.0 if vram_total < 5 * 1024**3 else 20.0)
        )

    target_pixels_vram = vram_limit / (4 * vram_multiplier)

    # Resolve spatial indexes to correctly identify Z vs XY
    spatial_indices = [i for i in range(len(shape)) if i != c_axis]

    # Calculate scale factor for plane rescaling
    model_diam = 30.0  # Standard cyto

    # SANITY CHECK: prevent absurdly small scale factors or huge min_dims from extreme diameters
    # diameter=95000 leads to scale=0.0003, which makes side_vram_plane huge.
    # We clamp the diameter used for blocksize logic to a reasonable range.
    calc_diameter = max(1.0, min(diameter, 500.0)) if diameter > 0 else 30.0
    scale = model_diam / calc_diameter

    if is_3d:
        # Determine Z-axis length (first spatial index)
        z_len = shape[spatial_indices[0]] if len(spatial_indices) >= 3 else 1

        # Optimization: use one block for Z if it's small enough (avoid small remainders like 3 slices)
        if z_len <= 128:
            z_target = z_len
        else:
            z_target = min(64, z_len) if c_axis != 0 else 64

        # Account for anisotropy in VRAM estimation. 3D mode runs XY, YZ, XZ planes.
        # rescaled_plane_YZ = (z_target * scale * anisotropy) * (side * scale)
        # rescaled_plane_XY = (side * scale) * (side * scale)
        # We must limit the largest rescaled plane to target_pixels_vram.

        # Max dimension scale factor across all axes
        eff_anisotropy = max(1.0, anisotropy)
        side_vram_plane = int(math.sqrt(target_pixels_vram / eff_anisotropy) / scale)

        # NEW for 3D: Volume VRAM constraint for GPU post-processing
        # Full-res volume must fit several times for flow-stitching on GPU.
        # multiplier of 40-60x for 4GB cards to ensure the dynamics step fits.
        vram_vol_multiplier = 60.0 if vram_total < 5 * 1024**3 else 10.0
        target_voxels_vram_vol = vram_limit / (4 * vram_vol_multiplier)
        side_vram_vol = int(math.sqrt(target_voxels_vram_vol / z_target))

        # volume_voxels = (z * side * side) <= target_voxels_ram
        side_ram = int(math.sqrt(target_voxels_ram / z_target))

        spatial_side = min(side_vram_plane, side_vram_vol, side_ram)
    else:
        # For 2D
        spatial_side = int(math.sqrt(target_pixels_vram) / scale)
        # Match against total RAM volume too
        spatial_side = min(spatial_side, int(math.sqrt(target_voxels_ram)))

    min_dim = int(round(3.0 * (calc_diameter if calc_diameter > 1 else 30.0)))
    spatial_side = max(min_dim, min(4096 if is_3d else 8192, spatial_side))

    # Hard cap for small cards to avoid fragmentation OOM
    if vram_total > 0 and vram_total < 5 * 1024**3:
        spatial_side = min(spatial_side, 512)

    block3d = (
        [z_target, spatial_side, spatial_side]
        if is_3d
        else [spatial_side, spatial_side]
    )

    # Rank alignment based on shape and channel axis
    # We want to keep the channel axis at 1 (one channel at a time)
    # and map our 3D/2D block to the spatial dimensions.
    final_block = list(shape)

    # Map block3d dims (Z, Y, X) or (Y, X) to spatial indices in reverse (right to left)
    target_spatial_rank = len(block3d)
    for i, idx in enumerate(reversed(spatial_indices)):
        if i < target_spatial_rank:
            # Match Z to Z, Y to Y, X to X regardless of exact dimension counts
            final_block[idx] = block3d[target_spatial_rank - 1 - i]
        else:
            # If spatial dims > 3, keep original for the rest
            pass

    # Ensure channel axis is 1 if it exists
    if c_axis is not None:
        final_block[c_axis] = 1

    return tuple(min(b, s) for b, s in zip(final_block, shape))


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
    global zarr
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

                def _coerce(sel, mode="expand"):
                    if isinstance(sel, (list, tuple)):
                        return tuple(_coerce(s, mode) for s in sel)
                    if isinstance(sel, slice):
                        start = (
                            int(math.floor(float(sel.start)))
                            if sel.start is not None
                            else None
                        )
                        stop = (
                            int(math.ceil(float(sel.stop)))
                            if (sel.stop is not None and mode == "expand")
                            else (
                                int(math.floor(float(sel.stop)))
                                if sel.stop is not None
                                else None
                            )
                        )
                        return slice(start, stop, sel.step)
                    if hasattr(sel, "__int__") or isinstance(sel, (int, float, str)):
                        return int(float(sel))
                    return sel

                # Find the Array class to patch - robust for Zarr 2 and Zarr 3
                Array = getattr(zarr, "Array", None)
                if Array is None:
                    # Fallback for some Zarr 2 environments
                    Array = getattr(_zca, "Array", None)
                if Array is None:
                    # Direct check on zarr.core if available
                    try:
                        import zarr.core

                        Array = getattr(zarr.core, "Array", None)
                    except Exception:
                        pass

                if Array is not None:
                    _orig_get = Array.__getitem__
                    _orig_set = Array.__setitem__

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
                                            new_sel_list.append(
                                                _coerce(s, mode="nearest")
                                            )
                                    return _orig_set(self, tuple(new_sel_list), value)
                        except Exception:
                            pass
                        return _orig_set(
                            self, _coerce(selection, mode="nearest"), value
                        )

                    Array.__getitem__ = _getitem_w
                    Array.__setitem__ = _setitem_w
                    applied = True
                else:
                    # Still apply zarr.open patch even if Array indexing patch failed
                    applied = True
            except Exception:
                pass
            return applied
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
    mask: Optional[np.ndarray] = None,
    global_limits: Optional[Tuple[float, float]] = None,
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
    mask : numpy.ndarray, optional
        Foreground mask to limit processing to relevant regions.

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

            # Resolve min_intensity value to a number if it was 'auto'
            resolved_min_i = "None"
            if args.min_intensity is not None and args.min_intensity != "None":
                if (
                    isinstance(args.min_intensity, str)
                    and args.min_intensity.lower() == "auto"
                ):
                    # We need to use the calculated value from determine_grid_mask
                    # If it was called, it should have updated a local variable or we can re-derive it.
                    # Since global_norm/p1/p99 are calculated in main, we should pass it.
                    if "auto_min_intensity_val" in globals():
                        resolved_min_i = str(globals()["auto_min_intensity_val"])
                    else:
                        resolved_min_i = "None"
                else:
                    resolved_min_i = str(args.min_intensity)

            preload_content = (
                wp_source
                + "\n\n"
                + f"dask_setup = setup_worker({thread_limit}, min_intensity={resolved_min_i})\n"
            )
        except Exception:
            # Last-resort fallback inline minimal script
            preload_content = f"""
import os

def dask_setup(worker):
    nthreads = {thread_limit}
    min_i = {resolved_min_i if "resolved_min_i" in locals() else "None"}
    os.environ['OMP_NUM_THREADS'] = str(nthreads)
    os.environ['MKL_NUM_THREADS'] = str(nthreads)
    os.environ['OPENBLAS_NUM_THREADS'] = str(nthreads)
    os.environ['NUMEXPR_NUM_THREADS'] = str(nthreads)
    # Reduce fragmentation and improve stability for small GPUs
    os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"
    print(f"Worker {{getattr(worker, 'id', '<unknown>')}}: Thread limit enforced to {{nthreads}} threads")
    if min_i is not None:
        print(f"Worker {{getattr(worker, 'id', '<unknown>')}}: Skipping intensity check (inline fallback does not support it)")
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
        # Create preprocessing_steps if we have multiple channels or Gaussian/Median/Norm blur
        preprocessing_steps = []

        # Define common preprocessing logic once to ensure it's applied correctly to all channels
        def apply_manual_preprocessing(img):
            if img is None:
                return None
            res = img
            if args.gauss > 0 and ndimage is not None:
                s_xy = args.gauss
                s_z = args.gauss / args.anisotropy if args.anisotropy > 0 else 1.0
                if res.ndim == 3:
                    res = ndimage.gaussian_filter(res, sigma=(s_z, s_xy, s_xy))
                else:
                    res = ndimage.gaussian_filter(res, sigma=s_xy)

            if args.median > 0 and ndimage is not None:
                m_xy = args.median
                m_z = max(1, int(round(args.median / args.anisotropy)))
                if res.ndim == 3:
                    res = ndimage.median_filter(res, size=(m_z, m_xy, m_xy))
                else:
                    res = ndimage.median_filter(res, size=m_xy)

            if args.global_norm and global_limits is not None:
                low, high = global_limits
                res = res.astype(np.float32)
                res = np.clip(res, low, high)
                res = (res - low) / (high - low) * 255.0  # Cellpose likes 0-255 scale
                res = res.astype(np.float32)

            return res

        # 1. Add general preprocessing (Blur, Median, Norm)
        if args.gauss > 0 or args.median > 0 or args.global_norm:
            if args.gauss > 0:
                print(f"Applying Gaussian blur (sigma={args.gauss:.2f})")
            if args.median > 0:
                print(f"Applying Median filter (size={args.median})")
            if args.global_norm and global_limits:
                print(
                    f"Applying Global Normalization ({global_limits[0]:.2f} - {global_limits[1]:.2f})"
                )
                # Ensure Cellpose doesn't do its own normalization if we just did it globally
                eval_kwargs["normalize"] = False

            def pre_all(image, crop):
                return apply_manual_preprocessing(image)

            preprocessing_steps.append((pre_all, {}))

        # 2. Add Multi-channel stacking (with preprocessing applied to each channel)
        if channel_zarrs and len(channel_zarrs) > 1:

            def stack_channels(image, crop):
                """Stack additional channels onto the base channel.
                Following cellpose documentation pattern for multi-channel segmentation.
                Each channel is preprocessed before stacking.
                """
                # image has already been preprocessed by 'pre_all' if it exists.
                # However, Cellpose's distributed_eval architecture for preprocessing_steps
                # is slightly different for multi-channels. We must ensure we apply our
                # manual preprocessing to ALL channels here.
                channels_to_stack = [apply_manual_preprocessing(image)]
                for ch_idx in range(1, len(channel_zarrs)):
                    ch_img = apply_manual_preprocessing(channel_zarrs[ch_idx][crop])
                    channels_to_stack.append(ch_img)

                # Determine where to insert the channel axis.
                # If image is 3D (Z, Y, X), we want (Z, C, Y, X) -> axis 1
                if image.ndim == 3:
                    return np.stack(channels_to_stack, axis=1)
                else:
                    # For 2D model, cellpose expects (C, Y, X)
                    return np.stack(channels_to_stack, axis=0)

            preprocessing_steps.append((stack_channels, {}))
            print(f"Added preprocessing_steps to stack {len(channel_zarrs)} channels")
            sys.stdout.flush()

        if not preprocessing_steps:
            preprocessing_steps = None

        # Proactively filter eval_kwargs to match CellposeModel.eval signature
        # to avoid TypeErrors from unknown parameters (e.g. CLI flags vs API keys).
        try:
            import inspect

            from cellpose.models import CellposeModel

            sig_eval = inspect.signature(CellposeModel.eval)
            valid_keys = set(sig_eval.parameters.keys())
            # Add known common parameters that might not be in the signature but are used
            valid_keys.update(
                [
                    "normalize",
                    "flow_threshold",
                    "cellprob_threshold",
                    "stitch_threshold",
                    "min_size",
                    "compute_masks",
                ]
            )
            # Ensure manually passed flags are always considered valid
            if hasattr(args, "additional_eval_kwargs"):
                valid_keys.update(args.additional_eval_kwargs.keys())

            filtered_eval = {k: v for k, v in eval_kwargs.items() if k in valid_keys}
            if len(filtered_eval) < len(eval_kwargs):
                dropped = set(eval_kwargs.keys()) - set(filtered_eval.keys())
                print(
                    f"Filtering eval_kwargs: dropped unsupported parameters {dropped}"
                )
                eval_kwargs = filtered_eval
        except Exception as _fe:
            print(f"Warning: could not filter eval_kwargs: {_fe}")

        # Let cellpose create its own cluster with our preload script
        kwargs = dict(
            blocksize=blocksize,
            write_path=write_zarr,
            model_kwargs=model_kwargs,
            eval_kwargs=eval_kwargs,
            cluster_kwargs=cluster_kwargs,
            temporary_directory=args.temporary_directory,
        )

        if mask is not None:
            kwargs["mask"] = mask
            # Efficiency diagnostic: count expected active blocks
            try:
                # Project mask onto block grid
                grid_mask = np.ones(
                    tuple(
                        math.ceil(input_zarr.shape[i] / blocksize[i])
                        for i in range(len(blocksize))
                    ),
                    dtype=bool,
                )
                if hasattr(mask, "compute"):
                    # Low-res dask mask
                    from scipy import ndimage

                    # Simple zoom/subsample logic to grid
                    m_comp = mask.compute()
                    factors = [
                        grid_mask.shape[i] / m_comp.shape[i]
                        for i in range(len(grid_mask.shape))
                    ]
                    grid_mask = ndimage.zoom(m_comp, factors, order=0)
                else:
                    # Regular numpy mask
                    pass

                active_count = np.sum(grid_mask)
                total_count = math.prod(grid_mask.shape)
                print(
                    f"Applying segmentation mask: processing {active_count}/{total_count} blocks ({(100.0 * active_count / total_count):.1f}%)"
                )
            except Exception:
                print("Applying segmentation mask (skipping blocks with no signal)...")

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
        num_blocks = 0
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

        # Diagnostic versions
        try:
            if dask and distributed:
                print(
                    f"Environment info: dask={dask.__version__}, distributed={distributed.__version__}"
                )
        except Exception:
            pass

        # Prefer creating a LocalCluster and Client here so we can explicitly
        # apply worker patches via `client.run()` and pass `client`/`cluster`
        # into `ds.distributed_eval` when supported. Fall back to letting
        # cellpose create its own cluster if cluster construction fails.
        client = None
        created = None
        try:
            cluster_kwargs_local = dict(cluster_kwargs) if cluster_kwargs else {}

            # PORT SAFETY: If port 8787 is in use, it might be a zombie process
            # or a previous crashed run. Dask usually increments itself (8788, etc)
            # but sometimes users get 404s if it binds to a stale address.
            # Here we check for 8787 and explicitly try to avoid it if it's busy.
            if "dashboard_address" not in cluster_kwargs_local:
                target_port = 8787
                # We try several ports if 8787 is busy to find a 'fresh' one
                while is_port_in_use(target_port) and target_port < 8800:
                    target_port += 1

                # Explicitly bind to 127.0.0.1 to avoid binding to external IPs
                # that might be blocked by firewalls or return 404 on loopback.
                cluster_kwargs_local["dashboard_address"] = f"127.0.0.1:{target_port}"
                print(f"Targeting dashboard address: 127.0.0.1:{target_port}")

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
                print(
                    "Warning: LocalCluster initialization with full kwargs failed, retrying with subset..."
                )
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
                # Check for bokeh (dashboard dependency) / diagnostics
                try:
                    import bokeh

                    print(f"Bokeh version (dashboard dep): {bokeh.__version__}")
                except ImportError:
                    print(
                        "\n[WARNING] 'bokeh' package not found. Dask dashboard will be DISABLED."
                    )
                    print("To enable, run: pip install bokeh\n")

                # Print Dask dashboard address for visibility (helps when launched from Fiji)
                try:
                    dashboard = getattr(created, "dashboard_link", None) or getattr(
                        client, "dashboard_link", None
                    )

                    # More thorough fallback resolution
                    if not dashboard or "0.0.0.0" in dashboard:
                        try:
                            # Try to extract from scheduler info
                            info = created.scheduler_info()
                            services = info.get("services", {})
                            if "dashboard" in services:
                                port = services["dashboard"]
                                # Primary: 127.0.0.1 (Safest)
                                dashboard = f"http://127.0.0.1:{port}/status"
                        except Exception:
                            pass

                    # Final sanity check on dashboard string
                    if dashboard:
                        # Ensure we use 127.0.0.1 rather than 0.0.0.0 or localhost
                        dashboard = dashboard.replace("0.0.0.0", "127.0.0.1")
                        dashboard = dashboard.replace("localhost", "127.0.0.1")

                        if "/status" not in dashboard:
                            dashboard = dashboard.rstrip("/") + "/status"

                        dashboard_alt = dashboard.replace("127.0.0.1", "localhost")

                        print(f"\nDask dashboard: {dashboard}")
                        print(f"Alternative URL: {dashboard_alt}\n")
                        sys.stdout.flush()

                        try:
                            # Use open_dashboard (from argparse action) instead of show_dashboard
                            if webbrowser is not None and getattr(
                                args, "open_dashboard", False
                            ):
                                # Wait for the dashboard port to actually open.
                                # This prevents 'Connection Refused' if the browser is too fast.
                                print("Waiting for dashboard to stabilize...")
                                try:
                                    # Parse port
                                    dash_port = int(
                                        dashboard.split(":")[-1].split("/")[0]
                                    )
                                    for i in range(20):
                                        if is_port_in_use(dash_port):
                                            print(
                                                f"Dashboard port {dash_port} is active."
                                            )
                                            break
                                        time.sleep(0.5)
                                except Exception:
                                    time.sleep(2)

                                print(f"Opening dashboard in browser: {dashboard}")
                                webbrowser.open(dashboard)
                        except Exception as browser_err:
                            print(f"Warning: Could not open browser: {browser_err}")
                except Exception as dash_err:
                    print(f"Warning: Could not resolve dashboard address: {dash_err}")

                # Register worker plugins and setup logging immediately
                try:

                    class _WorkerSetupPlugin:
                        def setup(self, worker):
                            _patch_worker_all()
                            if log_file:
                                _set_worker_logging(log_file)

                    try:
                        client.register_plugin(
                            _WorkerSetupPlugin(), name="worker_setup"
                        )
                        print("Registered worker setup plugin (patches + logging)")
                    except Exception:
                        pass
                except Exception:
                    pass

                try:
                    # Backward compatibility / aggressive activation
                    client.run(_patch_worker_all)
                    if log_file:
                        client.run(_set_worker_logging, log_file)
                        try:
                            client.run_on_scheduler(_set_worker_logging, log_file)
                        except Exception:
                            pass
                except Exception as e:
                    print(f"Warning: Worker setup failed: {e}")

                # Determine whether to pass client/cluster and other advanced params to distributed_eval
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

                # Optional foreground mask support
                if mask is not None and "mask" in sig.parameters:
                    kwargs["mask"] = mask
                    print(
                        "Passing foreground mask to distributed_eval for efficient processing"
                    )

                # Optional preprocessing_steps support
                if preprocessing_steps and "preprocessing_steps" in sig.parameters:
                    kwargs["preprocessing_steps"] = preprocessing_steps
                    print(
                        f"Passing preprocessing_steps to distributed_eval to stack {len(channel_zarrs)} channels"
                    )
                elif preprocessing_steps:
                    # If not supported, we might have a problem if it's multi-channel,
                    # but we keep it in kwargs and let distributed_eval try to use it
                    # or fail gracefully later.
                    pass

                # If distributed_eval supports 'overlap', pass margin to help avoid holes between blocks.
                # We use a tuple to specify different overlaps for Z and XY.
                # This is critical because 2*diameter overlap in Z would trim away the whole stack
                # if the stack is thinner than the overlap (e.g. 67 planes vs 278px overlap).
                if "overlap" in sig.parameters:
                    diam = eval_kwargs.get("diameter", 30)
                    dist_overlap_xy = int(math.ceil(float(diam) * 1.5))

                    # For Z, we only need enough overlap to stitch cells (~15-30 pixels).
                    # For 2D images, this doesn't apply.
                    dist_overlap_z = min(30, dist_overlap_xy)

                    # Create the overlap parameter based on image dimensionality
                    if input_zarr.ndim == 3:
                        # (Z, Y, X)
                        dist_z = dist_overlap_z
                        dist_xy = dist_overlap_xy

                        # Safety: overlap must be < blocksize in all dims.
                        # Dask map_overlap requires depth < chunk_size.
                        if blocksize is not None and len(blocksize) == 3:
                            bz, by, bx = blocksize
                            dist_z = min(dist_z, bz - 2) if bz > 2 else 0
                            dist_xy = (
                                min(dist_xy, by - 2, bx - 2)
                                if (by > 2 and bx > 2)
                                else 0
                            )

                        dist_overlap = (dist_z, dist_xy, dist_xy)
                    else:
                        # (Y, X) or other
                        dist_xy = dist_overlap_xy
                        if blocksize is not None and len(blocksize) >= 2:
                            by, bx = blocksize[-2], blocksize[-1]
                            dist_xy = (
                                min(dist_xy, by - 2, bx - 2)
                                if (by > 2 and bx > 2)
                                else 0
                            )
                        dist_overlap = dist_xy  # Integer is fine for 2D

                    kwargs["overlap"] = dist_overlap
                    print(
                        f"Detected 'overlap' support in distributed_eval: using {dist_overlap} pixels"
                    )

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

        # If the error is about 'overlap', retry without it
        if "unexpected keyword argument 'overlap'" in msg and "overlap" in kwargs:
            print("Retrying distributed_eval without 'overlap' argument...")
            sys.stdout.flush()
            new_kwargs = dict(kwargs)
            new_kwargs.pop("overlap", None)
            try:
                result = ds.distributed_eval(input_zarr, **new_kwargs)
                return result
            except TypeError as e2:
                # If it fails again, continue to other handlers with the NEW error
                e = e2
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
    """Command-line entry point for the Cellpose Distributed helper.

    Parses CLI arguments, prepares an input Zarr (from a TIFF or a
    directory of TIFFs), applies compatibility patches (`zarr.open`
    and `Array.__getitem__`), and runs distributed evaluation to
    produce a stitched labeled Zarr and optionally a TIFF output.

    Returns
    -------
    None
    """
    # Standard Dask configuration for memory-safe execution on large volumes
    if dask is not None:
        dask.config.set(
            {
                "distributed.worker.memory.target": 0.85,
                "distributed.worker.memory.spill": 0.90,
                "distributed.worker.memory.pause": 0.95,
                "distributed.worker.memory.terminate": 0.98,
                "array.slicing.split_large_chunks": True,
            }
        )

    parser = argparse.ArgumentParser(description="Cellpose Distributed CLI helper")
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
        "--diameter", default=None, type=float, help="Diameter for evaluation"
    )
    parser.add_argument(
        "--chan",
        default=0,
        type=int,
        help="Channel index to use (1-based, 0 is auto/first)",
    )
    parser.add_argument(
        "--chan2", default=-0, type=int, help="Second channel index or 0 (None)"
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
        "--min_intensity",
        default=None,
        help="Minimum intensity threshold for a block to be processed. Blocks with max(intensity) < min_intensity are skipped. 'auto' calculates based on image background.",
    )
    parser.add_argument(
        "--anisotropy",
        default=None,
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
        "--bsize",
        default=None,
        type=int,
        help="Block size for Cellpose internal tiling (default: 224 for v3, 256 for v4). Set larger than tile size to disable tiling.",
    )
    parser.add_argument(
        "--tile_overlap",
        default=None,
        type=float,
        help="Overlap for Cellpose internal tiling (default: 0.1)",
    )
    parser.add_argument(
        "--batch_size",
        default=1,
        type=int,
        help="Batch size for each worker (default: 1, to avoid Cellpose 3 tiling bugs)",
    )
    parser.add_argument(
        "--mem_multiplier",
        default=0.0,
        type=float,
        help="Custom memory multiplier for auto-blocksize. 0 uses defaults (e.g. 40 for 3D VRAM).",
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
        "--no_norm",
        action="store_true",
        default=False,
        help="If set, do not normalize the image before running Cellpose",
    )
    parser.add_argument(
        "--gauss",
        default=0.0,
        type=float,
        help="Sigma for Gaussian blur preprocessing (0 to disable)",
    )
    parser.add_argument(
        "--median",
        default=0,
        type=int,
        help="Size for Median filter preprocessing (0 to disable)",
    )
    parser.add_argument(
        "--global_norm",
        action="store_true",
        default=False,
        help="If set, use global image statistics for normalization instead of per-block normalization",
    )
    parser.add_argument(
        "--log_dir",
        default=None,
        help="Directory where the log file should be saved",
    )
    args, unknown_args = parser.parse_known_args()

    # Parse unknown args as potential eval_kwargs (e.g. --do_3D, --flow3D_smooth=3)
    args.additional_eval_kwargs = {}
    if unknown_args:
        i = 0
        while i < len(unknown_args):
            arg = unknown_args[i]
            if arg.startswith("--"):
                key = arg[2:]
                # Check if it's key=value or just a flag
                if "=" in key:
                    key, val = key.split("=", 1)
                    # Try to parse as float/int
                    try:
                        if "." in val:
                            args.additional_eval_kwargs[key] = float(val)
                        else:
                            args.additional_eval_kwargs[key] = int(val)
                    except ValueError:
                        args.additional_eval_kwargs[key] = val
                else:
                    # Look ahead for value
                    if i + 1 < len(unknown_args) and not unknown_args[i + 1].startswith(
                        "-"
                    ):
                        val = unknown_args[i + 1]
                        try:
                            if "." in val:
                                args.additional_eval_kwargs[key] = float(val)
                            else:
                                args.additional_eval_kwargs[key] = int(val)
                        except ValueError:
                            args.additional_eval_kwargs[key] = val
                        i += 1
                    else:
                        # Assume it's a boolean flag set to True
                        args.additional_eval_kwargs[key] = True
            i += 1
        if args.additional_eval_kwargs:
            print(f"Parsed additional eval kwargs: {args.additional_eval_kwargs}")

    # Parse min_intensity
    if args.min_intensity is not None and str(args.min_intensity).lower() == "auto":
        args.min_intensity = "auto"
    elif args.min_intensity is not None:
        try:
            args.min_intensity = float(args.min_intensity)
        except ValueError:
            print(
                f"Warning: could not parse min_intensity '{args.min_intensity}' as float. Disabling."
            )
            args.min_intensity = None

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

    # Choose a safe temporary directory if none provided. Prefer the results
    # output directory if available and writable, otherwise fall back to Fiji
    # installation or user home. Ensure the chosen directory is writable
    # and create a per-user hidden subfolder.
    def _choose_safe_tempdir(provided):
        if provided:
            return provided

        # Determine potential output directory to use as first candidate
        output_candidate = args.output_dir
        if not output_candidate and args.output_tif:
            try:
                output_candidate = os.path.dirname(os.path.abspath(args.output_tif))
            except Exception:
                pass

        candidates = [
            output_candidate,
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

            _update_log_handlers()

            # We don't add a separate FileHandler to the logger because the logger
            # already writes to sys.stderr (via StreamHandler), which we just redirected.
            # Adding another handler would result in duplicate entries.
            print(f"Persistent log file created at: {log_file}")
            sys.stdout.flush()

        except Exception as e:
            print(f"Warning: Could not setup persistent logging: {e}")
            sys.stdout.flush()
            log_file = None
            log_handle = None

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
    was_subsetted = False

    if args.input_file:
        print(f"Opening input file: {args.input_file}")
        sys.stdout.flush()

        # Check if the input is a Zarr directory
        # Heuristic: is a directory and either contains zarr metadata or ends in .zarr
        is_zarr_dir = os.path.isdir(args.input_file) and (
            os.path.exists(os.path.join(args.input_file, ".zarray"))
            or os.path.exists(os.path.join(args.input_file, ".zgroup"))
            or os.path.exists(os.path.join(args.input_file, "zarr.json"))
            or args.input_file.lower().endswith(".zarr")
        )

        z_root = None
        if is_zarr_dir:
            print(f"Detected Zarr input: {args.input_file}")
            try:
                # Open using zarr API directly
                im = zarr.open(args.input_file, mode="r")
                z_root = im

                # Robustly handle OME-Zarr levels or Groups that wrap an Array
                # Check for OME-Zarr multiscales metadata first
                if (
                    not hasattr(im, "dtype")
                    and hasattr(im, "attrs")
                    and "multiscales" in im.attrs
                ):
                    try:
                        # Extract the first multiscale dataset (Level 0)
                        level0_path = im.attrs["multiscales"][0]["datasets"][0]["path"]
                        print(
                            f"  OME-Zarr detected. Opening Level 0 at '{level0_path}'."
                        )
                        im = im[level0_path]
                    except Exception:
                        pass

                # If still a group, drill down into '0' levels (legacy OME-Zarr)
                while not hasattr(im, "dtype") and hasattr(im, "__getitem__"):
                    try:
                        if "0" in im:
                            im = im["0"]
                        else:
                            break
                    except Exception:
                        break

                # Handle Zarr V3 Array fallback (e.g. Zarr V2 engine interpreting a V3 chunk "c" dir as a Group)
                if not hasattr(im, "dtype") and hasattr(im, "keys"):
                    try:
                        if "c" in im.keys():
                            print(
                                "  Detected Zarr V3 array structure (contains 'c' chunk directory)..."
                            )
                            # If Zarr couldn't parse it as an array (returns Group), pass it to Dask directly.
                            # The user identified that `im["c"]` contains the raw chunk items.
                            # We will try passing the full path natively to Dask, or fallback to the chunk data itself.
                            # Dask or other loaders might accept the raw 'c' folder for reconstruction.
                            try:
                                if da is not None:
                                    d_test = da.from_zarr(args.input_file)
                                    if hasattr(d_test, "shape"):
                                        im = d_test
                                        print(
                                            "  Dask successfully loaded the Zarr V3 array directly."
                                        )
                            except Exception:
                                print(
                                    "  Dask direct load failed, falling back to 'c' chunk subgroup..."
                                )
                                im = im["c"]
                    except Exception:
                        pass

                # Fallback: if it's still a group, try to find ANY array inside it
                # (e.g. if channels are stored as separate arrays)
                if not hasattr(im, "dtype") and hasattr(im, "keys"):
                    try:
                        child_arrays = [k for k in im.keys() if hasattr(im[k], "dtype")]
                        if child_arrays:
                            if len(child_arrays) > 1:
                                print(
                                    f"  Group contains multiple arrays: {child_arrays}"
                                )
                            im = im[child_arrays[0]]
                    except Exception:
                        pass

                # Ultimate ultimate fallback: Try letting Dask sort out the root path
                # (fixes zarr-python V3 vs V2 API incompatibilities)
                if not hasattr(im, "dtype") and not hasattr(im, "shape"):
                    try:
                        if da is not None:
                            d_test = da.from_zarr(args.input_file)
                            if hasattr(d_test, "shape"):
                                im = d_test
                                print(
                                    "  Dask mapped the root path to a valid array structure."
                                )
                    except Exception:
                        pass

                is_zarr = hasattr(im, "dtype") or hasattr(im, "shape")
                if not is_zarr:
                    print(
                        f"Warning: {args.input_file} is a Zarr group but no data array was found at the root or in level '0'."
                    )
            except Exception as e:
                print(
                    f"Failed to open as Zarr ({e}), falling back to TIFF if possible..."
                )
                is_zarr = False
        else:
            is_zarr = False

        if not is_zarr:
            print(f"Loading TIFF file: {args.input_file}")
            # Ensure we don't try to open a directory with tifffile
            if os.path.isdir(args.input_file):
                raise ValueError(
                    f"Path {args.input_file} is a directory but was not recognized as a valid Zarr volume."
                )
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

        # Metadata discovery: pixel size and anisotropy
        # Pass the root group (which contains .zattrs) if available, otherwise the array
        discovered_px, discovered_anisotropy = _extract_calibration_metadata(
            args.input_file, z_root if z_root is not None else im
        )
        if args.diameter is None or args.diameter <= 0:
            # If diameter is missing or 0, we can't do much except use a default.
            # Usually 30 pixels is the Cellpose default.
            args.diameter = 30.0
            print(f"DEBUG: No diameter provided. Using default 30.0 pixels.")
        else:
            # If we found a pixel size, assume the user-provided diameter is in the SAME units (um).
            # We convert it to pixels for Cellpose.
            if discovered_px and discovered_px > 0:
                diameter_um = args.diameter
                args.diameter = diameter_um / discovered_px
                print(
                    f"DEBUG: Scaled diameter from {diameter_um:.4f} um to {args.diameter:.4f} pixels (using pixel_size={discovered_px:.4f})"
                )

                # SANITY CHECK: if diameter > 1000 pixels, it's almost certainly a unit error
                # in the metadata (e.g. mm or meters instead of um).
                if args.diameter > 1000:
                    print(
                        f"!!! WARNING: Diameter ({args.diameter:.1f} px) is extremely large."
                    )
                    print(
                        f"!!! This usually means the file metadata units are wrong (e.g. millimeters vs micrometers)."
                    )
                    print(
                        f"!!! Cellpose works best with diameters around 30 pixels. Performance will be very poor."
                    )
            else:
                print(
                    f"DEBUG: Using user-supplied diameter as pixels: {args.diameter:.4f} (no calibration found)"
                )

        if args.anisotropy is None or args.anisotropy <= 0:
            args.anisotropy = (
                discovered_anisotropy
                if (discovered_anisotropy and discovered_anisotropy > 0)
                else 1.0
            )
            print(
                f"DEBUG: Auto-selected anisotropy based on metadata: {args.anisotropy:.4f}"
            )
        else:
            print(f"DEBUG: Using user-supplied anisotropy: {args.anisotropy:.4f}")
        sys.stdout.flush()

        # Detect multi-channel input and prepare for preprocessing_steps approach.
        # Prefer explicit 4D layout (Z, C, Y, X) with c_axis=1, but also try to
        # auto-detect a small channel axis in multi-dimensional inputs.
        c_axis_pos = None
        if im.ndim >= 4:
            # For 4D (Z, C, Y, X) or (C, Z, Y, X), etc.
            # We look for the smallest dimension among the non-spatial axes.
            spatial_dims = 2
            possible_axes = list(range(im.ndim - spatial_dims))

            # Heuristic: Find candidate axes with size <= 10
            candidates = [i for i in possible_axes if im.shape[i] <= 10]
            if candidates:
                # If multiple candidates, prefer the first one (often Channel)
                c_axis_pos = candidates[0]
            elif im.ndim == 4:
                # Fallback for 4D if no small axis found: assume classic axis 1
                c_axis_pos = 1
        elif im.ndim == 3:
            # Heuristic: treat a small axis (<=10) as channel axis when the other
            # axes are significantly larger (to avoid confusing thin spatial
            # dimensions with channels).
            sizes = im.shape
            candidates = [
                i
                for i, s in enumerate(sizes)
                if s <= 10 and max(sizes[j] for j in range(len(sizes)) if j != i) > 50
            ]
            if candidates:
                c_axis_pos = candidates[0]

        # Store original channel axis before any virtual extraction / stacking changes it
        c_axis_pos_orig = c_axis_pos

        # Resolve 'auto' blocksize using hardware-aware heuristic
        if blocksize == "auto":
            is_3d_mode = (im.ndim >= 3 and c_axis_pos is None) or (im.ndim >= 4)
            blocksize = get_auto_blocksize(
                im.shape,
                is_3d_mode,
                args.use_gpu,
                multiplier=args.mem_multiplier,
                c_axis=c_axis_pos,
                diameter=args.diameter,
                anisotropy=args.anisotropy,
            )
            print(f"Auto-resolved blocksize based on hardware: {blocksize}")

        # Decide whether we need to create/extract a new Zarr or use the current one.
        # we only NEED to extract if:
        # 1. Input is NOT Zarr (e.g. TIFF)
        # 2. Input is Zarr but we need to combine/stack multiple channels that are not already stacked
        # 3. Input is Zarr but chunks are extremely small (e.g. < 1MB)
        # 4. Input is Zarr but we want to optimize rechunking for the specific blocksize

        is_direct_compatible = False
        if is_zarr:
            # Universal Virtual Extraction: Any multi-channel Zarr can be virtually sliced
            # and stacked using Dask, avoiding expensive disk writes.
            is_direct_compatible = True

        if is_direct_compatible:
            print(f"Using existing Zarr directly: {args.input_file}")
            if c_axis_pos is not None:
                # Determine channel indices (0-based)
                c1 = (args.chan - 1) if args.chan > 0 else 0
                c2 = (args.chan2 - 1) if args.chan2 > 0 else -1

                # Use Dask for lazy slicing/stacking
                # Determine chunks to use for dask wrapper
                chunks = getattr(im, "chunks", "auto")
                if hasattr(im, "compute"):
                    im_da = im
                else:
                    im_da = da.from_array(im, chunks=chunks)

                if c2 < 0:
                    # Single channel virtual extraction
                    slc = [slice(None)] * im.ndim
                    slc[c_axis_pos] = c1
                    input_zarr = im_da[tuple(slc)]
                    print(
                        f"  Virtual extraction: slicing channel index {c1} at axis {c_axis_pos}"
                    )
                    # After slicing, this dimension is gone, so c_axis_pos becomes None for the workers
                    c_axis_pos = None
                else:
                    # DOUBLE channel virtual extraction: stack them virtually into a new index 0
                    slc1 = [slice(None)] * im.ndim
                    slc1[c_axis_pos] = c1
                    slc2 = [slice(None)] * im.ndim
                    slc2[c_axis_pos] = c2

                    ch1 = im_da[tuple(slc1)]
                    ch2 = im_da[tuple(slc2)]

                    # Stack results in (channels, Z, Y, X) or (channels, Y, X)
                    input_zarr = da.stack([ch1, ch2], axis=0)
                    print(
                        f"  Universal Virtual Extraction: stacked channels {c1 + 1}, {c2 + 1} virtually at axis 0"
                    )
                    c_axis_pos = 0  # Now it's at the front
            else:
                input_zarr = im

            # Ensure blocksize matches the rank of the final input_zarr
            if len(blocksize) > input_zarr.ndim:
                blocksize = blocksize[-input_zarr.ndim :]
            elif len(blocksize) < input_zarr.ndim:
                # Pad blocksize with 1s or max dims as needed
                diff = input_zarr.ndim - len(blocksize)
                blocksize = (1,) * diff + blocksize
        else:
            # Multi-channel or conversion required
            if c_axis_pos is not None and im.shape[c_axis_pos] > 1:
                # Multi-channel data: extract ONLY the channels required by the user.
                num_channels_total = im.shape[c_axis_pos]

                # Determine which 0-based indices are needed.
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
                    f"Extracting required channels: {[idx + 1 for idx in needed_indices]}"
                )
                sys.stdout.flush()

                # Extract spatial dimensions (remove channel axis)
                spatial_shape = tuple(
                    s for i, s in enumerate(im.shape) if i != c_axis_pos
                )

                # Match blocksize rank to spatial_shape rank for Zarr chunks
                # SANITY CHECK: Never use huge chunks for Zarr storage (causes OOM during rechunk)
                # Keep max chunk size around 256MB per channel
                zarr_chunks = []
                for i, b in enumerate(blocksize):
                    if i == c_axis_pos:
                        continue
                    # Cap spatial chunks to something manageable (e.g. 1024-2048) if block is huge
                    dim_size = spatial_shape[len(zarr_chunks)]
                    if b > 4096 and dim_size > 4096:
                        zarr_chunks.append(1024)
                    else:
                        zarr_chunks.append(b)
                zarr_chunks = tuple(zarr_chunks)

                # Final sanity check: if rank still mismatched
                if len(zarr_chunks) != len(spatial_shape):
                    if len(zarr_chunks) > len(spatial_shape):
                        zarr_chunks = zarr_chunks[-len(spatial_shape) :]
                    else:
                        zarr_chunks = (max(spatial_shape),) * (
                            len(spatial_shape) - len(zarr_chunks)
                        ) + zarr_chunks

                # Create path for the unified input Zarr
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
                            f"Warning: requested channel {ch_idx + 1} exceeds image depth {num_channels_total}. Falling back to ch 1."
                        )
                        ch_idx = 0

                    if args.input_zarr:
                        # Strip .zarr extension if it exists to make it cleaner
                        if args.input_zarr.endswith(".zarr"):
                            ch_path = args.input_zarr[:-5] + f"_ch{ch_idx}.zarr"
                        else:
                            ch_path = args.input_zarr + f"_ch{ch_idx}.zarr"
                    else:
                        ch_path = os.path.join(parent, f"channel_{ch_idx}.zarr")

                    # Check for reuse
                    if os.path.exists(ch_path):
                        try:
                            z_ch = zarr.open(ch_path, mode="r")
                            # Check if it is an array and matching spatial shape
                            if hasattr(z_ch, "shape") and z_ch.shape == spatial_shape:
                                print(
                                    f"  Channel {ch_idx + 1}: reusing existing Zarr at {ch_path}"
                                )
                                sys.stdout.flush()
                                channel_zarrs.append(zarr.open(ch_path, mode="a"))
                                continue
                            else:
                                print(
                                    f"  Channel {ch_idx + 1}: existing Zarr shape/type mismatch. re-extracting..."
                                )
                        except Exception:
                            # Not a valid Zarr or protected, proceed to extraction
                            pass

                    print(f"  Channel {ch_idx + 1}: extracting to {ch_path}...")
                    sys.stdout.flush()

                    try:
                        slc = [slice(None)] * im.ndim
                        slc[c_axis_pos] = ch_idx
                        if hasattr(im, "compute"):
                            ch_dask = im[tuple(slc)]
                        else:
                            ch_dask = da.from_array(im, chunks="auto")[tuple(slc)]

                        # Clean up existing directory or file to avoid "An array exists in store" conflicts
                        if os.path.exists(ch_path):
                            try:
                                if os.path.isdir(ch_path):
                                    shutil.rmtree(ch_path)
                                else:
                                    os.remove(ch_path)
                            except Exception:
                                pass

                        ch_dask.rechunk(zarr_chunks).to_zarr(ch_path, overwrite=True)
                        z_ch = zarr.open(ch_path, mode="a")

                        # Inherit metadata from the parent Zarr/file if available
                        if hasattr(im, "attrs"):
                            try:
                                for k, v in im.attrs.items():
                                    z_ch.attrs[k] = v
                            except Exception:
                                pass
                        # Special case: discovered calibration from TIFF etc
                        if "pixel_size" not in z_ch.attrs and discovered_px:
                            try:
                                # Ensure we save a usable list format
                                z_ch.attrs["pixel_size"] = [
                                    1.0,
                                    discovered_px * discovered_anisotropy,
                                    discovered_px,
                                    discovered_px,
                                ]
                            except Exception:
                                pass

                        channel_zarrs.append(z_ch)
                        print(f"  Channel {ch_idx + 1}: success (shape={z_ch.shape})")
                    except Exception as e:
                        print(f"  Channel {ch_idx + 1}: failed lazily ({e}). Eager...")
                        slc_e = [slice(None)] * im.ndim
                        slc_e[c_axis_pos] = ch_idx
                        ch_data = im[tuple(slc_e)]
                        z_ch = zarr.open(
                            ch_path,
                            mode="w",
                            shape=spatial_shape,
                            chunks=zarr_chunks,
                            dtype=im.dtype,
                        )
                        z_ch[...] = ch_data
                        channel_zarrs.append(z_ch)

                input_zarr = channel_zarrs[0]
                blocksize = zarr_chunks
                c_axis_pos = None
                was_subsetted = True
                print(
                    f"Using channel {needed_indices[0] + 1} as base, shape: {input_zarr.shape}, blocksize: {blocksize}"
                )
                sys.stdout.flush()
            else:
                # Single channel conversion (e.g. from Tiff)
                if len(blocksize) > im.ndim:
                    blocksize = blocksize[-im.ndim :]
                elif len(blocksize) < im.ndim:
                    blocksize = (max(im.shape),) * (
                        im.ndim - len(blocksize)
                    ) + blocksize

                if args.input_zarr:
                    zpath = args.input_zarr
                    parent = os.path.dirname(zpath) or "."
                    os.makedirs(parent, exist_ok=True)
                    # Check if Zarr already exists and reuse it
                    if os.path.exists(zpath):
                        try:
                            z_ch = zarr.open(zpath, mode="r")
                            # Check if it is an array and matching shape
                            if hasattr(z_ch, "shape") and z_ch.shape == im.shape:
                                print(f"Reusing existing input Zarr at {zpath}")
                                sys.stdout.flush()
                                input_zarr = zarr.open(zpath, mode="r")
                                # We still need to notify success and set input_zarr
                                print(
                                    f"Input zarr shape: {input_zarr.shape}, chunks: {input_zarr.chunks}"
                                )
                                sys.stdout.flush()
                                # We can skip the rest of the conversion logic
                                skip_conversion = True
                            else:
                                print(
                                    f"Existing Zarr at {zpath} shape/type mismatch. re-converting..."
                                )
                                skip_conversion = False
                        except Exception:
                            # Not a valid Zarr, proceed to write
                            skip_conversion = False
                    else:
                        skip_conversion = False
                else:
                    tmpdir = tempfile.TemporaryDirectory(
                        prefix="distributed_cellpose_tmp_", dir=args.temporary_directory
                    )
                    zpath = os.path.join(tmpdir.name, "input.zarr")
                    skip_conversion = False

                if not skip_conversion:
                    print(f"Writing input Zarr to {zpath} (dtype={im.dtype})...")
                    sys.stdout.flush()
                    try:
                        # Clean up existing directory or file to avoid "An array exists in store" conflicts
                        if os.path.exists(zpath):
                            try:
                                if os.path.isdir(zpath):
                                    shutil.rmtree(zpath)
                                else:
                                    os.remove(zpath)
                            except Exception:
                                pass

                        if hasattr(im, "compute"):
                            im_to_save = im
                        else:
                            im_to_save = da.from_array(im, chunks="auto")

                        im_to_save.rechunk(blocksize).to_zarr(zpath, overwrite=True)
                        input_zarr = zarr.open(zpath, mode="a")

                        # Inherit and save metadata for reuse
                        if hasattr(im, "attrs"):
                            try:
                                for k, v in im.attrs.items():
                                    input_zarr.attrs[k] = v
                            except Exception:
                                pass
                        # If we discovered calibration (e.g. from TIFF)
                        if (
                            "pixel_size" not in input_zarr.attrs
                            and discovered_px is not None
                        ):
                            try:
                                # Note: im.ndim might be 3 (Z, Y, X)
                                if im.ndim == 3:
                                    input_zarr.attrs["pixel_size"] = [
                                        discovered_px * discovered_anisotropy,
                                        discovered_px,
                                        discovered_px,
                                    ]
                                elif im.ndim == 4:
                                    input_zarr.attrs["pixel_size"] = [
                                        1.0,
                                        discovered_px * discovered_anisotropy,
                                        discovered_px,
                                        discovered_px,
                                    ]
                            except Exception:
                                pass

                    except Exception as e:
                        print(f"Lazy write failed ({e}), eager fallback...")
                        z_ch = zarr.open(
                            zpath,
                            mode="w",
                            shape=im.shape,
                            chunks=blocksize,
                            dtype=im.dtype,
                        )
                        z_ch[...] = im
                        input_zarr = z_ch
                        # Re-write metadata to eager input_zarr
                        if discovered_px:
                            try:
                                if im.ndim == 3:
                                    input_zarr.attrs["pixel_size"] = [
                                        discovered_px * discovered_anisotropy,
                                        discovered_px,
                                        discovered_px,
                                    ]
                                elif im.ndim == 4:
                                    input_zarr.attrs["pixel_size"] = [
                                        1.0,
                                        discovered_px * discovered_anisotropy,
                                        discovered_px,
                                        discovered_px,
                                    ]
                            except Exception:
                                pass
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

        # Resolve 'auto' blocksize using hardware-aware heuristic
        if blocksize == "auto":
            is_3d_mode = input_zarr.ndim >= 3
            blocksize = get_auto_blocksize(
                input_zarr.shape,
                is_3d_mode,
                args.use_gpu,
                multiplier=args.mem_multiplier,
                c_axis=None,
                diameter=args.diameter,
                anisotropy=args.anisotropy,
            )
            print(f"Auto-resolved blocksize based on hardware: {blocksize}")
        tmpdir = None

    # Worker patching handles float slice coercion, no proxy needed

    # Attempt to auto-extract anisotropy from Zarr metadata if not provided
    if input_zarr is not None and args.anisotropy == 1.0:
        try:
            if hasattr(input_zarr, "attrs") and "pixel_size" in input_zarr.attrs:
                ps = input_zarr.attrs["pixel_size"]
                # Convert to floats and handle [C, Z, Y, X] or [Z, Y, X]
                ps_vals = [float(v) for v in ps]
                if len(ps_vals) == 4:
                    vz, vx = ps_vals[1], ps_vals[3]
                elif len(ps_vals) == 3:
                    vz, vx = ps_vals[0], ps_vals[2]
                else:
                    vz, vx = 1.0, 1.0

                if vz > 0 and vx > 0:
                    args.anisotropy = vz / vx
                    print(
                        f"Found calibration in Zarr metadata. Setting anisotropy={args.anisotropy:.3f} (Z={vz}, XY={vx})"
                    )
        except Exception:
            pass

    # Optimization: Early Exit for empty blocks (skip GPU for background blocks)
    # Also find global statistics for normalization if requested.
    segmentation_mask = None
    global_limits = None

    # Determine if mask generation is worth it based on file size if no pyramid is found.
    # If file is > 100 GB and we're at Level 0, we should skip the mask to save time.
    size_limit_for_mask = 100 * 1024**3  # 100 GB
    image_size_bytes = getattr(im, "nbytes", 0) or (im.size * im.itemsize if hasattr(im, "size") else 0)

    if (args.min_intensity is not None or args.global_norm) and im is not None:
        try:
            # 1. Use pyramid-based statistics and mask for speed if input is OME-Zarr
            if is_zarr:
                # Check if we should skip the mask due to extreme size and lack of pyramid
                # We'll let _create_optimized_segmentation_mask decide if it actually finds a pyramid.
                print(
                    "Building pyramidal mask/stats with Level+4 for statistics and Level+3 for exclusion..."
                )
                segmentation_mask, v_min, v_max, resolved_min_i = (
                    _create_optimized_segmentation_mask(
                        z_root,
                        args.min_intensity,
                        c_axis=c_axis_pos_orig,
                        c_idx=(args.chan - 1) if args.chan > 0 else 0,
                        skip_if_huge=(image_size_bytes > size_limit_for_mask),
                    )
                )

                if args.min_intensity == "auto" or args.min_intensity == "Auto":
                    print(
                        f"Resolved 'auto' intensity threshold to: {resolved_min_i:.2f}"
                    )
                    args.min_intensity = resolved_min_i

                if args.global_norm:
                    global_limits = (v_min, v_max)
                    print(
                        f"Pyramidal global normalization limits: {global_limits[0]:.2f} - {global_limits[1]:.2f}"
                    )
            else:
                # Fallback: calculate statistics from sampled slices if needed (Original logic)
                if args.min_intensity == "auto" or args.global_norm:
                    try:
                        print(
                            "Calculating robust image statistics (Threshold/Normalization)..."
                        )
                        sys.stdout.flush()

                        # Sample a few slices across the stack for more robust statistics.
                        # We take 5 slices (10%, 30%, 50%, 70%, 90%) to catch signal in both halves.
                        shape = input_zarr.shape
                        ndim = input_zarr.ndim
                        if ndim >= 3:
                            z_indices = [
                                int(shape[0] * r) for r in [0.1, 0.3, 0.5, 0.7, 0.9]
                            ]
                            samples = []
                            for zi in z_indices:
                                try:
                                    samples.append(
                                        np.array(input_zarr[zi, ::8, ::8]).flatten()
                                    )
                                except Exception:
                                    pass
                            if samples:
                                slice_sample = np.concatenate(samples)
                            else:
                                slice_sample = np.array(input_zarr[::8, ::8])
                        else:
                            slice_sample = np.array(input_zarr[::8, ::8])

                        background_level = float(np.median(slice_sample))
                        q75, q25 = np.percentile(slice_sample, [75, 25])
                        iqr = q75 - q25
                        robust_std = qiqr = (
                            iqr / 1.349 if iqr > 0 else float(np.std(slice_sample))
                        )
                        noise_floor = background_level + max(robust_std * 3, 1.0)

                        # Calculate Global Normalization Limits (p1, p99)
                        if args.global_norm:
                            p1, p99 = np.percentile(slice_sample, [1, 99])
                            # If p1 == p99, use min/max of sample
                            if p1 >= p99:
                                p1, p99 = np.min(slice_sample), np.max(slice_sample)
                            # Avoid div/0 and non-zero normalization range
                            global_limits = (float(p1), float(max(p99, p1 + 1.0)))
                            print(
                                f"Global normalization limits (p1/p99): {global_limits[0]:.2f} - {global_limits[1]:.2f}"
                            )

                        # Resolve 'auto' threshold if requested
                        if args.min_intensity == "auto":
                            # Triangle algorithm: find the point on the 'slope' of the background
                            # peak where it starts to level off into signal.
                            def _triangle_threshold(data, bins=512):
                                low = float(np.min(data))
                                high = float(np.max(data))
                                if low >= high:
                                    return low
                                counts, edges = np.histogram(
                                    data, bins=bins, range=(low, high)
                                )
                                centers = (edges[:-1] + edges[1:]) / 2
                                counts = counts.astype(float)
                                peak_idx = np.argmax(counts)

                                # Determine if peak is left or right and where farthest bin is
                                flipped = False
                                if peak_idx < bins // 2:
                                    # Standard left-peak
                                    x_pts = np.arange(peak_idx, bins)
                                    y_pts = counts[peak_idx:]
                                    start_x, start_y = peak_idx, counts[peak_idx]
                                    end_x, end_y = bins - 1, counts[-1]
                                else:
                                    # Right-peak fallback
                                    flipped = True
                                    counts = counts[::-1]
                                    peak_idx = bins - 1 - peak_idx
                                    x_pts = np.arange(peak_idx, bins)
                                    y_pts = counts[peak_idx:]
                                    start_x, start_y = peak_idx, counts[peak_idx]
                                    end_x, end_y = bins - 1, counts[-1]

                                # Distance to triangle line
                                a = start_y - end_y
                                b = end_x - start_x
                                c = -a * start_x - b * start_y
                                dist = np.abs(a * x_pts + b * y_pts + c)
                                best_idx = peak_idx + np.argmax(dist)
                                if flipped:
                                    best_idx = bins - 1 - best_idx
                                return centers[best_idx]

                            triangle_t = _triangle_threshold(slice_sample)

                            # Otsu's method (Simplified Numpy/Native version)
                            def _otsu_threshold(data, bins=256):
                                counts, edges = np.histogram(data, bins=bins)
                                centers = (edges[:-1] + edges[1:]) / 2
                                p = counts / counts.sum()
                                w = p.cumsum()
                                mu = (centers * p).cumsum()
                                mu_total = mu[-1]
                                # Variance between classes
                                idx = (w > 0) & (w < 1)
                                sigma_b2 = np.zeros_like(w)
                                sigma_b2[idx] = (mu_total * w[idx] - mu[idx]) ** 2 / (
                                    w[idx] * (1 - w[idx])
                                )
                                return centers[np.argmax(sigma_b2)]

                            otsu_t = _otsu_threshold(slice_sample)

                            # We use Triangle as it is more conservative for microscopy backgrounds,
                            # but we cap it at a fraction of Otsu to avoid over-thresholding
                            # if Otsu is very aggressive, or taking it if Triangle is too close to noise.
                            conservative_otsu = otsu_t * 0.33 + background_level * 0.67
                            args.min_intensity = max(
                                noise_floor, triangle_t, conservative_otsu
                            )

                            print(
                                f"Auto-calculated min_intensity threshold: {args.min_intensity:.2f} "
                                f"(noise_floor: {noise_floor:.2f}, triangle: {triangle_t:.2f}, "
                                f"otsu-lite: {conservative_otsu:.2f})"
                            )
                    except Exception as e:
                        print(f"Warning: could not auto-calculate stats: {e}")
                        if args.min_intensity == "auto":
                            args.min_intensity = None
                        global_limits = None

            # 2. Generate or refine a foreground mask for distributed_eval.
            # Using a mask is much safer than patching workers' eval() because
            # we can dilate it to ensure neighboring overlapping blocks are preserved.
            if args.min_intensity is not None and args.min_intensity > 0:
                print(
                    f"Finalizing foreground mask (threshold={args.min_intensity:.2f})..."
                )
                sys.stdout.flush()

                # If we don't have a mask yet (e.g. not a Zarr or was missing pyramids), generate it.
                if segmentation_mask is None:
                    # For reasonable image sizes (<500MP), we can hold the whole boolean mask in RAM.
                    # Thresholding directly at full-res avoids misalignment issues.
                    total_pixels = np.prod(input_zarr.shape)
                    if total_pixels < 500 * 1024 * 1024:
                        print(
                            "Image small enough (<500MP): generating full-resolution mask for precise block filtering."
                        )
                        segmentation_mask = np.array(input_zarr) > args.min_intensity
                    else:
                        # For huge images, we generate a downsampled mask.
                        # Cellpose distributed_eval can handle low-resolution masks by mapping shapes.
                        ds_factor = 8
                        print(
                            f"Image too large ({total_pixels / 1e6:.1f}MP): generating downsampled mask (factor {ds_factor})."
                        )
                        slices = tuple(
                            slice(None, None, ds_factor) for _ in input_zarr.shape
                        )
                        segmentation_mask = (
                            np.array(input_zarr[slices]) > args.min_intensity
                        )

                # Dilate mask to include cell tails and buffer at block boundaries.
                # This ensures neighboring chunks are processed for robust stitching.
                if segmentation_mask is not None and ndimage is not None:
                    try:
                        # Dilation should be ~0.75x diameter to catch overlapping cells.
                        eff_diam = args.diameter if args.diameter > 1 else 30.0

                        # Determine downsampling factor of the CURRENT segmentation_mask to scale iterations
                        # If it was pyramidal Level 4, it's 16x. Level 3 is 8x. Fallback is 8x.
                        mask_shape = segmentation_mask.shape
                        full_shape = input_zarr.shape

                        # Use spatial dimensions (last 2 or 3) to find scale
                        dim_ratio = full_shape[-1] / mask_shape[-1]

                        num_iter = int(math.ceil(eff_diam * 0.75 / dim_ratio))
                        # Ensure at least 2 pixels of dilation
                        num_iter = max(2, num_iter)

                        print(
                            f"Dilating mask to protect boundaries (iterations={num_iter}, scale_factor={dim_ratio:.1f})"
                        )
                        # We must compute the dask mask to dilate it
                        if hasattr(segmentation_mask, "compute"):
                            mask_np = segmentation_mask.compute()
                        else:
                            mask_np = np.asanyarray(segmentation_mask)

                        mask_np = _dilate_mask(mask_np, iterations=num_iter)
                        # Put back into dask for distributed_eval to handle mapping
                        segmentation_mask = da.from_array(mask_np, chunks=mask_np.shape)
                    except Exception as de:
                        print(f"Warning: mask dilation failed ({de})")
                        print(f"Warning: dilation failed: {de}")

                if segmentation_mask is not None:
                    # Explicitly compute mean from compiled numpy mask
                    coverage = np.mean(segmentation_mask) * 100
                    print(
                        f"Early Exit mask created. Will process approx {coverage:.1f}% of blocks."
                    )
                sys.stdout.flush()

        except Exception as e:
            print(f"Warning: could not create Early Exit mask: {e}")
            args.min_intensity = None
            segmentation_mask = None

    # Prepare final segmentation level and upscaling
    # If using OME-Zarr, we can segment at a lower resolution level and upscale labels.
    target_level = 0
    if is_zarr:
        # Heuristic: if diameter is large (>100px), segment at level 1 to save 4-8x time.
        # This is strictly for label segmentation where precision is less critical than speed.
        if args.diameter >= 100:
            target_level = 1
            print(
                f"High-diameter optimization: segmenting at pyramid Level {target_level} and upscaling labels."
            )
            # Adjust spatial blocksize for lower resolution level
            input_zarr = _get_pyramid_level(im, target_level) or im
            # Blocksize needs to be adjusted for the lower resolution
            # (already handles in _run_distributed_eval by mapping shapes, but we prefer
            # to be explicit here)
            blocksize = tuple(max(1, b // (2**target_level)) for b in blocksize)
            print(f"Adjusted blocksize for Level {target_level}: {blocksize}")

    # Determine model_kwargs
    model_kwargs = (
        {"model_type": args.model, "gpu": args.use_gpu}
        if isinstance(args.model, str)
        else args.model
    )

    # GUI and CLI now use 1-based indexing consistently (1=First, 2=Second, 0=None).
    # However, if we subsetted the channels during extraction (was_subsetted),
    # the 'channels' indices must refer to the indices in the stack we created.
    if was_subsetted:
        # If we extracted multiple channels into channel_zarrs,
        # preprocessing_steps will stack them, creating a [Channel, Z, Y, X]
        # or [Z, Channel, Y, X] result.
        if len(channel_zarrs) > 1:
            c1 = 1
            c2 = 2
        else:
            # Single channel extracted: treat as grayscale for safety
            c1, c2 = 0, 0
        channels = [c1, c2]
        print(f"Subsetting/Stacking mode: mapping channels to stack indices {channels}")
    else:
        # Single channel or folder of TIFFs: use user-provided indices
        # If the input doesn't have a channel axis, we must use [0, 0] for grayscale.
        if c_axis_pos is None and input_zarr.ndim < 3:
            channels = [0, 0]
        else:
            c1 = args.chan if args.chan > 0 else 1
            c2 = args.chan2 if args.chan2 > 0 else 0
            channels = [c1, c2]

    # Resolve dimensionality
    is_3d_spatial = (input_zarr.ndim >= 3 and c_axis_pos is None) or (
        input_zarr.ndim >= 4 and c_axis_pos is not None
    )

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
        "do_3D": is_3d_spatial,
        "normalize": not args.no_norm,
    }

    # Add any extra args from CLI (e.g. --do_3D, --flow3D_smooth, etc.)
    if hasattr(args, "additional_eval_kwargs"):
        for k, v in args.additional_eval_kwargs.items():
            print(f"Adding extra eval kwarg: {k}={v}")
            eval_kwargs[k] = v

    # Explicitly handle bsize and tile_overlap if provided
    # Note: 'tile' argument is NOT valid for CellposeModel.eval() in v3 or v4 (Cellpose-SAM)
    if args.bsize is not None:
        eval_kwargs["bsize"] = args.bsize
    else:
        # Default behavior: Let Cellpose use its internal tiling or bsize defaults.
        # This is safer for coordination between distributed blocks.
        pass

    if args.tile_overlap is not None:
        eval_kwargs["tile_overlap"] = args.tile_overlap
    else:
        # Auto-calculate tile_overlap based on diameter to avoid boundary artifacts.
        # We want the overlap (in pixels) to be at least 1.25x the diameter.
        # tile_overlap in Cellpose is a fraction (0.1 = 10%) of the block spatial dimensions.
        try:
            # blocksize is (Z, Y, X) or (Y, X)
            spatial_block = blocksize[1:] if len(blocksize) == 3 else blocksize
            max_spatial_dim = max(spatial_block)

            # Recommendation: 1.25 * diameter for robust stitching.
            # We use at least 15% as a baseline.
            eff_diam = args.diameter if args.diameter > 1 else 30.0
            overlap_fraction = (eff_diam * 1.25) / max_spatial_dim

            # Clamp between 15% and 40% (high overlap slows down processing but ensures quality)
            # If diameter is huge, we'd rather be slow than have empty pixels.
            auto_overlap = min(0.4, max(0.15, overlap_fraction))

            eval_kwargs["tile_overlap"] = auto_overlap
            print(
                f"Auto-calculated tile_overlap: {auto_overlap:.3f} (based on diameter {eff_diam} and block {max_spatial_dim})"
            )
        except Exception as e:
            print(
                f"Warning: could not auto-calculate tile_overlap ({e}). Using default 0.1."
            )
            eval_kwargs["tile_overlap"] = 0.1

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

        # Handle key=value format (e.g., flow3D_smooth=3)
        if "=" in arg:
            key, value = arg.split("=", 1)
            key = key.lstrip("-")
        # Handle --key or key (without --)
        else:
            key = arg.lstrip("-")
            value = None

            # Check if next arg is a value or another flag
            if i + 1 < len(unknown_args):
                next_arg = unknown_args[i + 1]
                if not next_arg.startswith("-") and "=" not in next_arg:
                    value = next_arg
                    i += 2
                else:
                    value = True
                    i += 1
            else:
                value = True
                i += 1

        if value is True:
            eval_kwargs[key] = True
        elif value is not None:
            # Try to convert to appropriate type
            try:
                # Try boolean
                if str(value).lower() in ("true", "false"):
                    eval_kwargs[key] = str(value).lower() == "true"
                # Try float
                elif "." in str(value):
                    eval_kwargs[key] = float(value)
                # Try int
                else:
                    eval_kwargs[key] = int(value)
            except (ValueError, AttributeError):
                # Keep as string if conversion fails
                eval_kwargs[key] = value

        if "=" in arg:
            i += 1

    # Map CLI-specific flags from unknown_args to Cellpose API parameters
    # cellpose CLI uses --total_mask for what API calls 'flow_threshold'
    if "total_mask" in eval_kwargs:
        eval_kwargs["flow_threshold"] = eval_kwargs.pop("total_mask")

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

    # Logic to find a blocksize that actually fits in memory
    print(
        f"\nHardware detection: {os.cpu_count()} CPUs, {torch.cuda.device_count() if (torch is not None and torch.cuda.is_available()) else 0} GPUs detected. Optimize={args.optimize_parallel}"
    )

    current_block = list(blocksize) if isinstance(blocksize, tuple) else []
    if blocksize == "auto":
        # Final safety resolution if not handled during input loading
        is_3d = False
        shape = (1024, 1024)
        if "input_zarr" in locals() and input_zarr is not None:
            is_3d = input_zarr.ndim >= 3
            shape = input_zarr.shape
        elif eval_kwargs.get("do_3D"):
            is_3d = True
            shape = (64, 1024, 1024)

        blocksize = get_auto_blocksize(
            shape,
            is_3d,
            args.use_gpu,
            multiplier=args.mem_multiplier,
            c_axis=None,
            diameter=args.diameter,
            anisotropy=args.anisotropy,
        )
        current_block = list(blocksize)
        print(f"Auto-blocksize final safety resolution: {blocksize}")

    optimal_n_workers = 0
    dask_threads = 1
    internal_threads = 1

    # Minimum spatial side to avoid boundary artifacts.
    # To prevent "holes" or visual truncation, the block size MUST be larger
    # than the overlap depth (typically 1.5 * diameter).
    # We allow the block size to go as low as 2.0 * diameter to find a memory fit
    # before we ever consider splitting the Z-axis.
    min_spatial_side = int(round(2.0 * (args.diameter if args.diameter > 1 else 30.0)))

    # Hard minimum for safety: never go below 1.5x diameter or Dask stitching will fail.
    hard_min_spatial = int(round(1.5 * (args.diameter if args.diameter > 1 else 30.0)))

    while optimal_n_workers == 0:
        optimal_n_workers, dask_threads, internal_threads = get_optimal_n_workers(
            use_gpu=args.use_gpu,
            requested_n_workers=args.n_workers,
            blocksize=tuple(current_block),
            model_type=args.model if isinstance(args.model, str) else "cyto3",
            diameter=args.diameter,
            mem_multiplier=args.mem_multiplier,
            anisotropy=args.anisotropy if args.anisotropy > 0 else 1.0,
        )

        if optimal_n_workers > 0:
            blocksize = tuple(current_block)
            break

        # If we get here, the block is too large for 1 worker.
        # Reduce the largest dimension(s) and retry.
        z, y, x = current_block
        if y > min_spatial_side or x > min_spatial_side:
            print(
                f"CRITICAL: Block {tuple(current_block)} is too large for system RAM/VRAM. Reducing spatial dimensions..."
            )
            current_block[1] = max(min_spatial_side, int(y * 0.75))
            current_block[2] = max(min_spatial_side, int(x * 0.75))
        elif y > hard_min_spatial or x > hard_min_spatial:
            # Drop down from min_spatial_side to absolute hard minimum (1.5x diameter)
            # before ever touching the Z-axis.
            print(
                f"CRITICAL: Block {tuple(current_block)} is still too large. Dropping to hard minimum spatial sides ({hard_min_spatial})..."
            )
            current_block[1] = max(hard_min_spatial, int(y * 0.75))
            current_block[2] = max(hard_min_spatial, int(x * 0.75))
        elif z > 150:
            # Only reduce Z if it's already large enough to be split safely.
            # (Assuming a ~30px overlap, we need at least 100-150px to split reliably).
            print(
                f"CRITICAL: Block {tuple(current_block)} is still too large. Reducing Z dimension..."
            )
            current_block[0] = max(100, z // 2)
        else:
            # We reached a minimum viable block size and it still doesn't fit
            print(
                f"ERROR: Even at the minimum viable block size {tuple(current_block)}, the memory estimation "
                f"exceeds hardware limits (Possibly very large diameter or batch_size)."
            )
            print("Proceeding with 1 worker and hoping for the best (OOM likely).")
            optimal_n_workers = 1
            blocksize = tuple(current_block)
            break

    # If optimize_parallel is ON, we might want to INCREASE workers if possible by shrinking further
    if args.optimize_parallel and optimal_n_workers < 2 and args.use_gpu:
        print(
            "Optimization ON: Checking if further block reduction would allow more parallel workers..."
        )
        z, y, x = current_block
        if y > 256 or x > 256:
            test_block = (z, 256, 256)
            n_p, d_p, i_p = get_optimal_n_workers(
                use_gpu=args.use_gpu,
                requested_n_workers=args.n_workers,
                blocksize=test_block,
                model_type=args.model if isinstance(args.model, str) else "cyto3",
                diameter=args.diameter,
                anisotropy=args.anisotropy if args.anisotropy > 0 else 1.0,
            )
            if n_p > optimal_n_workers:
                print(
                    f"Optimization ON: Smaller block {test_block} allowed {n_p} workers. Switching."
                )
                optimal_n_workers, dask_threads, internal_threads = n_p, d_p, i_p
                blocksize = test_block

    # Final blocksize update in eval_kwargs if it changed
    if eval_kwargs.get("do_3D"):
        # Blocksize is already resolved.
        pass

    # Optimization: Auto-tune batch_size if parallel optimization is enabled and GPU is used
    if (
        args.use_gpu
        and args.optimize_parallel
        and eval_kwargs.get("do_3D")
        and eval_kwargs.get("batch_size", 1) == 1
    ):
        try:
            # We want to increase batch_size to fill VRAM
            # We know blocksize (Z, Y, X) and scale factors
            # Robustly unpack Z, Y, X
            if len(blocksize) == 3:
                bz, by, bx = blocksize
            else:
                bz = 1
                by, bx = blocksize

            # Avoid division by zero if diameter is not set correctly
            # We treat diameters <= 1.0 as "default" or "auto" to avoid extreme scaling
            eff_diam = args.diameter if args.diameter > 1.0 else 30.0
            scale = 30.0 / eff_diam

            # Cap scaling factor to avoid blowing up VRAM estimations on tiny diameters
            scale = min(5.0, max(0.2, scale))

            anisotropy = args.anisotropy if args.anisotropy > 0 else 1.0

            # Get available VRAM
            if torch is not None and torch.cuda.is_available():
                gpu_props = torch.cuda.get_device_properties(0)
                gpu_mem = gpu_props.total_memory
                free_mem = gpu_mem - torch.cuda.memory_allocated(0)

                # Plane size in VRAM approximation.
                # In 3D mode, Cellpose runs the 2D model on XY, YZ and XZ planes (rescaled).
                # The VRAM bottleneck is the largest of these planes.
                area_xy = (by * scale) * (bx * scale)
                area_yz = (bz * scale * anisotropy) * (by * scale)
                area_xz = (bz * scale * anisotropy) * (bx * scale)

                max_plane_pixels = max(area_xy, area_yz, area_xz)

                # Multiplier for inference activations + overhead. 45.0 is a realistic for 8GB+ GPUs.
                vram_plane_mult = 150.0 if gpu_mem < 5 * 1024**3 else 45.0

                # NEW: Account for 3D Volume Overhead in VRAM!
                # If we are in 3D mode, Cellpose keeps the rescaled volume in memory
                # which significantly reduces the pool available for batch_size activations.
                volume_vram_bytes = 0
                if eval_kwargs.get("do_3D"):
                    # Use a multiplier that accounts for flows (6x) + probs (1x) + input (1x)
                    v_mult = 40.0 if gpu_mem < 5 * 1024**3 else 10.0
                    rescaled_vol = (
                        (bz * scale * anisotropy) * (by * scale) * (bx * scale)
                    )
                    volume_vram_bytes = rescaled_vol * 4 * v_mult

                mem_per_plane = max_plane_pixels * 4 * vram_plane_mult

                # Targeting usage (conservative for small GPUs)
                ratio = 0.5 if gpu_mem < 5 * 1024**3 else 0.85
                target_mem = min(gpu_mem * ratio, free_mem * 0.9) - volume_vram_bytes

            # Check how many planes fit
            # We assume 1 worker here (optimal_n_workers is likely 1 per GPU)
            if mem_per_plane > 0 and target_mem > 0:
                max_batch = int(target_mem / mem_per_plane)

                # Global batch ceiling for 3D stability.
                # 3D inference is much more VRAM-hungry than 2D due to volume stitching.
                # We cap it much lower than 2D.
                batch_cap = 8 if eval_kwargs.get("do_3D") else 128

                # Very conservative for small GPUs (< 5GB)
                if gpu_mem < 5 * 1024**3:
                    batch_cap = 2 if eval_kwargs.get("do_3D") else 8

                new_batch = max(1, min(batch_cap, max_batch))

                if new_batch > 1:
                    print(
                        f"Optimization ON: Increasing batch_size from 1 to {new_batch} to utilize VRAM (Estimated plane mem: {mem_per_plane / 1024**2:.1f} MB, Volume overhead: {volume_vram_bytes / 1024**2:.1f} MB)"
                    )
                    eval_kwargs["batch_size"] = new_batch
            else:
                if eval_kwargs.get("do_3D") and target_mem <= 0:
                    print(
                        f"Optimization: 3D volume overhead ({volume_vram_bytes / 1024**2:.1f} MB) consumes all target VRAM. Keeping batch_size=1."
                    )
        except Exception as e:
            print(f"Optimization warning: could not auto-tune batch_size: {e}")

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

    # Prepare cluster configuration
    cluster_kwargs = {
        "n_workers": optimal_n_workers,
        "threads_per_worker": dask_threads,
        "death_timeout": 60 if args.use_gpu else 15,
        "local_directory": args.temporary_directory,
        # Force binding to 127.0.0.1 with correct port 0 (random free)
        # to avoid 0.0.0.0 issues on Windows and avoid port collisions.
        # Note: arg is named 'open_dashboard' in the parser
        "dashboard_address": "127.0.0.1:0"
        if getattr(args, "open_dashboard", False)
        else None,
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
        # Prefer placing the intermediate (unstitched) Zarr inside the user's
        # output folder when one was provided. Use a clear suffix so the
        # intermediate can be distinguished from final outputs.
        out_dir = (
            args.output_dir if args.output_dir else os.path.dirname(output_tif) or "."
        )
        base_name = os.path.splitext(os.path.basename(output_tif))[0]
        write_zarr = os.path.join(out_dir, base_name + "_unstitched.zarr")

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
        mask=segmentation_mask,
        global_limits=global_limits,
    )

    # Upscale labels if segmented at lower resolution level
    if target_level > 0:
        print(f"Upscaling labels from Level {target_level} to Level 0...")
        # out_zarr is typically a zarr array; wrap it in dask for upscaling
        da_labels = da.from_array(out_zarr, chunks="auto")
        # Upscale
        out_zarr = _upscale_labels_to_level0(da_labels, target_level)
        print("Upscaling complete.")
        sys.stdout.flush()

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

    # Close log handle before exiting
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
