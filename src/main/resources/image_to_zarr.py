#!/usr/bin/env python3
"""Image to Multi-Channel OME-Zarr Converter.

This script converts massive scientific image files (.ims, .czi, .nd2, .lif, etc.)
directly to a multi-channel OME-Zarr volume. It's designed to be used as a
pre-processor for distributed image analysis (like Cellpose) or for efficient
cloud-streaming visualization.

Key Features:
-------------
- Optimized Imaris (.ims) conversion using h5py.
- Support for any Bio-Formats compatible format via BioIO and bioio-bioformats.
- Automatic generation of OME-Zarr multiresolution pyramids.
- Efficient dask-based, chunked data transfer (low RAM footprint).
- OME-NGFF v0.4 compliant metadata (axes, scales, units).

Works on:
---------
2D, 3D, and 4D (Timepoints) multi-channel volumes.

Environment Setup:
------------------
Using Pixi (Recommended):
    pixi init
    pixi add h5py bioio dask zarr numpy
    pixi add --pypi bioio-bioformats bioio-ome-tiff

Using Conda:
    conda create -n image2zarr -c conda-forge h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy
    conda activate image2zarr

Using Pip:
    pip install h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy
"""

import argparse
import contextlib
import gc
import itertools
import os
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List, Optional, Tuple, Union

import dask.array as da
import numpy as np
import zarr

# Detect Zarr version to handle V3 vs V2 differences
ZARR_V3 = zarr.__version__.startswith("3")

try:
    import h5py
except ImportError:
    h5py = None

try:
    from bioio import BioImage
except ImportError:
    BioImage = None

try:
    from tqdm import tqdm
except ImportError:
    tqdm = None

try:
    from dask.diagnostics import ProgressBar
except ImportError:
    ProgressBar = None

try:
    import psutil
except ImportError:
    psutil = None  # type: ignore[assignment]

try:
    from zarr.codecs import BloscCodec, BytesCodec, ShardingCodec
except ImportError:
    BloscCodec = BytesCodec = ShardingCodec = None  # type: ignore[assignment]


def _auto_chunks(dtype: np.dtype, z_size: int = 0) -> Tuple[int, int, int, int]:
    """Compute shard shape based on available system RAM.

    Targets ~10% of free RAM per shard so multiple shards can be buffered
    simultaneously without OOM. Always keeps C=1 (one channel per shard).

    Parameters
    ----------
    dtype : np.dtype
        Array dtype (used to compute bytes per element).
    z_size : int
        Full Z depth of the image. 0 means unknown/2D.

    Returns
    -------
    Tuple[int, int, int, int]
        Optimal (C, Z, Y, X) shard shape, all values are powers of 2.

    Examples
    --------
    >>> import numpy as np
    >>> c, z, y, x = _auto_chunks(np.dtype("uint16"), z_size=64)
    >>> c
    1
    """
    if psutil is not None:
        free_bytes: int = psutil.virtual_memory().available
    else:
        # Fall back to 8 GB when psutil is not installed.
        free_bytes = 8 * 1024**3
        print("  (psutil not found - assuming 8 GB free RAM for shard sizing)")

    bytes_per_elem = np.dtype(dtype).itemsize
    # Target: 10 % of free RAM per shard, hard-capped at 1 GB.
    # Large shards keep the number of per-shard NAS metadata round-trips low.
    target_bytes = min(free_bytes * 0.10, 1 * 1024**3)

    is_2d = z_size <= 1
    if is_2d:
        z_chunk = 1
    else:
        # Largest divisor of z_size that is <= 64.
        # This guarantees Dask can rechunk without misalignment warnings.
        max_z = min(z_size, 64)
        z_chunk = next(
            (d for d in range(max_z, 0, -1) if z_size % d == 0),
            max_z,
        )

    # XY tile: use floor(log2) to stay conservatively under the budget.
    xy_budget = target_bytes / (z_chunk * bytes_per_elem)
    xy_tile = int(2 ** int(np.log2(max(xy_budget**0.5, 1))))
    xy_tile = max(256, min(xy_tile, 8192))  # clamp to [256, 8192]

    print(
        f"  Auto shard size: (1, {z_chunk}, {xy_tile}, {xy_tile}) "
        f"≈ {z_chunk * xy_tile * xy_tile * bytes_per_elem / 1024**2:.0f} MB/shard "
        f"[{free_bytes / 1024**3:.1f} GB free RAM]"
    )
    return (1, z_chunk, xy_tile, xy_tile)


