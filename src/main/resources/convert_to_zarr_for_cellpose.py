#!/usr/bin/env python
"""
Convert any Bio-Formats-readable image to a pyramidal OME-Zarr array suitable
for distributed Cellpose segmentation.

The output is an OME-Zarr 0.4 multiscales group:  output.zarr/0/, /1/, …
For generic inputs, each level is downsampled 2× in the spatial axes only
(channel axis preserved). For Imaris ``.ims`` inputs, the native IMS pyramid
geometry is preserved exactly.
Physical pixel sizes are stored both in the OME-Zarr multiscales metadata and
as a top-level ``physical_pixel_sizes_um`` attribute so that downstream tools
can convert µm diameters to pixel diameters without parsing the full multiscales
spec.

Axes convention used throughout this pipeline: (Z, Y, X) or (Z, Y, X, C).
This is channel-last, which differs from canonical OME-Zarr (TCZYX), but is
kept for consistency with distributed_cellpose_run.py.

Supported inputs:
    * Any Bio-Formats file (CZI, LIF, ND2, …) — requires bioio + bioio-bioformats
    * TIFF / OME-TIFF — requires bioio-tifffile
    * Imaris .ims (HDF5) — read directly with h5py (no Bio-Formats needed)
    * Folder of 2-D TIFF files — assembled as a Z-stack in natural sorted order

Dependencies (install in your conda/venv environment):
    bioio
    bioio-bioformats   (for CZI, ND2, LIF, etc.)
    bioio-tifffile     (for TIFF / OME-TIFF)
    zarr
    numpy
    scikit-image       (already installed as a cellpose dependency)
    h5py               (for Imaris .ims files; optional — only needed for IMS)
"""

import argparse
import logging
import os
import sys

import numpy as np
import zarr

# Configure logging to output to stdout for the wrapper to capture
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger(__name__)


def _simplify_array(data):
    """Drop trailing channel dim when C=1 → store as (Z,Y,X) not (Z,Y,X,1)."""
    if data.ndim == 4 and data.shape[-1] == 1:
        return data[..., 0]
    return data


def parse_args():
    p = argparse.ArgumentParser(
        description="Convert an image file to pyramidal OME-Zarr for distributed Cellpose"
    )
    p.add_argument(
        "--input_path",
        required=True,
        help=(
            "Path to the input image. Accepts: "
            "(1) any Bio-Formats file (CZI, ND2, LIF, TIFF, …); "
            "(2) an Imaris .ims file (read directly via h5py); "
            "(3) a folder of 2-D TIFF files assembled as a Z-stack."
        ),
    )
    p.add_argument(
        "--output_zarr",
        required=True,
        help="Path for the output Zarr store (directory)",
    )
    p.add_argument(
        "--chunks",
        default="auto",
        help=(
            "Chunk sizes for level 0 as 'x'-separated ZxYxX (e.g. '64x128x128'). "
            "If 'auto' (default), values are optimized for Cellpose distributed "
            "segmentation and network-drive performance (Z=64, YX=1024)."
        ),
    )
    p.add_argument(
        "--n_levels",
        default="auto",
        help=(
            "Number of pyramid resolution levels (including level 0). "
            "If 'auto' (default), it matches the input file's original pyramid depth. "
            "If the input is not pyramidal, it defaults to 4 levels."
        ),
    )
    # Optional pixel-size overrides (take priority over bioio-detected values)
    p.add_argument(
        "--pixel_size_x_um",
        type=float,
        default=None,
        help="Physical pixel size along X in µm (overrides file metadata)",
    )
    p.add_argument(
        "--pixel_size_y_um",
        type=float,
        default=None,
        help="Physical pixel size along Y in µm (overrides file metadata)",
    )
    p.add_argument(
        "--pixel_size_z_um",
        type=float,
        default=None,
        help="Physical pixel size along Z in µm (overrides file metadata)",
    )
    return p.parse_args()


# ---------------------------------------------------------------------------
# Image reading — helpers
# ---------------------------------------------------------------------------


def _ij_unit_to_micron(unit):
    """Return the scale factor that converts *unit* to µm."""
    u = (unit or "").lower().strip()
    if u in ("um", "\u00b5m", "micrometer", "micron"):
        return 1.0
    if u in ("nm", "nanometer"):
        return 1e-3
    if u in ("mm", "millimeter"):
        return 1e3
    if u in ("cm", "centimeter"):
        return 1e4
    if u in ("m", "meter"):
        return 1e6
    return 1.0  # unknown unit — assume already µm


def _tiff_pixel_sizes(tif):
    """
    Best-effort extraction of XY (and Z) pixel sizes in µm from an open TiffFile.
    Returns ``(px_x, px_y, px_z)``; any element may be ``None`` if not found.
    """
    try:
        # OME-TIFF — PhysicalSizeX/Y/Z are in µm by default
        if tif.is_ome and tif.ome_metadata:
            from xml.etree import ElementTree as ET

            root = ET.fromstring(tif.ome_metadata)
            ns = root.tag.split("}")[0].lstrip("{")
            ns_prefix = f"{{{ns}}}" if ns else ""
            pixels = root.find(f".//{ns_prefix}Pixels")
            if pixels is not None:

                def _pv(attr):
                    v = pixels.get(attr)
                    return float(v) if v else None

                return _pv("PhysicalSizeX"), _pv("PhysicalSizeY"), _pv("PhysicalSizeZ")

        # ImageJ TIFF — resolution stored in XResolution / YResolution tags
        if tif.is_imagej and tif.imagej_metadata:
            ij = tif.imagej_metadata or {}
            factor = _ij_unit_to_micron(ij.get("unit", ""))
            page = tif.pages[0]
            px_x = px_y = px_z = None
            for tag_name, is_x in (("XResolution", True), ("YResolution", False)):
                tag = page.tags.get(tag_name)
                if tag:
                    val = tag.value
                    r = (
                        (val[0] / val[1])
                        if (isinstance(val, tuple) and val[1])
                        else float(val)
                    )
                    if r > 0:
                        if is_x:
                            px_x = factor / r
                        else:
                            px_y = factor / r
            if "spacing" in ij:
                px_z = float(ij["spacing"]) * factor
            return px_x, px_y, px_z
    except Exception as exc:
        print(f"  WARNING: could not read pixel sizes from TIFF metadata: {exc}")
    return None, None, None


