#!/usr/bin/env python3
"""Distributed Cellpose CLI helper.

This module provides a command-line interface for running Cellpose segmentation
distributed across Dask workers, with support for Zarr arrays.
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
import time
import traceback
import webbrowser
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

# Optional dependencies handled with try-except to allow partial environments
try:
    import zarr
except Exception:
    zarr = None

try:
    from zarr.core import array as _zarr_array
except Exception:
    _zarr_array = None

try:
    import zarr.core.indexing as _z_idx
except Exception:
    _z_idx = None

try:
    import torch
except ImportError:
    torch = None

try:
    import torch.utils.mkldnn as mkldnn
except (ImportError, AttributeError):
    mkldnn = None

try:
    import tifffile
except ImportError:
    tifffile = None

try:
    import dask
    from dask.distributed import Client, LocalCluster
    from distributed import WorkerPlugin as _WP
except Exception:
    dask = None
    Client = None
    LocalCluster = None
    _WP = None

# Try to import central worker patches helper; not fatal if missing.
try:
    import worker_patches  # type: ignore
except Exception:
    worker_patches = None

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


def get_optimal_n_workers(use_gpu, requested_n_workers, blocksize, model_type="cyto3"):
    """Calculate optimal number of workers based on GPU memory availability.

    When GPU is enabled, this function queries available GPU memory and
    estimates memory requirements per worker to avoid CUDA out-of-memory
    errors. Returns a safe n_workers value that won't exceed GPU memory.

    Parameters
    ----------
    use_gpu : bool
        Whether GPU acceleration is enabled.
    requested_n_workers : int
        The user-requested number of workers.
    blocksize : tuple
        Block size used for processing (Z, Y, X) - this determines memory per worker.
    model_type : str, optional
        The cellpose model type being used (default: "cyto3").

    Returns
    -------
    tuple
        `(n_workers, threads_per_worker)` recommended for the LocalCluster.
    """
    if not use_gpu:
        # CPU mode: no GPU memory constraints; choose a simple threads-per-worker
        threads = max(1, (os.cpu_count() or 1) // max(1, requested_n_workers))
        return (requested_n_workers, threads)

    if torch is None:
        print("Warning: PyTorch not available. Cannot query GPU memory.")
        threads = max(1, (os.cpu_count() or 1) // max(1, requested_n_workers))
        return (requested_n_workers, threads)

    try:
        if not torch.cuda.is_available():
            print("Warning: GPU requested but CUDA is not available. Using CPU.")
            threads = max(1, (os.cpu_count() or 1) // max(1, requested_n_workers))
            return (requested_n_workers, threads)

        # Get GPU memory info (in bytes)
        gpu_count = torch.cuda.device_count()
        if gpu_count == 0:
            print("Warning: No CUDA devices found. Using CPU.")
            threads = max(1, (os.cpu_count() or 1) // max(1, requested_n_workers))
            return (requested_n_workers, threads)

        # Use the first GPU for memory estimation
        gpu_props = torch.cuda.get_device_properties(0)
        total_memory = gpu_props.total_memory  # bytes

        # Get currently available memory
        torch.cuda.empty_cache()
        reserved_memory = torch.cuda.memory_reserved(0)
        allocated_memory = torch.cuda.memory_allocated(0)
        free_memory = total_memory - allocated_memory

        print(f"GPU: {gpu_props.name}")
        print(f"Total GPU memory: {total_memory / (1024**3):.2f} GB")
        print(f"Reserved: {reserved_memory / (1024**3):.2f} GB")
        print(f"Allocated: {allocated_memory / (1024**3):.2f} GB")
        print(f"Free: {free_memory / (1024**3):.2f} GB")

        # Estimate memory per worker
        # CRITICAL: Dask workers execute multiple tasks concurrently!
        # Each task loads its own model copy, so we must account for concurrent execution.

        # Base model memory (approximation for cyto3/nuclei models)
        base_model_memory = 500 * (1024**2)  # ~500 MB for model weights

        # Estimate memory per block based on block dimensions
        # Cellpose processes blocks with intermediate tensors
        # Conservative estimate: 10x the input block size (forward + backward flow + gradients + overhead)
        voxels_per_block = np.prod(blocksize)  # Z*Y*X of block
        bytes_per_voxel = 4  # float32
        memory_multiplier = 10  # very conservative for safety
        block_memory = voxels_per_block * bytes_per_voxel * memory_multiplier

        # Memory per concurrent task (each task loads a full model)
        memory_per_task = base_model_memory + block_memory

        # Reserve part of GPU memory for overhead and fragmentation.
        # Use 50% on low-VRAM GPUs (<=6GB) to be extra conservative,
        # otherwise use 70% (the previous behavior).
        fraction = 0.5 if total_memory <= 6 * (1024**3) else 0.7
        usable_memory = free_memory * fraction
        print(
            f"Using {int(fraction * 100)}% of free GPU memory for tasks (fraction={fraction})"
        )

        # Calculate maximum TOTAL concurrent tasks across all workers
        # This is the hard limit based on GPU memory
        max_total_tasks = int(usable_memory / memory_per_task)
        max_total_tasks = max(1, max_total_tasks)  # At least 1

        # Distribute tasks across workers
        # Each worker needs at least 1 thread, preferably 2-4 for efficiency
        # But we MUST respect GPU memory constraints
        # Conservative: don't create more workers than GPUs (one worker per GPU)
        max_workers = min(requested_n_workers, max_total_tasks, gpu_count)

        # Calculate threads per worker to stay within memory limits
        # This limits Dask's concurrent task execution per worker
        max_threads_per_worker = max(1, max_total_tasks // max_workers)

        # If the GPU has low total memory (e.g. <=6GB) or usable memory is very small
        # compared to the estimated memory per task, be extra conservative: force
        # a single thread per worker and avoid spawning multiple workers per GPU.
        low_vram = total_memory <= 6 * (1024**3) or usable_memory < (
            2 * memory_per_task
        )
        if low_vram:
            print(
                "Low GPU VRAM detected – forcing single-threaded workers and limiting workers to GPUs."
            )
            max_threads_per_worker = 1
            max_workers = min(max_workers, gpu_count)

        print(
            f"Estimated memory per task (including model): {memory_per_task / (1024**2):.2f} MB"
        )
        print(f"Free GPU memory: {free_memory / (1024**2):.2f} MB")
        print(f"Usable GPU memory (70%): {usable_memory / (1024**2):.2f} MB")
        print(f"Maximum total concurrent tasks: {max_total_tasks}")
        print(f"Maximum workers: {max_workers}")
        print(f"Threads per worker (to limit concurrency): {max_threads_per_worker}")

        # Return tuple: (workers, threads_per_worker)
        if requested_n_workers > max_workers:
            print(
                f"Warning: Requested {requested_n_workers} workers but GPU memory "
                f"only supports {max_workers} workers with {max_threads_per_worker} threads each."
            )
            return (max_workers, max_threads_per_worker)
        else:
            print(
                f"Using {requested_n_workers} workers with {max_threads_per_worker} threads each (within GPU memory limits)."
            )
            return (requested_n_workers, max_threads_per_worker)

    except ImportError:
        print(
            "Warning: PyTorch not available for GPU memory detection. Using requested n_workers."
        )
        # Default to 4 threads per worker if we can't check GPU memory
        return (requested_n_workers, 4)
    except Exception as e:
        print(f"Warning: Could not detect GPU memory: {e}. Using requested n_workers.")
        threads = max(1, (os.cpu_count() or 1) // max(1, requested_n_workers))
        return (requested_n_workers, threads)


def validate_runtime_requirements():
    """Validate required Python packages and cellpose helpers.

    Checks that the Python environment exposes the required Python
    modules and that `cellpose.contrib.distributed_segmentation`
    provides the expected helpers. Exits the program with an
    informative message when a requirement is missing.

    Returns
    -------
    module
        The imported `cellpose.contrib.distributed_segmentation` module
        when available.
    """
    missing = []
    info = []

    modules_to_check = ["cellpose", "dask", "distributed", "zarr", "tifffile"]
    for mod_name in modules_to_check:
        try:
            mod = importlib.import_module(mod_name)
            ver = getattr(mod, "__version__", None)
            info.append((mod_name, ver))
        except Exception:
            missing.append(mod_name)

    ds_mod = None
    if "cellpose" not in missing:
        try:
            ds_mod = importlib.import_module(
                "cellpose.contrib.distributed_segmentation"
            )
        except Exception:
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


def parse_blocksize(s):
    """Parse a comma-separated blocksize string into a tuple of ints.

    Also handles concatenated format like `128256256` -> (128, 256, 256).

    Parameters
    ----------
    s : str
        Comma-separated integers, e.g. `"128,256,256"`, or concatenated
        integers like `128256256` (will be split into equal thirds).

    Returns
    -------
    tuple of int
        The parsed blocksize tuple, e.g. `(128, 256, 256)`.
    """
    s = s.strip()
    if "," in s:
        # Normal comma-separated format
        parts = [int(x) for x in s.split(",") if x.strip()]
    elif s.isdigit() and len(s) >= 6:
        # Concatenated format: split into equal thirds
        length = len(s)
        third = length // 3
        parts = [int(s[0:third]), int(s[third : 2 * third]), int(s[2 * third :])]
    else:
        # Fallback: try to parse as single integer (will likely fail downstream)
        parts = [int(s)]
    return tuple(parts)


def _apply_zarr_open_compat(z_module, ds_module=None, reload_ds=True):
    """Patch `zarr.open` to accept a positional `mode` argument.

    Some zarr versions make the `mode` argument keyword-only; older
    callers may call `zarr.open(path, 'w', ...)`. This function wraps
    `zarr.open` to accept a positional mode argument and optionally
    reloads the distributed segmentation module so it picks up the
    patched function.

    Parameters
    ----------
    z_module : module
        The imported `zarr` module to patch.
    ds_module : module, optional
        The `cellpose.contrib.distributed_segmentation` module; if
        provided, its reference to `zarr.open` will also be patched.
    reload_ds : bool, optional
        If True, attempt to reload `ds_module` after patching.

    Returns
    -------
    bool
        True on success, False on error.
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