def _write_to_zarr_with_progress(
    dask_arr: da.Array,
    z_arr: Any,
    desc: str = "Writing",
) -> None:
    """Write a dask array to a Zarr array using read-ahead + single write.

    Strategy (optimised for a source and destination on the same NAS):

    * ``_N_READERS`` background threads prefetch the next N shards from the
      source simultaneously, keeping the inbound network link saturated.
    * A **single** write thread flushes each block to the destination
      sequentially after LZ4 bitshuffle compression.  LZ4 typically
      reduces microscopy data by 5-50×, so write bytes are negligible.
    * At most ``_N_READERS + 1`` shards are live in RAM at any time.

    Uses `tqdm` for an ETA progress bar when available, falls back to
    `dask.diagnostics.ProgressBar`, then silent compute.

    Parameters
    ----------
    dask_arr : da.Array
        Source array, already rechunked to the desired shard shape.
    z_arr : Any
        Open Zarr array to write into (created by `_open_shard_array`).
    desc : str, optional
        Label shown in the progress bar, by default ``"Writing"``.
    """
    ndim = dask_arr.ndim
    chunks = dask_arr.chunks  # actual chunk sizes after rechunk

    starts = [
        [sum(chunks[ax][:i]) for i in range(len(chunks[ax]))] for ax in range(ndim)
    ]
    sizes = [list(chunks[ax]) for ax in range(ndim)]

    shard_coords = list(itertools.product(*[range(len(s)) for s in starts]))
    total_shards = len(shard_coords)

    def _slc(coord):
        return tuple(
            slice(starts[ax][coord[ax]], starts[ax][coord[ax]] + sizes[ax][coord[ax]])
            for ax in range(ndim)
        )

    if tqdm is not None:
        pbar_ctx: Any = tqdm(
            total=total_shards,
            unit="shard",
            desc=desc,
            bar_format=(
                "{l_bar}{bar}| {n_fmt}/{total_fmt} shards"
                " [{elapsed}<{remaining}, {rate_fmt}]"
            ),
            dynamic_ncols=True,
        )
    elif ProgressBar is not None:
        pbar_ctx = ProgressBar()
    else:
        pbar_ctx = contextlib.nullcontext()

    # Sequential read in main thread + 1 background writer.
    # Write traffic is negligible after LZ4; keeping reads sequential avoids
    # h5py / BioIO thread-safety issues with concurrent access.
    with pbar_ctx as pbar, ThreadPoolExecutor(max_workers=1) as write_exec:
        write_future = None
        for coord in shard_coords:
            slc = _slc(coord)
            block = dask_arr[slc].compute()
            if write_future is not None:
                write_future.result()
                gc.collect()
                if tqdm is not None and pbar is not None:
                    pbar.update(1)
            write_future = write_exec.submit(z_arr.__setitem__, slc, block)
            del block
        if write_future is not None:
            write_future.result()
            gc.collect()
            if tqdm is not None and pbar is not None:
                pbar.update(1)


def _clamp_chunks(chunks: Tuple[int, ...], shape: Tuple[int, ...]) -> Tuple[int, ...]:
    """Clamp each chunk dimension so it does not exceed the array shape.

    Parameters
    ----------
    chunks : Tuple[int, ...]
        Requested chunk sizes.
    shape : Tuple[int, ...]
        Actual array shape.

    Returns
    -------
    Tuple[int, ...]
        Chunk sizes guaranteed to fit within *shape*.
    """
    return tuple(min(c, s) for c, s in zip(chunks, shape))


def _open_zarr_root(output_path: str) -> Any:
    """Open a new writable Zarr group at *output_path*.

    Parameters
    ----------
    output_path : str
        Filesystem path for the root Zarr store.

    Returns
    -------
    Any
        Open :class:`zarr.Group` in write mode.
    """
    kwargs: Dict[str, Any] = {"mode": "w"}
    if ZARR_V3:
        kwargs["zarr_format"] = 3
    return zarr.open_group(output_path, **kwargs)