def _read_tiff_folder(folder_path):
    """
    Read all TIFF files in *folder_path* and assemble them as a Z-stack.

    Files are sorted by natural-sort order (numbers in filenames are compared
    numerically). Each file must be a 2-D image (one Z-plane). Files are
    stacked along axis 0 → output shape (Z, Y, X) or (Z, Y, X, C).

    Pixel sizes are read from the first file's OME-XML or ImageJ metadata.
    """
    import re

    try:
        import tifffile
    except ImportError:
        sys.exit("tifffile is not installed. Install it with:  pip install tifffile")

    def _natural_key(s):
        return [
            int(tok) if tok.isdigit() else tok.lower() for tok in re.split(r"(\d+)", s)
        ]

    tiff_files = sorted(
        [f for f in os.listdir(folder_path) if f.lower().endswith((".tif", ".tiff"))],
        key=_natural_key,
    )
    if not tiff_files:
        sys.exit(f"No TIFF files found in folder: {folder_path}")

    print(f"Found {len(tiff_files)} TIFF file(s); assembling as Z-stack.")

    px_x = px_y = px_z = None
    slabs = []

    for fname in tiff_files:
        fpath = os.path.join(folder_path, fname)
        with tifffile.TiffFile(fpath) as tif:
            arr = tif.asarray()
            if px_y is None:  # harvest once from the first file
                px_x, px_y, px_z = _tiff_pixel_sizes(tif)
        if arr.ndim == 2:
            arr = arr[np.newaxis]  # (Y, X) → (1, Y, X)
        slabs.append(arr)

    data = np.concatenate(slabs, axis=0)
    print(f"Assembled shape: {data.shape}  dtype: {data.dtype}")

    class _PPS:
        X = px_x
        Y = px_y
        Z = px_z

    return data, _PPS()


def _read_ims(path):
    """
    Read an Imaris ``.ims`` (HDF5) file using ``h5py``.

    Reads the full-resolution level (``ResolutionLevel 0``) at ``TimePoint 0``.
    All channels are stacked channel-last → (Z, Y, X, C); single-channel data
    is returned as (Z, Y, X).

    Physical pixel sizes are extracted from ``/DataSetInfo/Image`` attributes
    (stored as UTF-8 strings giving physical extents in µm):
        pixel_size_x = (ExtMax0 - ExtMin0) / X
        pixel_size_z = (ExtMax2 - ExtMin2) / Z
    """
    try:
        import h5py
    except ImportError:
        sys.exit(
            "h5py is not installed. Install it with:\n"
            "  pip install h5py\n"
            "or add 'h5py' to your conda environment."
        )

    def _attr_str(attrs, key):
        """Decode an HDF5 attribute to a plain Python string robustly."""
        v = attrs.get(key)
        if v is None:
            return None
        if isinstance(v, np.ndarray):
            v = v.flat[0]
        if isinstance(v, (bytes, np.bytes_)):
            return v.decode("utf-8", errors="replace").strip()
        if isinstance(v, (list, tuple)):
            try:
                return bytes(v).decode("utf-8", errors="replace").strip()
            except (TypeError, ValueError):
                pass
        return str(v).strip()

    with h5py.File(path, "r") as f:
        img_attrs = f["/DataSetInfo/Image"].attrs

        def _f(key):
            s = _attr_str(img_attrs, key)
            try:
                return float(s) if s else None
            except ValueError:
                return None

        nx, ny, nz = int(_f("X") or 0), int(_f("Y") or 0), int(_f("Z") or 0)
        # ExtMin/Max0→X, 1→Y, 2→Z (physical extents in µm)
        ex_min, ex_max = _f("ExtMin0") or 0.0, _f("ExtMax0") or float(nx)
        ey_min, ey_max = _f("ExtMin1") or 0.0, _f("ExtMax1") or float(ny)
        ez_min, ez_max = _f("ExtMin2") or 0.0, _f("ExtMax2") or float(nz)

        px_x = (ex_max - ex_min) / nx if nx > 0 else None
        px_y = (ey_max - ey_min) / ny if ny > 0 else None
        px_z = (ez_max - ez_min) / nz if nz > 0 else None

        tp_root = f["/DataSet/ResolutionLevel 0/TimePoint 0"]
        ch_keys = sorted(
            [k for k in tp_root.keys() if k.startswith("Channel ")],
            key=lambda k: int(k.split()[-1]),
        )
        channels = [tp_root[ch]["Data"][...] for ch in ch_keys]  # each (Z, Y, X)

    data = channels[0] if len(channels) == 1 else np.stack(channels, axis=-1)
    print(f"IMS shape: {data.shape}  dtype: {data.dtype}")
    print(f"IMS pixel sizes (µm): X={px_x}, Y={px_y}, Z={px_z}")

    class _PPS:
        X = px_x
        Y = px_y
        Z = px_z

    return data, _PPS()


# ---------------------------------------------------------------------------
# Image reading — dispatcher
# ---------------------------------------------------------------------------


def _read_image(input_path):
    """
    Dispatch to the appropriate reader.

    * Directory   → :func:`_read_tiff_folder` (Z-stack from TIFF files)
    * ``*.ims``   → :func:`_read_ims` (Imaris HDF5 via h5py)
    * Otherwise   → bioio (Bio-Formats, OME-TIFF, plain TIFF, …)

    Returns ``(ndarray ZYX-or-ZYXC, pps)`` where ``pps`` exposes ``.X``,
    ``.Y``, ``.Z`` physical pixel sizes in µm (or ``None`` if unavailable).
    """
    if os.path.isdir(input_path):
        print(f"Input is a directory — reading TIFF folder: {input_path}")
        return _read_tiff_folder(input_path)

    if input_path.lower().endswith(".ims"):
        print("Input is an Imaris .ims file — using h5py reader.")
        return _read_ims(input_path)

    # Generic path: Bio-Formats / OME-TIFF / plain TIFF via bioio
    try:
        from bioio import BioImage
    except ImportError:
        sys.exit(
            "bioio is not installed. Please install it with:\n"
            "  pip install bioio bioio-bioformats bioio-tifffile"
        )

    img = BioImage(input_path)
    pps = img.physical_pixel_sizes  # PhysicalPixelSizes(Z, Y, X)

    # Check if this is an IMS file - bioio often fails to get correct scaling for IMS
    # but the custom HDF5 reader we have is better at geometry.
    # However, if user says h5py is failing, we let BioImage try first.

    try:
        data = img.get_image_dask_data("TZYXC", T=0)  # (1-or-T, Z, Y, X, C)
    except AttributeError:
        data = img.get_image_data("TZYXC", T=0)

    if data.ndim == 5 and data.shape[0] == 1:
        data = data[0]  # (Z, Y, X, C)
    return data, pps


def _resolve_pixel_sizes(pps, override_x, override_y, override_z):
    """Build final pixel-size dict (µm), applying CLI overrides where given."""
    px_z = float(pps.Z) if (pps.Z is not None and override_z is None) else override_z
    px_y = float(pps.Y) if (pps.Y is not None and override_y is None) else override_y
    px_x = float(pps.X) if (pps.X is not None and override_x is None) else override_x
    return {"Z": px_z, "Y": px_y, "X": px_x}