def _patch_worker_all():
    """Apply worker-side compatibility patches inside each Dask worker.

    Delegates to the central `worker_patches.apply_zarr_patches()` helper
    which performs all needed monkeypatches. Returns True on success.
    """
    try:
        if worker_patches is not None:
            worker_patches.apply_zarr_patches()
        else:
            # best-effort fallback: attempt inline minimal patch
            try:
                import zarr as _zw

                _o = _zw.open

                def _o_compat(*a, **kw):
                    if len(a) >= 2 and isinstance(a[1], str):
                        return _o(store=a[0], mode=a[1], *a[2:], **kw)
                    return _o(*a, **kw)

                _zw.open = _o_compat
            except Exception:
                pass
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
    ds,
    input_zarr,
    write_zarr,
    blocksize,
    model_kwargs,
    eval_kwargs,
    cluster_kwargs,
    args,
    channel_zarrs=None,
):
    """Run `ds.distributed_eval`, attempting a proactively patched cluster.

    Attempts to create a `LocalCluster` and `Client` to patch
    worker environments for compatibility. Falls back to a direct
    call or a subprocess-based fallback when necessary.

    Parameters
    ----------
    ds : module
        The imported `cellpose.contrib.distributed_segmentation`
        module exposing `distributed_eval`.
    input_zarr : zarr.Array or ZarrArrayProxy
        The input zarr array or proxy.
    write_zarr : str
        Path where the stitched Zarr will be written.
    blocksize : sequence of int
        Block size for segmentation.
    model_kwargs : dict
        Model construction keyword arguments.
    eval_kwargs : dict
        Evaluation keyword arguments.
    cluster_kwargs : dict
        Cluster configuration passed through to the cluster constructor.
    args : argparse.Namespace
        Parsed CLI arguments containing runtime options like
    channel_zarrs : list, optional
        List of zarr arrays for each channel (for multi-channel preprocessing).
        `n_workers` and `ncpus`.

    Returns
    -------
    tuple
        A tuple `(out_zarr, boxes)` as returned by
        `ds.distributed_eval`.
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
            from dask.distributed import Client, LocalCluster

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
                        client.register_worker_plugin(
                            _ZarrPatchPlugin(), name="zarr_patches"
                        )
                        print("Registered zarr patch worker plugin")
                    except Exception:
                        pass
                except Exception:
                    pass

                try:
                    res = client.run(_patch_worker_all)
                    print("Applied worker patches via client.run(): %r" % (res,))
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
                import zarr

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
            import zarr

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
        "--write_zarr",
        default=None,
        help="Path to write the output stitched Zarr (default: <output_tif>.zarr)",
    )
    args, unknown_args = parser.parse_known_args()

    blocksize = parse_blocksize(args.blocksize)

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

    # one of --output_tif or --output_dir must be provided
    if not args.output_tif and not args.output_dir:
        parser.error("Either --output_tif or --output_dir must be provided")

    # Validate that the Python environment has the required modules
    ds, cellpose_version = validate_runtime_requirements()

    # import the I/O modules now that we've validated they're present
    # `tifffile` was imported at module top (may be None if unavailable).
    # `zarr` is imported at module top as well; use those variables.
    try:
        importlib.reload(tifffile) if tifffile is not None else None
    except Exception:
        pass
    try:
        importlib.reload(zarr) if zarr is not None else None
    except Exception:
        pass

    # Apply compatibility wrapper early in the main process so that any
    # subsequent cluster creation has the best chance to see the patched
    # zarr.open (we still provide a subprocess fallback below).
    try:
        _apply_zarr_open_compat(zarr, ds, reload_ds=True)
    except Exception:
        pass

    # Note: ZarrArrayProxy was removed as it's not used in the current flow.
    # Worker patching via client.run() is the primary mechanism for
    # ensuring compatibility with zarr indexing and array operations.

    # Prepare input zarr
    tmpdir = None
    channel_zarrs = []  # Will be populated for multi-channel inputs

    if args.input_file:
        im = tifffile.imread(args.input_file)

        # warn about input dtype but accept common integer/float types
        if im.dtype not in ("uint8", "uint16", "uint32", "float32", "float64"):
            print(
                f"Warning: input image dtype is {im.dtype}. This script preserves the dtype in the input Zarr; results may be unexpected."
            )

        print(f"Input shape: {im.shape}, blocksize: {blocksize}")

        # Detect multi-channel input and prepare for preprocessing_steps approach.
        # Prefer explicit 4D layout (Z, C, Y, X) with c_axis=1, but also try to
        # auto-detect a small channel axis in 3D inputs (common with some TIFFs).
        c_axis_pos = None
        if len(im.shape) == 4:
            c_axis_pos = 1
        elif len(im.shape) == 3:
            # Heuristic: treat a small axis (<=4) as channel axis when the other
            # axes are significantly larger (to avoid confusing thin spatial
            # dimensions with channels).
            sizes = im.shape
            candidates = [
                i
                for i, s in enumerate(sizes)
                if s <= 4 and max(sizes[j] for j in range(len(sizes)) if j != i) > 8
            ]
            if candidates:
                c_axis_pos = candidates[0]

        if c_axis_pos is not None and im.shape[c_axis_pos] > 1:
            # Multi-channel data: split into separate per-channel 3D arrays
            num_channels = im.shape[c_axis_pos]
            print(f"Detected {num_channels} channels at axis {c_axis_pos}")
            print(
                "Will use preprocessing_steps to stack channels (cellpose best practice)"
            )

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

            for ch_idx in range(num_channels):
                # Robustly extract channel data regardless of which axis is the channel
                ch_data = np.take(im, ch_idx, axis=c_axis_pos)

                # Create zarr for this channel
                ch_path = os.path.join(parent, f"channel_{ch_idx}.zarr")
                z_ch = zarr.open(
                    ch_path,
                    mode="w",
                    shape=spatial_shape,
                    chunks=zarr_chunks,
                    dtype=im.dtype,
                )
                z_ch[...] = ch_data
                channel_zarrs.append(z_ch)
                print(
                    f"  Channel {ch_idx}: created zarr at {ch_path}, shape {spatial_shape}"
                )

            # Use first channel as input_zarr
            input_zarr = channel_zarrs[0]
            # Ensure blocksize matches the rank of the spatial zarr
            blocksize = zarr_chunks
            print(
                f"Using channel 0 as base input, shape: {input_zarr.shape}, blocksize: {blocksize}"
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
                z = zarr.open(
                    zpath,
                    mode="w",
                    shape=im.shape,
                    chunks=blocksize,
                    dtype=im.dtype,
                )
                z[...] = im
                input_zarr = z
                print(f"Wrote input Zarr to {zpath} (dtype={im.dtype})")
                print(
                    f"Input zarr shape: {input_zarr.shape}, chunks: {input_zarr.chunks}"
                )
                sys.stdout.flush()
            else:
                tmpdir = tempfile.TemporaryDirectory(
                    prefix="distributed_cellpose_tmp_", dir=args.temporary_directory
                )
                zpath = os.path.join(tmpdir.name, "input.zarr")
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

    model_kwargs = (
        {"model_type": args.model, "gpu": args.use_gpu}
        if isinstance(args.model, str)
        else args.model
    )
    # cellpose 3.x uses 'channels' instead of 'chan'/'chan2'
    # channels expects a list: [cytoplasm_channel, nucleus_channel]
    # When using preprocessing_steps, channels refer to axis indices after stacking
    # For multi-channel with preprocessing_steps: channels are stacked on axis 1,
    # so we use [1, 2] for two channels, [2, 1] if reversed, or [1, 0] for single channel
    if len(channel_zarrs) > 1:
        # Multi-channel: after stacking on axis 1, channels are at indices 1, 2, ...
        # Map original chan/chan2 to post-stack indices
        if args.chan2 >= 0:
            # Both channels: map to [1, 2] or [2, 1] based on which is cytoplasm
            channels = [args.chan + 1, args.chan2 + 1]
        else:
            channels = [args.chan + 1, 0]
        print(
            f"Multi-channel mode: channels mapped to {channels} (post-stacking indices)"
        )
    else:
        # Single channel or 3D input: standard channel indexing
        if args.chan2 >= 0:
            channels = [args.chan, args.chan2]
        else:
            channels = [args.chan, 0]

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
        # Enable 3D segmentation
        "do_3D": True,
    }

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

    # Add axis parameters for Cellpose 4.x compatibility
    # In Cellpose 3.x, these parameters are not accepted by model.eval()
    # In Cellpose 4.x, channel_axis and z_axis are supported
    if cellpose_version and cellpose_version.startswith("4"):
        eval_kwargs["z_axis"] = 0  # Z dimension is axis 0
        eval_kwargs["channel_axis"] = (
            1  # Channel dimension is axis 1 (after preprocessing stacks channels)
        )
        print(
            f"Cellpose {cellpose_version} detected: adding z_axis and channel_axis to eval_kwargs"
        )
    else:
        print(
            f"Cellpose {cellpose_version or '3.x'} detected: using preprocessing_steps approach (axis parameters not supported by model.eval())"
        )

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

    # Optimize n_workers and threads_per_worker based on GPU memory availability
    optimal_n_workers, optimal_threads_per_worker = get_optimal_n_workers(
        use_gpu=args.use_gpu,
        requested_n_workers=args.n_workers,
        blocksize=blocksize,
        model_type=args.model if isinstance(args.model, str) else "cyto3",
    )

    # CRITICAL: Set environment variables in main process BEFORE creating cluster
    # These control thread limits for worker processes that will be spawned
    print("\n=== Setting global thread limits in main process ===")
    print(
        f"Workers: {optimal_n_workers}, Threads per worker: {optimal_threads_per_worker}"
    )
    os.environ["DASK_DISTRIBUTED__WORKER__THREADS"] = str(optimal_threads_per_worker)
    os.environ["OMP_NUM_THREADS"] = str(optimal_threads_per_worker)
    os.environ["MKL_NUM_THREADS"] = str(optimal_threads_per_worker)
    os.environ["OPENBLAS_NUM_THREADS"] = str(optimal_threads_per_worker)
    os.environ["NUMEXPR_NUM_THREADS"] = str(optimal_threads_per_worker)
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
        f"Environment variables set - worker threads will be limited to {optimal_threads_per_worker}"
    )
    sys.stdout.flush()

    cluster_kwargs = {
        "n_workers": optimal_n_workers,
        "threads_per_worker": optimal_threads_per_worker,  # Use threads_per_worker instead of ncpus
        # Do not pass 'ncpus' here; newer distributed versions reject it.
        # Increase worker death timeout - GPU workers need more time to release CUDA contexts
        "death_timeout": 60 if args.use_gpu else 15,
        # Ask the cluster to use our safe temporary directory for worker files
        "local_directory": args.temporary_directory,
    }

    # decide where to write the final output TIFF and stitched Zarr
    if args.output_tif:
        output_tif = args.output_tif
        # Enforce _labels suffix in the output filename to clearly indicate segmentation result
        root, ext = os.path.splitext(output_tif)
        # Check for multiple extensions like .ome.tif
        if root.lower().endswith(".ome") and ext.lower() == ".tif":
            root, ext2 = os.path.splitext(root)
            ext = ext2 + ext

        if not root.endswith("_labels") and not root.endswith(".ome_labels"):
            print(f"Enforcing '_labels' suffix on output filename: {output_tif}")
            # If it's an OME-TIFF, we insert .ome_labels
            if ext.lower().endswith(".ome.tif"):
                # strip .ome.tif and append .ome_labels.ome.tif
                root_no_ome = output_tif[: -len(".ome.tif")]
                output_tif = root_no_ome + ".ome_labels.ome.tif"
            else:
                output_tif = f"{root}_labels{ext}"
            print(f"  -> {output_tif}")
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
                                                    start = int(math.floor(float(s.start)))
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
    )

    logger.debug(f"Stitched Zarr written to {write_zarr}")

    # Convert the zarr to a single TIFF file (uint32 labels)
    try:
        arr = out_zarr[...]  # may be large
        arr = arr.astype(np.uint32)
        nbytes = arr.nbytes
        big_threshold = 4 * 1024**3  # 4 GiB

        # Heuristic axes metadata for ImageJ/OME
        axes = None
        if arr.ndim == 2:
            axes = "YX"
        elif arr.ndim == 3:
            axes = "ZYX"
        elif arr.ndim == 4:
            # prefer ZCYX if first dim >= second dim, else CZYX
            axes = "ZCYX" if arr.shape[0] >= arr.shape[1] else "CZYX"

        # Decide writing mode:
        # - If dtype is uint32 (labels) or very large file -> write as OME-TIFF (supports uint32)
        # - For small uint8/uint16 images we can write ImageJ-style TIFF
        try:
            dt = arr.dtype
        except Exception:
            dt = None

        tif_kwargs = {}
        if axes:
            tif_kwargs["metadata"] = {"axes": axes}

        if dt is not None and dt == np.uint32 or nbytes > big_threshold:
            # Use OME-TIFF which supports uint32 and BigTIFF when needed
            tif_kwargs["ome"] = True
            tif_kwargs["bigtiff"] = nbytes > big_threshold
            logger.info(
                f"Writing OME-TIFF (ome=True) dtype={dt}, bigtiff={tif_kwargs['bigtiff']}"
            )
            tifffile.imwrite(output_tif, arr, **tif_kwargs)
            logger.debug("Wrote OME-TIFF output labels to %s", output_tif)
            logger.debug(
                "Stitched Zarr for inspection: %s",
                write_zarr,
            )
        else:
            # Safe to write simple ImageJ-compatible TIFF for small uint8/uint16
            tif_kwargs["imagej"] = True
            tifffile.imwrite(output_tif, arr, **tif_kwargs)
            logger.debug("Wrote output labels to %s", output_tif)
        # Remove intermediate stitched Zarr to avoid leaving large temporary stores
        try:
            if write_zarr and os.path.exists(write_zarr):
                logger.info(f"Retaining intermediate Zarr for debugging: {write_zarr}")
                # try:
                #     if os.path.isdir(write_zarr):
                #         shutil.rmtree(write_zarr, ignore_errors=True)
                #     else:
                #         os.remove(write_zarr)
                #     logger.info("Removed intermediate Zarr: %s", write_zarr)
                # except Exception as _e:
                #     logger.warning(
                #         "Could not remove intermediate Zarr %s: %s", write_zarr, _e
                #     )
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
        from dask.distributed import default_client

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

    # Make sure the process terminates when run as a script (safe because
    # `main()` is only invoked in the __main__ guard). Use sys.exit to set
    # an explicit exit code and avoid leaving lingering non-daemon threads.
    try:
        sys.exit(0)
    except Exception:
        return


if __name__ == "__main__":
    # Ensure multiprocessing method is compatible with Dask/distributed
    if sys.platform != "win32":
        try:
            multiprocessing.set_start_method("spawn", force=True)
        except RuntimeError:
            pass
    multiprocessing.freeze_support()
    main()