def _resolve_chunks(
    arr: da.Array,
    target_chunks: Optional[Tuple[int, ...]],
) -> Tuple[da.Array, Tuple[int, ...]]:
    """Rechunk *arr* and return the final chunk shape.

    If *target_chunks* is given, clamp it to the array shape and use it.
    Otherwise call :func:`_auto_chunks` to pick a RAM-aware shard size.

    Parameters
    ----------
    arr : da.Array
        Source Dask array (at least 4-D, CZYX order).
    target_chunks : Optional[Tuple[int, ...]]
        Explicit shard shape, or ``None`` for automatic sizing.

    Returns
    -------
    Tuple[da.Array, Tuple[int, ...]]
        ``(rechunked_arr, final_chunks)``
    """
    if target_chunks is not None:
        chunks = _clamp_chunks(target_chunks, arr.shape)
    else:
        z_size = arr.shape[1] if arr.ndim >= 4 else 1
        chunks = _clamp_chunks(_auto_chunks(arr.dtype, z_size=z_size), arr.shape)
    return arr.rechunk(chunks), chunks


def _open_shard_array(
    out_dir: str,
    shape: Tuple[int, ...],
    chunks: Tuple[int, ...],
    dtype: np.dtype,
) -> Any:
    """Create a Zarr array with V3 sharding and LZ4 bitshuffle compression.

    The shard grid equals *chunks*.  Each inner chunk is ``(1, 1, 256, 256)``
    so Cellpose workers load the smallest useful region without reading an
    entire shard file.  Falls back to plain Zarr V2 chunks when
    `zarr.codecs` is unavailable.

    Parameters
    ----------
    out_dir : str
        Directory path for the Zarr array (created if absent).
    shape : Tuple[int, ...]
        Full array shape ``(C, Z, Y, X)``.
    chunks : Tuple[int, ...]
        Shard (outer chunk) shape ``(C, Z, Y, X)``.
    dtype : np.dtype
        Array element type.

    Returns
    -------
    Any
        Open `zarr.Array` ready to receive data.
    """
    os.makedirs(out_dir, exist_ok=True)
    kwargs: Dict[str, Any] = {
        "mode": "w",
        "shape": shape,
        "chunks": chunks,
        "dtype": dtype,
    }
    # ShardingCodec is available when zarr >= 3 and zarr.codecs was imported.
    if ZARR_V3 and ShardingCodec is not None:
        c, z, y, x = chunks
        inner = (1, min(z, 1), min(y, 256), min(x, 256))
        kwargs["codecs"] = [
            ShardingCodec(
                chunk_shape=inner,
                codecs=[
                    BytesCodec(),
                    BloscCodec(cname="lz4", clevel=1, shuffle="bitshuffle"),
                ],
            )
        ]
        print(f"    shard={chunks}  inner={inner}  codec=lz4")
    return zarr.open_array(out_dir, **kwargs)


def _write_omengff_metadata(
    root: Any,
    datasets: List[Dict],
    pz: float,
    py: float,
    px: float,
) -> None:
    """Write OME-NGFF v0.4 ``multiscales`` metadata to a Zarr group root.

    Parameters
    ----------
    root : Any
        Open `zarr.Group` at the OME-Zarr root.
    datasets : List[Dict]
        List of dataset dicts with ``path`` and
        ``coordinateTransformations`` keys.
    pz : float
        Z pixel size in micrometres.
    py : float
        Y pixel size in micrometres.
    px : float
        X pixel size in micrometres.
    """
    new_attrs = {
        "multiscales": [
            {
                "version": "0.4",
                "datasets": datasets,
                "axes": [
                    {"name": "c", "type": "channel"},
                    {"name": "z", "type": "space", "unit": "micrometer"},
                    {"name": "y", "type": "space", "unit": "micrometer"},
                    {"name": "x", "type": "space", "unit": "micrometer"},
                ],
                "type": "gaussian",
            }
        ],
        "pixel_size": [1.0, pz, py, px],
    }
    if ZARR_V3 and hasattr(root, "update_attributes"):
        # zarr v3: __setitem__ on attrs does not flush to zarr.json.
        # update_attributes() is the correct persistent write API.
        root.update_attributes(new_attrs)
    else:
        # zarr v2: direct assignment works fine.
        root.attrs["multiscales"] = new_attrs["multiscales"]
        root.attrs["pixel_size"] = new_attrs["pixel_size"]