# ---------------------------------------------------------------------------
# Pyramid builder
# ---------------------------------------------------------------------------


def _spatial_axes(data):
    """Return the indices of spatial axes (all dims except a trailing channel)."""
    if data.ndim == 4:
        return [0, 1, 2]  # (Z, Y, X, C) — C is last
    return list(range(data.ndim))  # (Z, Y, X) or (Y, X)


def _downsample(data, spatial_axes):
    """
    Downsample ``data`` by 2× on every spatial axis using local-mean averaging
    (anti-aliased). The result is cast back to the original dtype.

    ``scikit-image`` is used for downscaling; it is already installed as a
    cellpose dependency.
    """
    try:
        from skimage.transform import downscale_local_mean
    except ImportError:
        sys.exit(
            "scikit-image is required for pyramid generation.\n"
            "Install it with:  pip install scikit-image"
        )

    # Build factors tuple: 2 for spatial dims, 1 for channel dim
    factors = tuple(2 if i in spatial_axes else 1 for i in range(data.ndim))
    downsampled = downscale_local_mean(data.astype(np.float64), factors)
    return downsampled.astype(data.dtype)


def _chunks_for_level(base_chunks, level, shape):
    """
    Return chunk sizes for a given pyramid level, ensuring they stay
    within the array dimensions.
    """
    return tuple(min(c, s) for c, s in zip(base_chunks, shape))


def _can_add_level(shape, spatial_axes, base_chunks):
    """
    Return True if the next 2× downsampling step would keep EVERY spatial
    dimension >= 1. (Actually, for pyramids, we handle small dims gracefully).
    """
    for ax in spatial_axes:
        new_size = shape[ax] // 2
        if new_size < 1:
            return False
    return True


def _get_zarr_group(path, mode="w"):
    """Open a Zarr group, forcing Zarr 2 format for compatibility with OME-Zarr 0.4."""
    import os

    import zarr

    # Ensure the directory exists to avoid some atomicity issues on network drives
    if mode == "w" and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)

    try:
        # Zarr 3.x: We MUST use zarr_format=2 for OME-Zarr 0.4.
        # We also disable atomic writes (synchronizers) for network drives
        # to avoid PermissionError [WinError 5] during .partial -> final rename.
        return zarr.open_group(path, mode=mode, zarr_format=2, synchronizer=None)
    except TypeError:
        # Zarr 2.x: zarr_format is not a valid argument
        return zarr.open_group(path, mode=mode, synchronizer=None)


def _write_metadata(zstore, pixel_sizes, axes, datasets_meta, input_name):
    """Utility to write OME-Zarr attributes early."""
    zstore.attrs["multiscales"] = [
        {
            "version": "0.4",
            "name": os.path.basename(input_name),
            "axes": axes,
            "datasets": datasets_meta,
            "type": "local_mean_2x",
            "metadata": {
                "description": (
                    "Pyramidal OME-Zarr generated by convert_to_zarr_for_cellpose.py. "
                    "Axes convention: ZYX or ZYXC (channel-last)."
                )
            },
        }
    ]
    zstore.attrs["physical_pixel_sizes_um"] = pixel_sizes


