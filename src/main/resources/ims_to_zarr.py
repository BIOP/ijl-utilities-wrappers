#!/usr/bin/env python3
"""IMS/Bio-Formats to Multi-Channel Zarr converter.

This script converts massive scientific image files (.ims, .czi, .nd2, etc.)
directly to a multi-channel Zarr volume suitable for distributed cellpose.
It uses h5py for IMS (optimized) and BioIO as a universal reader.

Works on
--------
2D, 3D, 4D (Timepoints)

Required Packages
-----------------
pip install h5py bioio bioio-bioformats bioio-ome-tiff dask zarr numpy
"""

import argparse
import os
import sys
import time
from typing import Any, Optional, Tuple

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


def get_ims_metadata(
    f: Any, shape_zyx: Tuple[int, ...]
) -> Tuple[Optional[float], Optional[Tuple[float, float, float]]]:
    """
    Extract anisotropy and pixel sizes from Imaris file metadata.

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
) -> bool:
    """
    Convert all channels of an Imaris file to Zarr using h5py.

    Parameters
    ----------
    input_path : str
        Path to the source .ims file.
    output_path : str
        Path to the output .zarr directory.
    timepoint : int, optional
        1-based timepoint index to extract, by default 1.
    target_chunks : Optional[Tuple[int, ...]], optional
        Desired Zarr chunking (C, Z, Y, X), by default None.

    Returns
    -------
    bool
        True if successful, False otherwise.
    """
    if h5py is None:
        return False

    try:
        print(f"Opening Imaris file with h5py: {input_path}")
        f = h5py.File(input_path, "r")
        res0 = f["/DataSet/ResolutionLevel 0"]
        t_key = f"TimePoint {timepoint - 1}"

        if t_key not in res0:
            print(f"Error: {t_key} not found in {input_path}")
            return False

        # Find all channel keys for this timepoint
        c_keys = sorted(
            [k for k in res0[t_key].keys() if k.startswith("Channel ")],
            key=lambda x: int(x.split()[-1]),
        )

        if not c_keys:
            print(f"No channels found in {t_key}")
            return False

        # Extract all channels as a list of dask arrays
        channel_arrays = []
        shape_zyx = None

        for c_key in c_keys:
            ds = res0[t_key][c_key]["Data"]
            if shape_zyx is None:
                shape_zyx = ds.shape
            channel_arrays.append(da.from_array(ds, chunks=ds.chunks))

        # Stack into (C, Z, Y, X)
        combined = da.stack(channel_arrays, axis=0)

        # Apply rechunking if target_chunks provided
        if target_chunks:
            if len(target_chunks) == combined.ndim:
                combined = combined.rechunk(target_chunks)
                print(f"Rechunking to: {target_chunks}")

        print(f"Writing {len(c_keys)} channels to Zarr: {output_path}...")
        start_time = time.time()
        combined.to_zarr(output_path, overwrite=True)
        print(f"Conversion completed in {time.time() - start_time:.2f}s.")

        # Write metadata to .zattrs
        anisotropy, pixel_sizes = get_ims_metadata(f, shape_zyx)
        if pixel_sizes:
            z = zarr.open(output_path, mode="a")
            # Store scale attribute (C, Z, Y, X)
            z.attrs["pixel_size"] = [1.0, *pixel_sizes]
            print(f"Metadata saved: pixel_size (C,Z,Y,X) = {z.attrs['pixel_size']}")
            if anisotropy:
                print(f"Suggested Anisotropy (Z/XY): {anisotropy:.3f}")

        return True
    except Exception as e:
        print(f"Imaris h5py conversion failed: {e}")
        return False


def convert_bioio_to_zarr(
    input_path: str,
    output_path: str,
    timepoint: int = 1,
    target_chunks: Optional[Tuple[int, ...]] = None,
) -> bool:
    """
    Convert all channels of any Bio-Formats file to Zarr using BioIO.

    Parameters
    ----------
    input_path : str
        Path to the source image file.
    output_path : str
        Path to the output .zarr directory.
    timepoint : int, optional
        1-based timepoint index to extract, by default 1.
    target_chunks : Optional[Tuple[int, ...]], optional
        Desired Zarr chunking, by default None.

    Returns
    -------
    bool
        True if successful, False otherwise.
    """
    if BioImage is None:
        return False

    try:
        print(f"Opening file with BioIO (BioImage): {input_path}")
        img = BioImage(input_path)
        data = img.data  # Usually (T, C, Z, Y, X)
        order = list(img.dims.order)

        # Slice timepoint if T exists
        slc = [slice(None)] * data.ndim
        if "T" in order:
            t_idx = order.index("T")
            slc[t_idx] = timepoint - 1

        d_sliced = data[tuple(slc)]
        remaining_order = [
            o for i, o in enumerate(order) if not isinstance(slc[i], int)
        ]

        # Apply rechunking
        if target_chunks:
            if len(target_chunks) == d_sliced.ndim:
                d_sliced = d_sliced.rechunk(target_chunks)
                print(f"Rechunking to: {target_chunks}")

        print(f"Writing to Zarr: {output_path}...")
        start_time = time.time()
        d_sliced.to_zarr(output_path, overwrite=True)
        print(f"Conversion completed in {time.time() - start_time:.2f}s.")

        # Write metadata
        try:
            px = img.physical_pixel_sizes
            if px.Z and px.Y and px.X:
                z = zarr.open(output_path, mode="a")
                # BioIO data is usually (C, Z, Y, X) after slicing T
                z.attrs["pixel_size"] = [1.0, px.Z, px.Y, px.X]
                print(f"Metadata saved: pixel_size (C,Z,Y,X) = {z.attrs['pixel_size']}")
                print(f"Suggested Anisotropy (Z/XY): {px.Z / px.Y:.3f}")
        except Exception:
            pass

        return True
    except Exception as e:
        print(f"BioIO conversion failed: {e}")
        return False


def main() -> None:
    """Parse arguments and execute conversion."""
    parser = argparse.ArgumentParser(description="Multi-Channel Zarr Converter")
    parser.add_argument("input", help="Input file (.ims, .czi, .nd2, etc.)")
    parser.add_argument("output", help="Output .zarr directory")
    parser.add_argument(
        "--timepoint", type=int, default=1, help="Timepoint index (1-based)"
    )
    parser.add_argument(
        "--chunks",
        default="1,64,512,512",
        help="Target Zarr chunk size (C,Z,Y,X), e.g. '1,64,512,512'",
    )

    args = parser.parse_args()

    # Parse chunks
    target_chunks = None
    if args.chunks:
        try:
            target_chunks = tuple(int(x) for x in args.chunks.split(","))
        except ValueError:
            print(f"Error parsing chunks: {args.chunks}")
            sys.exit(1)

    if not args.output.endswith(".zarr"):
        args.output += ".zarr"

    ext = os.path.splitext(args.input.rstrip("/"))[1].lower()

    if ext == ".ims":
        success = convert_ims_to_zarr(
            args.input, args.output, args.timepoint, target_chunks
        )
        if success:
            return

    # Fallback to BioIO
    if BioImage:
        success = convert_bioio_to_zarr(
            args.input, args.output, args.timepoint, target_chunks
        )
        if not success:
            sys.exit(1)
    else:
        print("Error: For non-IMS files or h5py failure, BioIO is required.")
        print("Install: pip install bioio bioio-bioformats bioio-ome-tiff")
        sys.exit(1)


if __name__ == "__main__":
    main()