def _write_ims_shards(
    h5_datasets: List[Any],
    z_arr: Any,
    shape: Tuple[int, ...],
    chunks: Tuple[int, ...],
    desc: str = "Writing",
) -> None:
    """Write IMS channel data to Zarr using pipelined h5py reads and writes.

    Pipeline (eliminates NAS idle gaps):

    1. A single **read thread** owns its own h5py file handle and reads
       shard N+1 while the main thread submits the write for shard N.
    2. A single **write thread** compresses (LZ4) and writes to Zarr.
    3. The main thread just coordinates: get prefetch result → submit write
       → submit next prefetch → repeat.

    Because the read thread uses its own file handle (opened once, lazily,
    inside the thread), concurrent access to the h5py file is safe.

    Parameters
    ----------
    h5_datasets : List[Any]
        One open h5py Dataset per channel, all with shape ``(Z, Y, X)``.
    z_arr : Any
        Open Zarr array to write into, shape ``(C, Z, Y, X)``.
    shape : Tuple[int, ...]
        Full ``(C, Z, Y, X)`` shape.
    chunks : Tuple[int, ...]
        Shard ``(C, Z, Y, X)`` shape.
    desc : str, optional
        Progress-bar label.
    """
    _, Z, Y, X = shape
    _, z_ch, y_ch, x_ch = chunks

    shard_coords = list(
        itertools.product(
            range(0, Z, z_ch),
            range(0, Y, y_ch),
            range(0, X, x_ch),
        )
    )
    total = len(shard_coords)

    # Extract file path and in-file dataset names so the read thread can
    # open its own handle (h5py datasets are not concurrency-safe across
    # threads opened from the same file object).
    input_path: str = h5_datasets[0].file.filename
    ds_names: List[str] = [ds.name for ds in h5_datasets]

    # Thread-local file handle: opened once inside the read thread and
    # reused for all shards — avoids per-shard open overhead.
    _read_handle: List[Any] = [None]
    _read_dsets: List[Any] = [None]

    def _read_shard(z0: int, y0: int, x0: int) -> Tuple[np.ndarray, tuple]:
        """Open (once) and read one shard from the thread-local handle."""
        if _read_handle[0] is None:
            _read_handle[0] = h5py.File(  # type: ignore[index]
                input_path,
                "r",
                rdcc_nbytes=512 * 1024**2,
                rdcc_nslots=100003,
            )
            _read_dsets[0] = [_read_handle[0][n] for n in ds_names]
        z1 = min(z0 + z_ch, Z)
        y1 = min(y0 + y_ch, Y)
        x1 = min(x0 + x_ch, X)
        block = np.stack([ds[z0:z1, y0:y1, x0:x1] for ds in _read_dsets[0]])
        slc = (slice(None), slice(z0, z1), slice(y0, y1), slice(x0, x1))
        return block, slc

    if tqdm is not None:
        pbar_ctx: Any = tqdm(
            total=total,
            unit="shard",
            desc=desc,
            bar_format=(
                "{l_bar}{bar}| {n_fmt}/{total_fmt} shards"
                " [{elapsed}<{remaining}, {rate_fmt}]"
            ),
            dynamic_ncols=True,
        )
    elif ProgressBar is not None:
        pbar_ctx = ProgressBar()
    else:
        pbar_ctx = contextlib.nullcontext()

    with (
        pbar_ctx as pbar,
        ThreadPoolExecutor(max_workers=1) as read_exec,
        ThreadPoolExecutor(max_workers=1) as write_exec,
    ):
        # Seed the pipeline: start reading shard 0 immediately.
        head = 0
        read_future = read_exec.submit(_read_shard, *shard_coords[head])
        head += 1

        write_future = None
        for _ in shard_coords:
            # Collect the prefetched block (usually already done).
            block, slc = read_future.result()

            # Immediately kick off the next read so the NAS stays busy.
            if head < total:
                read_future = read_exec.submit(_read_shard, *shard_coords[head])
                head += 1

            # Wait for the previous write (near-instant after LZ4).
            if write_future is not None:
                write_future.result()
                gc.collect()
                if tqdm is not None and pbar is not None:
                    pbar.update(1)

            write_future = write_exec.submit(z_arr.__setitem__, slc, block)
            del block

        # Drain the final write.
        if write_future is not None:
            write_future.result()
            gc.collect()
            if tqdm is not None and pbar is not None:
                pbar.update(1)

        # Close the read thread's file handle.
        if _read_handle[0] is not None:
            try:
                _read_handle[0].close()
            except Exception:
                pass