def _write_ome_zarr(output_path, data, base_chunks, pixel_sizes, n_levels, input_name):
    """
    Write ``data`` as a pyramidal OME-Zarr 0.4 group.

    Processes the array in Z-slabs to avoid blowing up memory during downsampling
    and writes out in chunked increments to optimize Zarr I/O.
    """
    if n_levels == "auto":
        # Data loaded into memory (TIFF, CZI, etc.) lacks a native pyramid ref.
        # We default to 4 levels for standard multi-scale viewing.
        n_levels = 4
    else:
        n_levels = int(n_levels)

    spatial_axes = _spatial_axes(data)
    ndim = data.ndim
    n_ch = data.shape[-1] if ndim == 4 else 1

    store = _get_zarr_group(output_path, mode="w")

    # RE-CALCULATE CHUNKS FOR NETWORK DRIVES (U:)
    chunks0 = _chunks_for_level(base_chunks, 0, data.shape)
    if not (ndim == 3 or ndim == 4):
        storage_chunks = chunks0  # Fallback for 2D or unknown
    elif "U:\\" in output_path or "U:/" in output_path:
        # Increase YX chunks to 1024 to reduce file count on U: drive
        storage_chunks = (chunks0[0], 1024, 1024) + (chunks0[3:] if ndim == 4 else ())
        print(
            f"  Network drive detected. Increasing storage chunks to {storage_chunks} to reduce file count."
        )
    else:
        storage_chunks = chunks0

    datasets_meta = []

    # Setup for level 0
    arr0 = store.create_dataset(
        "0",
        shape=data.shape,
        chunks=storage_chunks,
        dtype=data.dtype,
        overwrite=True,
    )

    # Write Level 0 in chunk_z chunks
    from concurrent.futures import ThreadPoolExecutor

    chunk_z = chunks0[0]
    z_size = data.shape[0]

    def _write_slab(z_start):
        z_end = min(z_start + chunk_z, z_size)
        # Use tiling to keep memory low if Y,X are very large
        # We read in 2048x2048 blocks (approx)
        tile_y, tile_x = 2048, 2048
        for ys in range(0, data.shape[1], tile_y):
            ye = min(ys + tile_y, data.shape[1])
            for xs in range(0, data.shape[2], tile_x):
                xe = min(xs + tile_x, data.shape[2])

                slab_tile = data[z_start:z_end, ys:ye, xs:xe]
                if hasattr(slab_tile, "compute"):
                    slab_tile = slab_tile.compute()
                else:
                    slab_tile = np.asarray(slab_tile)
                arr0[z_start:z_end, ys:ye, xs:xe] = slab_tile

    print(f"  Writing Level 0 ({data.shape}) with parallel tiled extraction...")
    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(_write_slab, range(0, z_size, chunk_z)))

    print(f"  Level 0 done. shape={data.shape}  chunks={chunks0}")

    pz = pixel_sizes["Z"] or 1.0
    py = pixel_sizes["Y"] or 1.0
    px = pixel_sizes["X"] or 1.0

    def _append_ds_meta(path_str, sf):
        scale_values = []
        if ndim == 3:
            scale_values = [pz * sf, py * sf, px * sf]
        elif ndim == 2:
            scale_values = [py * sf, px * sf]
        else:
            scale_values = [pz * sf, py * sf, px * sf, 1.0]
        datasets_meta.append(
            {
                "path": path_str,
                "coordinateTransformations": [{"type": "scale", "scale": scale_values}],
            }
        )

    _append_ds_meta("0", 1)

    # --- SAVE METADATA EARLY ---
    if ndim == 3:
        axes = [
            {"name": "z", "type": "space", "unit": "micrometer"},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
        ]
    elif ndim == 2:
        axes = [
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
        ]
    else:  # (Z, Y, X, C)
        axes = [
            {"name": "z", "type": "space", "unit": "micrometer"},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
            {"name": "c", "type": "channel"},
        ]
    _write_metadata(store, pixel_sizes, axes, datasets_meta, input_name)
    # ---------------------------

    prev_arr = arr0
    prev_shape = data.shape
    factors = tuple(2 if i in spatial_axes else 1 for i in range(ndim))

    try:
        from skimage.transform import downscale_local_mean
    except ImportError:
        sys.exit("scikit-image is required for pyramid generation.")

    for level in range(1, n_levels):
        if not _can_add_level(prev_shape, spatial_axes, base_chunks):
            print(
                f"  Stopping pyramid at level {level} — spatial dims too small "
                f"for another 2× downsampling."
            )
            break

        out_shape = tuple(
            (s + 1) // 2 if i in spatial_axes else s for i, s in enumerate(prev_shape)
        )
        chunks_l = _chunks_for_level(base_chunks, level, out_shape)

        arr_l = store.create_dataset(
            str(level),
            shape=out_shape,
            chunks=chunks_l,
            dtype=data.dtype,
            overwrite=True,
        )

        src_Z = prev_shape[0]
        chunk_z = chunks_l[0]

        def _write_pyramid_tile(out_z_start, out_y_start, out_x_start):
            # Input bounds (2x output)
            src_z_start = out_z_start * 2
            src_z_end = min(src_z_start + chunk_z * 2, src_Z)
            src_y_start = out_y_start * 2
            src_y_end = min(src_y_start + chunks_l[1] * 32, prev_shape[1])
            src_x_start = out_x_start * 2
            src_x_end = min(src_x_start + chunks_l[2] * 32, prev_shape[2])

            # Read tile from prev level
            if n_ch == 1:
                src_tile = np.array(
                    prev_arr[
                        src_z_start:src_z_end,
                        src_y_start:src_y_end,
                        src_x_start:src_x_end,
                    ],
                    dtype=np.float64,
                )
            else:
                src_tile = np.array(
                    prev_arr[
                        src_z_start:src_z_end,
                        src_y_start:src_y_end,
                        src_x_start:src_x_end,
                        :,
                    ],
                    dtype=np.float64,
                )

            # Downsample tile
            ds_tile = downscale_local_mean(src_tile, factors).astype(data.dtype)

            # Output bounds
            out_z_end = min(out_z_start + chunk_z, out_shape[0])
            out_y_end = min(out_y_start + chunks_l[1] * 16, out_shape[1])
            out_x_end = min(out_x_start + chunks_l[1] * 16, out_shape[2])

            # Write to current level
            actual_z = out_z_end - out_z_start
            actual_y = out_y_end - out_y_start
            actual_x = out_x_end - out_x_start

            if n_ch == 1:
                arr_l[
                    out_z_start:out_z_end, out_y_start:out_y_end, out_x_start:out_x_end
                ] = ds_tile[:actual_z, :actual_y, :actual_x]
            else:
                arr_l[
                    out_z_start:out_z_end,
                    out_y_start:out_y_end,
                    out_x_start:out_x_end,
                    :,
                ] = ds_tile[:actual_z, :actual_y, :actual_x, :]

        # Tile based pyramid build to save RAM
        with ThreadPoolExecutor(max_workers=4) as executor:
            for ozs in range(0, out_shape[0], chunk_z):
                for oys in range(0, out_shape[1], chunks_l[1] * 16):
                    for oxs in range(0, out_shape[2], chunks_l[2] * 16):
                        executor.submit(_write_pyramid_tile, ozs, oys, oxs)

        print(f"  Level {level}: shape={out_shape}  chunks={chunks_l} (tiled parallel)")
        _append_ds_meta(str(level), 2**level)
        # Update metadata to include the new level path
        _write_metadata(store, pixel_sizes, axes, datasets_meta, input_name)
        prev_arr = arr_l
        prev_shape = out_shape

    # ---- OME-Zarr 0.4 metadata (Done) --------------------------------------
    # Consolidate all metadata into a single .zmetadata file so that distributed
    # readers (multiple Dask workers) skip individual per-array metadata reads.
    zarr.consolidate_metadata(output_path)

    return store


# ---------------------------------------------------------------------------
# Low-memory TIFF-folder → OME-Zarr writer
# ---------------------------------------------------------------------------


