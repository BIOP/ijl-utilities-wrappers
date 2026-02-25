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
    pixi add h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy

Using Conda:
    conda create -n image2zarr -c conda-forge h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy
    conda activate image2zarr

Using Pip:
    pip install h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy
"""

import argparse
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple, Union

import dask.array as da
import numpy as np
import zarr

try:
    import h5py
except ImportError:
    h5py = None

try:
    from bioio import BioImage
except ImportError:
    BioImage = None


def check_dependencies() -> None:
    """Check for required core dependencies and exit if missing."""
    missing = []
    try:
        import zarr as _  # noqa: F401
    except ImportError:
        missing.append("zarr")
    try:
        import dask.array as _  # noqa: F401
    except ImportError:
        missing.append("dask")
    try:
        import numpy as _  # noqa: F401
    except ImportError:
        missing.append("numpy")

    if missing:
        print(f"Error: Missing core dependencies: {', '.join(missing)}")
        print("Please install them using: pip install " + " ".join(missing))
        sys.exit(1)


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
        f = h5py.File(input_path, "r")
        t_idx = timepoint - 1
        t_key = f"TimePoint {t_idx}"

        # Setup root group
        root = zarr.open_group(output_path, mode="w")

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
                continue

            # Find all channel keys for this timepoint
            c_keys = sorted(
                [k for k in res_group[t_key].keys() if k.startswith("Channel ")],
                key=lambda x: int(x.split()[-1]),
            )

            if not c_keys:
                continue

            # Extract all channels as a list of dask arrays
            channel_arrays = []
            shape_zyx = None

            for c_key in c_keys:
                ds = res_group[t_key][c_key]["Data"]
                if shape_zyx is None:
                    shape_zyx = ds.shape
                channel_arrays.append(da.from_array(ds, chunks=ds.chunks))

            # Stack into (C, Z, Y, X)
            combined = da.stack(channel_arrays, axis=0)

            # Apply rechunking if target_chunks provided
            if target_chunks:
                level_chunks = tuple(
                    min(c, s) for c, s in zip(target_chunks, combined.shape)
                )
                combined = combined.rechunk(level_chunks)

            sub_path = str(res_level)
            print(
                f"  Writing Level {res_level}: shape {combined.shape},"
                f" chunks {combined.chunksize}..."
            )
            combined.to_zarr(output_path, component=sub_path, overwrite=True)

            # Metadata for OME-Zarr multiscales
            if res_level == 0:
                shape0 = shape_zyx
                # Capture base resolution metadata
                anisotropy_base, pixel_sizes_base = get_ims_metadata(f, shape0)
                if pixel_sizes_base:
                    pz, py, px = pixel_sizes_base

            # Calculate relative scale for this level
            scale = [
                1.0,
                pz * (shape0[0] / shape_zyx[0]),
                py * (shape0[1] / shape_zyx[1]),
                px * (shape0[2] / shape_zyx[2]),
            ]

            datasets.append(
                {
                    "path": sub_path,
                    "coordinateTransformations": [{"type": "scale", "scale": scale}],
                }
            )

        # Finalize multiscales metadata in .zattrs
        root.attrs["multiscales"] = [
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
        ]

        if "pixel_sizes_base" in locals() and pixel_sizes_base:
            root.attrs["pixel_size"] = [1.0, *pixel_sizes_base]

        print(f"Conversion completed in {time.time() - start_time:.2f}s.")
        return True
    except Exception as e:
        print(f"Imaris conversion failed: {e}")
        import traceback

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

        root = zarr.open_group(output_path, mode="w")
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
            if level == 0:
                level_data = d_sliced
            else:
                level_data = da.coarsen(
                    np.mean,
                    level_data,
                    {level_data.ndim - 2: 2, level_data.ndim - 1: 2},
                    trim_excess=True,
                ).astype(d_sliced.dtype)

            if target_chunks:
                level_chunks = tuple(
                    min(c, s) for c, s in zip(target_chunks, level_data.shape)
                )
                level_data = level_data.rechunk(level_chunks)

            sub_path = str(level)
            print(f"  Writing Level {level}: shape {level_data.shape}...")
            level_data.to_zarr(output_path, component=sub_path, overwrite=True)

            scale = [1.0, pz, py * (2**level), px * (2**level)]
            datasets.append(
                {
                    "path": sub_path,
                    "coordinateTransformations": [{"type": "scale", "scale": scale}],
                }
            )

        root.attrs["multiscales"] = [
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
        ]
        root.attrs["pixel_size"] = [1.0, pz, py, px]

        print(f"Conversion completed in {time.time() - start_time:.2f}s.")
        return True
    except Exception as e:
        print(f"BioIO conversion failed: {e}")
        import traceback

        traceback.print_exc()
        return False


def main() -> None:
    """Parse arguments and initiate image to OME-Zarr conversion."""
    check_dependencies()

    parser = argparse.ArgumentParser(description="Multi-Channel OME-Zarr Converter")
    parser.add_argument("input", help="Input image file")
    parser.add_argument("output", help="Output .zarr directory")
    parser.add_argument("--timepoint", type=int, default=1, help="Timepoint (1-based)")
    parser.add_argument("--chunks", default="1,64,512,512", help="Chunks (C,Z,Y,X)")
    parser.add_argument("--pyramid", action="store_true", default=True)
    parser.add_argument("--no-pyramid", dest="pyramid", action="store_false")

    args = parser.parse_args()

    target_chunks = None
    if args.chunks:
        try:
            target_chunks = tuple(int(x) for x in args.chunks.split(","))
        except ValueError:
            print(f"Error parsing chunks: {args.chunks}")
            sys.exit(1)

    if not args.output.endswith(".zarr"):
        args.output += ".zarr"

    if os.path.exists(args.output) and (
        os.path.exists(os.path.join(args.output, ".zarray"))
        or os.path.exists(os.path.join(args.output, ".zgroup"))
    ):
        print(f"Skipping: Zarr already exists at {args.output}")
        sys.exit(0)

    ext = os.path.splitext(args.input.rstrip("/"))[1].lower()

    if ext == ".ims":
        if convert_ims_to_zarr(
            args.input,
            args.output,
            args.timepoint,
            target_chunks,
            args.pyramid,
        ):
            return

    if BioImage:
        if not convert_bioio_to_zarr(
            args.input,
            args.output,
            args.timepoint,
            target_chunks,
            args.pyramid,
        ):
            sys.exit(1)
    else:
        print("Error: For non-IMS files, BioIO is required.")
        sys.exit(1)


if __name__ == "__main__":
    main()