def get_ims_metadata(
    f: Any, shape_zyx: Tuple[int, ...]
) -> Tuple[Optional[float], Optional[Tuple[float, float, float]]]:
    """Extract anisotropy and pixel sizes from Imaris file metadata.

    Parameters
    ----------
    f : Any
        Open h5py file handle.
    shape_zyx : Tuple[int, ...]
        Shape of the resolution level 0 data (Z, Y, X).

    Returns
    -------
    Tuple[Optional[float], Optional[Tuple[float, float, float]]]
        - anisotropy: (Z/XY ratio)
        - pixel_sizes: (pz, py, px) in micrometers
    """
    try:
        info = f["/DataSetInfo/Image"]

        def _get_attr(name: str) -> float:
            val = info.attrs.get(name)
            if isinstance(val, (np.ndarray, list, tuple)):
                val = val[0]
            if isinstance(val, bytes):
                val = val.decode("utf-8")
            return float(val)

        pz = (_get_attr("ExtMax2") - _get_attr("ExtMin2")) / shape_zyx[0]
        py = (_get_attr("ExtMax1") - _get_attr("ExtMin1")) / shape_zyx[1]
        px = (_get_attr("ExtMax0") - _get_attr("ExtMin0")) / shape_zyx[2]

        return pz / px, (pz, py, px)
    except Exception:
        return None, None


def convert_ims_to_zarr(
    input_path: str,
    output_path: str,
    timepoint: int = 1,
    target_chunks: Optional[Tuple[int, ...]] = None,
    include_pyramid: bool = True,
) -> bool:
    """Convert all channels of an Imaris file to Zarr using h5py.

    Optionally preserves lower resolution levels as OME-Zarr multiscales.

    Parameters
    ----------
    input_path : str
        Path to the source Imaris (.ims) file.
    output_path : str
        Directory to create for the .zarr metadata.
    timepoint : int, default 1
        Index of the timepoint to convert (1-based).
    target_chunks : Optional[Tuple[int, ...]], default None
        Specify the chunk structure for the resulting Zarr arrays (C, Z, Y, X).
    include_pyramid : bool, default True
        If True, copies existing resolution levels from the Imaris file.

    Returns
    -------
    bool
        True if the conversion was successful, False otherwise.
    """
    if h5py is None:
        print("Error: h5py is required for Imaris conversion.")
        return False

    try:
        print(f"Opening Imaris file with h5py: {input_path}")
        start_time = time.time()
        # Large chunk cache reduces SMB round-trips: HDF5 sub-chunks are
        # fetched in bigger batches rather than one request per chunk.
        f = h5py.File(
            input_path,
            "r",
            rdcc_nbytes=512 * 1024**2,  # 512 MB cache
            rdcc_nslots=100003,  # prime keeps cache hash efficient
        )
        t_idx = timepoint - 1
        t_key = f"TimePoint {t_idx}"

        root = _open_zarr_root(output_path)

        # Find all resolution levels
        res_keys = sorted(
            [k for k in f["/DataSet"].keys() if k.startswith("ResolutionLevel ")],
            key=lambda x: int(x.split()[-1]),
        )

        if not include_pyramid:
            res_keys = [res_keys[0]]

        datasets = []
        shape0 = None
        pz, py, px = 1.0, 1.0, 1.0  # Base pixel sizes

        for res_level, res_key in enumerate(res_keys):
            res_group = f[f"/DataSet/{res_key}"]
            if t_key not in res_group:
                print(f"  Skipping {res_key}, {t_key} not found.")
                continue

            # Find all channel keys for this timepoint
            c_keys = sorted(
                [k for k in res_group[t_key].keys() if k.startswith("Channel ")],
                key=lambda x: int(x.split()[-1]),
            )

            if not c_keys:
                print(f"  Skipping {res_key}, no channels found.")
                continue

            # Collect raw h5py datasets (one per channel).
            h5_datasets = [res_group[t_key][c_key]["Data"] for c_key in c_keys]
            sample_ds = h5_datasets[0]
            czyx_shape = (len(h5_datasets), *sample_ds.shape)

            # Use a tiny dummy dask array just to resolve the chunk size.
            dummy = da.empty(czyx_shape, dtype=sample_ds.dtype)
            _, final_chunks = _resolve_chunks(dummy, target_chunks)

            print(f"  Level {res_level}: shape={czyx_shape}")
            z_arr = _open_shard_array(
                os.path.join(output_path, str(res_level)),
                czyx_shape,
                final_chunks,
                sample_ds.dtype,
            )
            _write_ims_shards(
                h5_datasets,
                z_arr,
                czyx_shape,
                final_chunks,
                desc=f"Level {res_level}",
            )

            # Metadata tracking for level compatibility
            if res_level == 0:
                shape0 = czyx_shape[1:]  # ZYX
                anisotropy_base, pixel_sizes_base = get_ims_metadata(f, shape0)
                if pixel_sizes_base:
                    pz, py, px = pixel_sizes_base

            scale = [
                1.0,
                pz * (shape0[0] / czyx_shape[1]),
                py * (shape0[1] / czyx_shape[2]),
                px * (shape0[2] / czyx_shape[3]),
            ]
            datasets.append(
                {
                    "path": str(res_level),
                    "coordinateTransformations": [{"type": "scale", "scale": scale}],
                }
            )

        _write_omengff_metadata(root, datasets, pz, py, px)

        print(f"Conversion done in {time.time() - start_time:.1f}s")
        return True
    except Exception as e:
        print(f"Imaris conversion failed: {e}")
        traceback.print_exc()
        return False