def _write_ome_zarr_from_tiff_folder(
    folder_path,
    output_path,
    base_chunks,
    override_x,
    override_y,
    override_z,
    n_levels,
    input_name,
):
    """
    Memory-efficient OME-Zarr writer for a folder of single-plane 2-D TIFFs.
    """
    if n_levels == "auto":
        # Folder of TIFFs has no native pyramid depth. Default to 4.
        n_levels = 4
    else:
        n_levels = int(n_levels)

    import re

    try:
        import tifffile
        from skimage.transform import downscale_local_mean
    except ImportError as exc:
        sys.exit(f"Missing dependency for TIFF-folder conversion: {exc}")

    def _natural_key(s):
        return [
            int(tok) if tok.isdigit() else tok.lower() for tok in re.split(r"(\d+)", s)
        ]

    all_files = [
        f for f in os.listdir(folder_path) if f.lower().endswith((".tif", ".tiff"))
    ]
    tiff_files = [
        os.path.join(folder_path, f) for f in sorted(all_files, key=_natural_key)
    ]
    if not tiff_files:
        sys.exit(f"No TIFF files found in folder: {folder_path}")

    n_files = len(tiff_files)
    print(f"Found {n_files} TIFF file(s); converting slice-by-slice (low-memory mode).")

    # ---- Probe first file for plane shape, dtype and pixel sizes -----------
    # is_mmstack=False prevents tifffile from following MicroManager virtual series
    with tifffile.TiffFile(tiff_files[0], is_mmstack=False) as tif:
        first_page_arr = tif.pages[0].asarray()
        px_x, px_y, px_z = _tiff_pixel_sizes(tif)

    if first_page_arr.ndim != 2:
        sys.exit(
            f"Expected single-plane (2-D) TIFFs in folder; first file has shape "
            f"{first_page_arr.shape}. Use the 'Input file' picker for multi-plane TIFFs."
        )

    plane_shape = first_page_arr.shape  # (Y, X)
    dtype = first_page_arr.dtype
    full_shape = (n_files,) + plane_shape  # (Z, Y, X)
    print(f"Virtual stack shape: {full_shape}  dtype: {dtype}")

    # ---- Resolve pixel sizes -----------------------------------------------
    class _PPS:
        X = px_x
        Y = px_y
        Z = px_z

    pixel_sizes = _resolve_pixel_sizes(
        _PPS(), override_x=override_x, override_y=override_y, override_z=override_z
    )
    logger.info(
        f"Pixel sizes (µm): Z={pixel_sizes['Z']}, Y={pixel_sizes['Y']}, X={pixel_sizes['X']}"
    )

    # ---- Create zarr store and write level 0 slice-by-slice ----------------
    from concurrent.futures import ThreadPoolExecutor

    zstore = _get_zarr_group(output_path, mode="w")
    chunks0 = _chunks_for_level(base_chunks, 0, full_shape)
    arr0 = zstore.create_dataset(
        "0", shape=full_shape, chunks=chunks0, dtype=dtype, overwrite=True
    )

    chunk_z = chunks0[0]

    def _process_tiff_slab(z_start):
        z_end = min(z_start + chunk_z, n_files)
        slab_len = z_end - z_start
        slab_buffer = np.zeros((slab_len,) + plane_shape, dtype=dtype)
        for i in range(slab_len):
            fpath = tiff_files[z_start + i]
            with tifffile.TiffFile(fpath, is_mmstack=False) as tif:
                slab_buffer[i] = tif.pages[0].asarray()
        arr0[z_start:z_end] = slab_buffer

    print(f"  Level 0: shape={full_shape}  chunks={chunks0} (parallel TIFF extraction)")
    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(_process_tiff_slab, range(0, n_files, chunk_z)))

    # ---- Build pyramid levels 1+ in 2-Z-slab passes -----------------------
    spatial_axes = [0, 1, 2]
    datasets_meta = []
    pz = pixel_sizes["Z"] or 1.0
    py = pixel_sizes["Y"] or 1.0
    px = pixel_sizes["X"] or 1.0

    def _append_ds_meta(path_str, sf):
        datasets_meta.append(
            {
                "path": path_str,
                "coordinateTransformations": [
                    {
                        "type": "scale",
                        "scale": [pz * sf, py * sf, px * sf],
                    }
                ],
            }
        )

    _append_ds_meta("0", 1)
    prev_arr = arr0
    prev_shape = full_shape

    for level in range(1, n_levels):
        if not _can_add_level(prev_shape, spatial_axes, base_chunks):
            print(
                f"  Stopping pyramid at level {level} — spatial dims too small "
                f"for another 2x downsampling."
            )
            break

        out_shape = tuple((s + 1) // 2 for s in prev_shape)
        chunks_l = _chunks_for_level(base_chunks, level, out_shape)
        arr_l = zstore.create_dataset(
            str(level), shape=out_shape, chunks=chunks_l, dtype=dtype, overwrite=True
        )

        src_Z = prev_shape[0]
        chunk_z = chunks_l[0]

        def _write_tiff_pyramid_slab(out_z_start):
            src_z_start = out_z_start * 2
            src_z_end = min(src_z_start + chunk_z * 2, src_Z)
            out_z_end = min(out_z_start + chunk_z, out_shape[0])

            slab = np.array(prev_arr[src_z_start:src_z_end], dtype=np.float64)
            ds_slab = downscale_local_mean(slab, (2, 2, 2)).astype(dtype)

            actual_out_len = out_z_end - out_z_start
            arr_l[out_z_start:out_z_end] = ds_slab[:actual_out_len]

        with ThreadPoolExecutor(max_workers=4) as executor:
            list(
                executor.map(_write_tiff_pyramid_slab, range(0, out_shape[0], chunk_z))
            )

        scale_factor = 2**level
        print(f"  Level {level}: shape={out_shape}  chunks={chunks_l} (parallel)")
        _append_ds_meta(str(level), scale_factor)
        prev_arr = arr_l
        prev_shape = out_shape

    # ---- OME-Zarr 0.4 metadata ---------------------------------------------
    axes = [
        {"name": "z", "type": "space", "unit": "micrometer"},
        {"name": "y", "type": "space", "unit": "micrometer"},
        {"name": "x", "type": "space", "unit": "micrometer"},
    ]
    zstore.attrs["multiscales"] = [
        {
            "version": "0.4",
            "name": os.path.basename(input_name),
            "axes": axes,
            "datasets": datasets_meta,
            "type": "local_mean_2x",
            "metadata": {
                "description": (
                    "Pyramidal OME-Zarr generated by convert_to_zarr_for_cellpose.py. "
                    "Axes convention: ZYX."
                )
            },
        }
    ]
    zstore.attrs["physical_pixel_sizes_um"] = pixel_sizes
    zarr.consolidate_metadata(output_path)
    return zstore


# ---------------------------------------------------------------------------
# Low-memory IMS → OME-Zarr writer
# ---------------------------------------------------------------------------


def _write_ome_zarr_from_ims(
    ims_path,
    output_path,
    base_chunks,
    override_x,
    override_y,
    override_z,
    n_levels,
    input_name,
):
    """
    Memory-efficient OME-Zarr writer for an Imaris ``.ims`` (HDF5) file.

    Reads one Z-slab at a time via h5py hyperslab selection so peak RAM stays
    proportional to ``n_channels × chunk_z × Y × X`` rather than the whole
    volume.  Multichannel data is written channel-last (Z, Y, X, C).

    Pyramid levels preserve the native IMS geometry and physical scale from each
    ``ResolutionLevel N`` entry, cropping away HDF5 storage padding via the
    per-level ``ImageSizeX/Y/Z`` attributes.
    """
    try:
        import h5py
    except ImportError as exc:
        sys.exit(f"Missing dependency for IMS conversion: {exc}")

    def _attr_str(attrs, key):
        v = attrs.get(key)
        if v is None:
            return None
        if isinstance(v, np.ndarray):
            if v.dtype.kind == "S":
                # Fixed-length byte-string dtype: each element may be a single char.
                # Join all elements before decoding (handles char-per-element arrays).
                return (
                    b"".join(x if isinstance(x, bytes) else bytes([x]) for x in v.flat)
                    .decode("utf-8", errors="replace")
                    .strip()
                )
            v = v.flat[0]
        if isinstance(v, (bytes, np.bytes_)):
            return v.decode("utf-8", errors="replace").strip()
        if isinstance(v, (list, tuple)):
            try:
                return bytes(v).decode("utf-8", errors="replace").strip()
            except (TypeError, ValueError):
                pass
        return str(v).strip()

    # ---- Open HDF5 once to read metadata and write level 0 ----------------
    with h5py.File(ims_path, "r") as f:
        img_attrs = f["/DataSetInfo/Image"].attrs

        # Detect original pyramid levels
        if n_levels == "auto":
            try:
                ds_root = f["/DataSet"]
                n_levels = len(
                    [k for k in ds_root.keys() if k.startswith("ResolutionLevel ")]
                )
                print(f"  Detected {n_levels} original pyramid levels in IMS file.")
            except Exception:
                n_levels = 4
                print("  Could not detect original pyramid levels. Defaulting to 4.")
        else:
            n_levels = int(n_levels)

        def _flt(key):
            s = _attr_str(img_attrs, key)
            try:
                return float(s) if s else None
            except ValueError:
                return None

        tp_root = f["/DataSet/ResolutionLevel 0/TimePoint 0"]
        ch_keys = sorted(
            [k for k in tp_root.keys() if k.startswith("Channel ")],
            key=lambda k: int(k.split()[-1]),
        )
        n_ch = len(ch_keys)

        def _int_attr(attrs, key, fallback):
            s = _attr_str(attrs, key)
            try:
                return int(s) if s else int(fallback)
            except (TypeError, ValueError):
                return int(fallback)

        ds_root = f["/DataSet"]
        native_level_names = sorted(
            [k for k in ds_root.keys() if k.startswith("ResolutionLevel ")],
            key=lambda name: int(name.split()[-1]),
        )
        if not native_level_names:
            sys.exit("No IMS pyramid levels found under /DataSet.")

        requested_levels = int(n_levels)
        if requested_levels > len(native_level_names):
            print(
                f"  Requested {requested_levels} levels but IMS only provides {len(native_level_names)}. "
                "Capping output to the native IMS pyramid depth."
            )
        native_level_names = native_level_names[: min(requested_levels, len(native_level_names))]

        level_entries = []
        for level_name in native_level_names:
            channel0 = ds_root[level_name]["TimePoint 0"][ch_keys[0]]
            data = channel0["Data"]
            attrs = channel0.attrs
            logical_shape = (
                _int_attr(attrs, "ImageSizeZ", data.shape[0]),
                _int_attr(attrs, "ImageSizeY", data.shape[1]),
                _int_attr(attrs, "ImageSizeX", data.shape[2]),
            )
            level_entries.append(
                {
                    "name": level_name,
                    "index": int(level_name.split()[-1]),
                    "stored_shape": tuple(int(v) for v in data.shape),
                    "logical_shape": logical_shape,
                }
            )

        level0 = level_entries[0]
        z_size, y_size, x_size = level0["logical_shape"]
        dtype = ds_root[level0["name"]]["TimePoint 0"][ch_keys[0]]["Data"].dtype
        print(
            f"IMS: {n_ch} channel(s), shape=({z_size},{y_size},{x_size}), dtype={dtype}"
        )

        # Read physical extents — use actual dataset shape as denominator so we
        # are never fooled by a misread X/Y/Z voxel-count attribute.
        ex_min = _flt("ExtMin0")
        ex_max = _flt("ExtMax0")
        ey_min = _flt("ExtMin1")
        ey_max = _flt("ExtMax1")
        ez_min = _flt("ExtMin2")
        ez_max = _flt("ExtMax2")
        print(
            f"  IMS extents (µm): X=[{ex_min}, {ex_max}]  "
            f"Y=[{ey_min}, {ey_max}]  Z=[{ez_min}, {ez_max}]"
        )

        px_x = (
            (ex_max - ex_min) / x_size
            if (ex_min is not None and ex_max is not None and x_size > 0)
            else None
        )
        px_y = (
            (ey_max - ey_min) / y_size
            if (ey_min is not None and ey_max is not None and y_size > 0)
            else None
        )
        px_z = (
            (ez_max - ez_min) / z_size
            if (ez_min is not None and ez_max is not None and z_size > 0)
            else None
        )

        if any(p is None for p in [px_x, px_y, px_z]):
            print(
                "  Warning: could not read physical extents from IMS /DataSetInfo/Image. "
                "Pixel sizes will be null unless provided via --pixel_size_*_um."
            )

        class _PPS:
            X = px_x
            Y = px_y
            Z = px_z

        pixel_sizes = _resolve_pixel_sizes(
            _PPS(),
            override_x=override_x,
            override_y=override_y,
            override_z=override_z,
        )
        logger.info(
            f"Pixel sizes (µm): Z={pixel_sizes['Z']}, Y={pixel_sizes['Y']}, X={pixel_sizes['X']}"
        )

        def _full_shape(level_shape):
            return level_shape if n_ch == 1 else level_shape + (n_ch,)

        def _level_pixel_sizes(level_shape):
            level_z, level_y, level_x = level_shape
            return {
                "Z": ((ez_max - ez_min) / level_z) if (ez_min is not None and ez_max is not None and level_z > 0) else pixel_sizes["Z"],
                "Y": ((ey_max - ey_min) / level_y) if (ey_min is not None and ey_max is not None and level_y > 0) else pixel_sizes["Y"],
                "X": ((ex_max - ex_min) / level_x) if (ex_min is not None and ex_max is not None and level_x > 0) else pixel_sizes["X"],
            }

        full_shape = _full_shape(level0["logical_shape"])
        spatial_axes = [0, 1, 2]

        chunks0 = _chunks_for_level(base_chunks, 0, full_shape)
        if n_ch > 1 and len(chunks0) == 3:
            # Store all channels in one chunk so a single read fetches the full block
            chunks0 = chunks0 + (n_ch,)

        # MODE CHECK: If we want to resume, we must use mode='a' (append)
        # to avoid wiping the existing directory.
        zstore_mode = "a" if os.path.exists(output_path) else "w"
        zstore = _get_zarr_group(output_path, mode=zstore_mode)

    # ---------------------------
    # SAVE METADATA (including group setup) FIRST to avoid race conditions
    # when creating datasets in parallel on network drives.
    # ---------------------------
    def _get_scale(level_shape):
        sizes = _level_pixel_sizes(level_shape)
        scale = [sizes["Z"] or 1.0, sizes["Y"] or 1.0, sizes["X"] or 1.0]
        if n_ch > 1:
            scale.append(1.0)
        return scale

    datasets_meta = [
        {
            "path": "0",
            "coordinateTransformations": [{"type": "scale", "scale": _get_scale(level0["logical_shape"])}],
        }
    ]

    if "U:\\" in output_path or "U:/" in output_path:
        storage_chunks = (chunks0[0], 1024, 1024) + (
            chunks0[3:] if len(chunks0) > 3 else ()
        )
    else:
        storage_chunks = chunks0

    # Pre-calculate native IMS pyramid level shapes and metadata
    pyramid_info = []  # list of (level, ims_name, logical_shape, chunks, storage_chunks)

    for entry in level_entries[1:]:
        l_idx = entry["index"]
        l_shape = _full_shape(entry["logical_shape"])
        l_chunks = _chunks_for_level(base_chunks, l_idx, l_shape)
        if n_ch > 1 and len(l_chunks) == 3:
            l_chunks = l_chunks + (n_ch,)

        if "U:\\" in output_path or "U:/" in output_path:
            l_storage = (l_chunks[0], 1024, 1024) + (l_chunks[3:] if n_ch > 1 else ())
        else:
            l_storage = l_chunks

        pyramid_info.append((l_idx, entry["name"], entry["logical_shape"], l_shape, l_chunks, l_storage))
        datasets_meta.append(
            {
                "path": str(l_idx),
                "coordinateTransformations": [
                    {"type": "scale", "scale": _get_scale(entry["logical_shape"])}
                ],
            }
        )

    if n_ch == 1:
        axes = [
            {"name": "z", "type": "space", "unit": "micrometer"},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
        ]
    else:
        axes = [
            {"name": "z", "type": "space", "unit": "micrometer"},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
            {"name": "c", "type": "channel"},
        ]

    # ---------------------------
    # Initializing datasets and metadata
    # ---------------------------
    # Create the datasets one by one synchronously to avoid race conditions
    # on the metadata files (.zattrs/.zarray) on network drives.
    if "0" not in zstore:
        arr0 = zstore.create_dataset(
            "0",
            shape=full_shape,
            chunks=storage_chunks,
            dtype=dtype,
            overwrite=True,
        )
    else:
        arr0 = zstore["0"]

    for level_idx, _ims_name, _logical_shape, out_shape, chunks_l, storage_chunks_l in pyramid_info:
        if str(level_idx) not in zstore:
            zstore.create_dataset(
                str(level_idx),
                shape=out_shape,
                chunks=storage_chunks_l,
                dtype=dtype,
                overwrite=True,
            )

    # Initialize the group attributes ONLY after all arrays are created.
    # This ensures Zarr doesn't try to rename .zattrs while we're writing data.
    _write_metadata(zstore, pixel_sizes, axes, datasets_meta, input_name)
    # ---------------------------

    # 1. Write Level 0 (Data extraction is parallel)
    # Check if level 0 is already fully written (heuristic: check if .zarray exists and if we're not in overwrite mode)
    # Actually, we can check if the first chunk exists or just re-run if it's fast.
    # Since level 0 is the most expensive, we'll check if any chunk exists.
    level0_done = False
    if "0" in zstore:
        # Check if the array has any non-zero data (very basic check) or if we want to skip it.
        # For now, let's look for the first chunk file on disk.
        chunk_path = os.path.join(output_path, "0", "0.0.0" if n_ch == 1 else "0.0.0.0")
        if os.path.exists(chunk_path):
            print("  Level 0 seems to already exist. Skipping write pass.")
            level0_done = True

    if not level0_done:
        # PARALLEL READING OPTIMIZATION:
        from concurrent.futures import ThreadPoolExecutor

        read_stride_yx = 2048
        chunk_z = chunks0[0]

        print(f"Writing level 0 — {full_shape} with tiled parallel extraction ...")

        def _write_tile(h5_file_path, ch_keys, z_start, y_start, x_start):
            import h5py

            z_end = min(z_start + chunk_z, z_size)
            y_end = min(y_start + read_stride_yx, y_size)
            x_end = min(x_start + read_stride_yx, x_size)

            with h5py.File(h5_file_path, "r") as f_local:
                tp_local = f_local[level0["name"]]["TimePoint 0"] if level0["name"].startswith("/") else f_local[f"/DataSet/{level0['name']}/TimePoint 0"]
                if n_ch == 1:
                    tile = tp_local[ch_keys[0]]["Data"][
                        z_start:z_end, y_start:y_end, x_start:x_end
                    ]
                    arr0[z_start:z_end, y_start:y_end, x_start:x_end] = tile
                else:
                    ch_tiles = [
                        tp_local[ck]["Data"][
                            z_start:z_end, y_start:y_end, x_start:x_end
                        ]
                        for ck in ch_keys
                    ]
                    tile = np.stack(ch_tiles, axis=-1)
                    arr0[z_start:z_end, y_start:y_end, x_start:x_end] = tile

        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = []
            for zs in range(0, z_size, chunk_z):
                for ys in range(0, y_size, read_stride_yx):
                    for xs in range(0, x_size, read_stride_yx):
                        futures.append(
                            executor.submit(_write_tile, ims_path, ch_keys, zs, ys, xs)
                        )

            n_tiles = len(futures)
            print(f"  Total tiles to write: {n_tiles}", flush=True)
            for i, fut in enumerate(futures):
                fut.result()
                if (i + 1) % max(1, n_tiles // 20) == 0:
                    print(
                        f"  Progress Level 0: {(i + 1) / n_tiles * 100:0.1f}% ({i + 1}/{n_tiles} tiles)",
                        flush=True,
                    )

    print(f"  Level 0 done: shape={full_shape}  chunks={chunks0}")

    # 2. Copy native IMS pyramid levels 1+
    for level, ims_level_name, logical_shape, out_shape, chunks_l, storage_chunks_l in pyramid_info:
        arr_l = zstore[str(level)]

        # Check if this level is already done
        level_done = False
        chunk_path = os.path.join(
            output_path, str(level), "0.0.0" if n_ch == 1 else "0.0.0.0"
        )
        if os.path.exists(chunk_path):
            print(f"  Level {level} seems to already exist. Skipping downsample pass.")
            level_done = True

        if not level_done:
            chunk_z = chunks_l[0]
            level_z, level_y, level_x = logical_shape
            print(f"  Copying native IMS level {level} {out_shape}...")

            def _copy_ims_level_tile(out_z_start, out_y_start, out_x_start):
                tile_size_yx = 1024
                out_z_end = min(out_z_start + chunk_z, level_z)
                out_y_end = min(out_y_start + tile_size_yx, level_y)
                out_x_end = min(out_x_start + tile_size_yx, level_x)

                with h5py.File(ims_path, "r") as f_local:
                    tp_local = f_local[f"/DataSet/{ims_level_name}/TimePoint 0"]
                    if n_ch == 1:
                        tile = tp_local[ch_keys[0]]["Data"][
                            out_z_start:out_z_end,
                            out_y_start:out_y_end,
                            out_x_start:out_x_end,
                        ]
                        arr_l[
                            out_z_start:out_z_end,
                            out_y_start:out_y_end,
                            out_x_start:out_x_end,
                        ] = tile
                    else:
                        ch_tiles = [
                            tp_local[ck]["Data"][
                                out_z_start:out_z_end,
                                out_y_start:out_y_end,
                                out_x_start:out_x_end,
                            ]
                            for ck in ch_keys
                        ]
                        tile = np.stack(ch_tiles, axis=-1)
                        arr_l[
                            out_z_start:out_z_end,
                            out_y_start:out_y_end,
                            out_x_start:out_x_end,
                            :,
                        ] = tile

            # Tile based pyramid build to save RAM
            with ThreadPoolExecutor(max_workers=4) as executor:
                pyr_futures = []
                for ozs in range(0, level_z, chunk_z):
                    for oys in range(0, level_y, 1024):
                        for oxs in range(0, level_x, 1024):
                            pyr_futures.append(
                                executor.submit(_copy_ims_level_tile, ozs, oys, oxs)
                            )

                n_pyr_tiles = len(pyr_futures)
                for i, fut in enumerate(pyr_futures):
                    fut.result()
                    if (i + 1) % max(1, n_pyr_tiles // 20) == 0:
                        print(
                            f"  Progress Level {level}: {(i + 1) / n_pyr_tiles * 100:0.1f}% ({i + 1}/{n_pyr_tiles} tiles)",
                            flush=True,
                        )

            print(
                f"  Level {level} done: shape={out_shape}  chunks={chunks_l} (native IMS copy)"
            )

    # ---- OME-Zarr 0.4 metadata (Done) --------------------------------------
    zstore.attrs["physical_pixel_sizes_um"] = pixel_sizes

    # consolidate_metadata can fail on network drives (e.g. Windows/SMB) due to atomic rename issues with .zattrs
    try:
        zarr.consolidate_metadata(output_path)
    except Exception as e:
        logger.warning(
            f"Could not consolidate metadata (expected on some network drives): {e}"
        )

    return zstore


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    args = parse_args()

    # Accept both 'x' and ',' as delimiters ('x' is the Java-safe separator).
    # Expected format is ZxYxX for 3D data.
    if args.chunks.lower() == "auto":
        # DEFAULT OPTIMAL FOR CELLPOSE + NETWORK DRIVE:
        # Z=64 is the ideal balance between 3D context and memory usage.
        # YX=1024 minimizes the number of files on the U: drive (crucial!).
        # Note: distributed_cellpose_run.py reads small sub-tiles (128x128)
        # from within these 1024x1024 chunks, which is very efficient in Zarr.
        print("Auto-calculating optimal chunks for Cellpose + Network Drive...")
        chunk_values = [64, 1024, 1024]
    elif args.chunks.lower() == "auto_cellpose":
        # Legacy support/alternative name
        print("Auto-calculating optimal chunks for Cellpose...")
        chunk_values = [64, 1024, 1024]
    else:
        # Accept both 'x' and ',' as delimiters ('x' is the Java-safe separator).
        chunk_values = [
            int(c.strip()) for c in args.chunks.replace("x", ",").split(",")
        ]

    # CELLPOSE OPTIMIZATION:
    # Cellpose 3D works best with Z chunks that are power-of-2 and ideally <= 64
    # (to avoid excessive memory per worker) but YX chunks should be large enough
    # to contain full cells (128-256).
    if len(chunk_values) == 3:
        cz, cy, cx = chunk_values
        if cz > 128:
            logger.warning(
                f"Large Z chunk ({cz}) detected. Cellpose distributed may be slow "
                "due to high memory usage per task. Consider Z <= 64."
            )
        if cy < 64 or cx < 64:
            logger.warning(
                f"Small YX chunks ({cy}x{cx}) detected. "
                "Consider Y,X >= 128 for better segmentation performance."
            )

    if os.path.isdir(args.input_path):
        # Low-memory path: never allocate the full Z-stack in RAM
        print(
            f"Input is a directory — reading TIFF folder in low-memory mode: "
            f"{args.input_path}"
        )
        ndim = 3  # TIFF folders always produce a ZYX volume
        cv = chunk_values + [chunk_values[-1]] * max(0, ndim - len(chunk_values))
        base_chunks = tuple(cv[:ndim])
        print(f"Base chunks (level 0): {base_chunks}")
        print(
            f"Writing OME-Zarr pyramid ({args.n_levels} levels max) to: "
            f"{args.output_zarr}"
        )
        _write_ome_zarr_from_tiff_folder(
            folder_path=args.input_path,
            output_path=args.output_zarr,
            base_chunks=base_chunks,
            override_x=args.pixel_size_x_um,
            override_y=args.pixel_size_y_um,
            override_z=args.pixel_size_z_um,
            n_levels=args.n_levels,
            input_name=args.input_path,
        )
        print("Done.")
        return

    if args.input_path.lower().endswith(".ims"):
        # DIRECT H5PY PATH: Much faster for both metadata and data on large files.
        # We skip BioIO because it often hangs/times out on large network IMS files
        # and triggers massive Java initialization overhead.
        print(f"Using manual h5py reader for IMS (skipping BioIO): {args.input_path}")
        cv = chunk_values + [chunk_values[-1]] * max(0, 3 - len(chunk_values))
        base_chunks = tuple(cv[:3])
        _write_ome_zarr_from_ims(
            ims_path=args.input_path,
            output_path=args.output_zarr,
            base_chunks=base_chunks,
            override_x=args.pixel_size_x_um,
            override_y=args.pixel_size_y_um,
            override_z=args.pixel_size_z_um,
            n_levels=args.n_levels,
            input_name=args.input_path,
        )
        print("Done.")
        return

    print(f"Reading: {args.input_path}")
    data, pps = _read_image(args.input_path)
    data = _simplify_array(data)
    print(f"Image shape: {data.shape}  dtype: {data.dtype}")

    # Pad/truncate chunk tuple to match data.ndim
    if len(chunk_values) < data.ndim:
        chunk_values = chunk_values + [chunk_values[-1]] * (
            data.ndim - len(chunk_values)
        )
    base_chunks = tuple(chunk_values[: data.ndim])
    print(f"Base chunks (level 0): {base_chunks}")

    pixel_sizes = _resolve_pixel_sizes(
        pps,
        override_x=args.pixel_size_x_um,
        override_y=args.pixel_size_y_um,
        override_z=args.pixel_size_z_um,
    )
    logger.info(
        f"Pixel sizes (µm): Z={pixel_sizes['Z']}, Y={pixel_sizes['Y']}, X={pixel_sizes['X']}"
    )

    print(
        f"Writing OME-Zarr pyramid ({args.n_levels} levels max) to: {args.output_zarr}"
    )
    _write_ome_zarr(
        output_path=args.output_zarr,
        data=data,
        base_chunks=base_chunks,
        pixel_sizes=pixel_sizes,
        n_levels=args.n_levels,
        input_name=args.input_path,
    )

    print("Done.")


if __name__ == "__main__":
    main()