def convert_bioio_to_zarr(
    input_path: str,
    output_path: str,
    timepoint: int = 1,
    target_chunks: Optional[Tuple[int, ...]] = None,
    include_pyramid: bool = True,
) -> bool:
    """Convert all channels of any Bio-Formats file to Zarr using BioIO.

    Generates pyramidal resolutions if target is a high-resolution 2D/3D file.

    Parameters
    ----------
    input_path : str
        Path to the source image file readable by Bio-Formats.
    output_path : str
        Directory to create for the .zarr metadata.
    timepoint : int, default 1
        Index of the timepoint to convert (1-based).
    target_chunks : Optional[Tuple[int, ...]], default None
        Specify the chunk structure for the resulting Zarr arrays (C, Z, Y, X).
    include_pyramid : bool, default True
        If True, downsamples the image to create multiresolution levels.

    Returns
    -------
    bool
        True if the conversion was successful, False otherwise.
    """
    if BioImage is None:
        print("Error: BioIO is required for this file format.")
        return False

    try:
        print(f"Opening file with BioIO: {input_path}")
        start_time = time.time()
        img = BioImage(input_path)
        data = img.data  # Usually (T, C, Z, Y, X)
        order = list(img.dims.order)

        # Slice timepoint if T exists
        slc: List[Union[slice, int]] = [slice(None)] * data.ndim
        if "T" in order:
            t_idx = order.index("T")
            slc[t_idx] = timepoint - 1

        d_sliced = data[tuple(slc)]
        remaining_order = "".join(
            [o for i, o in enumerate(order) if not isinstance(slc[i], int)]
        )

        # Standardize to (C, Z, Y, X)
        target_order = "CZYX"
        if remaining_order != target_order:
            current_pos = {o: i for i, o in enumerate(remaining_order)}
            new_axes = [current_pos.get(ax) for ax in target_order if ax in current_pos]
            d_sliced = d_sliced.transpose(*new_axes)

        while d_sliced.ndim < 4:
            d_sliced = d_sliced[np.newaxis, ...]

        root = _open_zarr_root(output_path)

        datasets = []
        pz, py, px = 1.0, 1.0, 1.0
        try:
            pp = img.physical_pixel_sizes
            pz, py, px = pp.Z or 1.0, pp.Y or 1.0, pp.X or 1.0
            print(f"  Detected calibration: {pz:.4f}, {py:.4f}, {px:.4f} um")
        except Exception:
            pass

        max_dim = max(d_sliced.shape[-2], d_sliced.shape[-1])
        num_levels = 1
        if include_pyramid:
            if max_dim > 2048:
                num_levels = int(np.log2(max_dim / 256)) + 1
                num_levels = max(1, min(6, num_levels))

        for level in range(num_levels):
            # Scale data (very basic downsampling)
            if level == 0:
                level_data = d_sliced
            else:
                level_data = da.coarsen(
                    np.mean,
                    level_data,
                    {level_data.ndim - 2: 2, level_data.ndim - 1: 2},
                    trim_excess=True,
                ).astype(d_sliced.dtype)

            print(f"  Writing Level {level}...")

            level_data, level_chunks = _resolve_chunks(level_data, target_chunks)

            print(f"  Level {level}: shape={level_data.shape}")
            z_arr = _open_shard_array(
                os.path.join(output_path, str(level)),
                level_data.shape,
                level_chunks,
                level_data.dtype,
            )
            _write_to_zarr_with_progress(level_data, z_arr, desc=f"Level {level}")

            # Metadata tracking
            scale = [1.0, pz, py * (2**level), px * (2**level)]
            datasets.append(
                {
                    "path": str(level),
                    "coordinateTransformations": [{"type": "scale", "scale": scale}],
                }
            )

        _write_omengff_metadata(root, datasets, pz, py, px)

        print(f"Conversion done in {time.time() - start_time:.1f}s")
        return True
    except Exception as e:
        print(f"BioIO conversion failed: {e}")
        traceback.print_exc()
        return False


def main() -> None:
    """Parse arguments and initiate image to OME-Zarr conversion."""
    parser = argparse.ArgumentParser(description="Multi-Channel OME-Zarr Converter")
    parser.add_argument("--input", "-i", required=True, help="Input image file")
    parser.add_argument("--output", "-o", help="Output .zarr directory (optional)")
    parser.add_argument("--timepoint", type=int, default=1, help="Timepoint (1-based)")
    parser.add_argument(
        "--chunks",
        default="auto",
        help=(
            "Shard size as C,Z,Y,X (e.g. 1,60,2048,2048) or 'auto' to"
            " size from available RAM (default)."
        ),
    )
    parser.add_argument("--pyramid", action="store_true", default=True)
    parser.add_argument("--no-pyramid", dest="pyramid", action="store_false")

    args = parser.parse_args()

    # Handle optional output path
    if not args.output:
        base_dir = os.path.dirname(os.path.abspath(args.input))
        base_name = os.path.splitext(os.path.basename(args.input))[0]
        args.output = os.path.join(base_dir, f"{base_name}.zarr")
    else:
        # If output is an existing directory but does not end with .zarr,
        # we append the input basename to it.
        if os.path.isdir(args.output) and not args.output.lower().endswith(".zarr"):
            base_name = os.path.splitext(os.path.basename(args.input))[0]
            args.output = os.path.join(args.output, f"{base_name}.zarr")
        elif not args.output.lower().endswith(".zarr"):
            # Ensure it ends with .zarr if it's a new path
            args.output += ".zarr"

    target_chunks = None
    if args.chunks and args.chunks.lower() != "auto":
        try:
            target_chunks = tuple(int(x) for x in args.chunks.split(","))
        except ValueError:
            print(f"Error parsing chunks: {args.chunks}")
            sys.exit(1)

    if os.path.exists(args.output) and any(
        os.path.exists(os.path.join(args.output, m))
        for m in (".zgroup", ".zarray", "zarr.json")
    ):
        print(f"Skipping: Zarr already exists at {args.output}")
        sys.exit(0)

    ext = os.path.splitext(args.input.rstrip("/"))[1].lower()

    ok = False
    if ext == ".ims" and h5py is not None:
        ok = convert_ims_to_zarr(
            args.input, args.output, args.timepoint, target_chunks, args.pyramid
        )

    if not ok:
        if BioImage is None:
            print("Error: bioio is required for non-IMS files.")
            print("Install with: pip install bioio bioio-bioformats")
            sys.exit(1)
        ok = convert_bioio_to_zarr(
            args.input, args.output, args.timepoint, target_chunks, args.pyramid
        )

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
