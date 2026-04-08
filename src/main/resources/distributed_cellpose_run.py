#!/usr/bin/env python
"""
Run distributed Cellpose segmentation on a Zarr or OME-Zarr input.

Design goals
------------
- Tune resolution level, blocksize, and worker count from both machine limits
  and image metadata.
- Keep compatibility with ``cellpose.contrib.distributed_segmentation``.
- Handle multi-channel inputs without relying on fragile output-index patches.

The main compatibility trick is a spatial-only adapter around channelled Zarr
arrays. ``distributed_segmentation.distributed_eval`` assumes that the input
array shape matches the output segmentation shape. That is false for channelled
inputs such as ``ZYXC``. The adapter exposes only the spatial axes for block
planning and output writing, but still reads the full channel data for each
block so preprocessing can extract or stack channels before Cellpose runs.

The script also applies two narrow compatibility patches needed for the current
Cellpose 3 + Zarr 3 environment:
- ``zarr.open(path, 'w', ...)`` positional-mode compatibility
- integer coercion for ``overlap`` and crop slices inside distributed helpers
"""

import argparse
import gc
from dataclasses import dataclass
import datetime
from importlib.metadata import PackageNotFoundError, version as package_version
import logging
import inspect
import math
import multiprocessing
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import webbrowser
import xml.etree.ElementTree as ET

import numpy as np
from scipy.ndimage import gaussian_filter
import tifffile
import zarr

try:
    from zarr.codecs import BloscCodec, BytesCodec, ShardingCodec
except ImportError:
    BloscCodec = BytesCodec = ShardingCodec = None


MIN_DIAMETER_PX = 15.0
TRAIN_DIAM_NUCLEI = 17.0
TRAIN_DIAM_CYTO = 30.0
BLOCK_OVERHEAD = 12.0
AUTO_RAM_FRACTION = 0.80
GPU_RAM_FRACTION = 0.85
MIN_3D_Z_DIAMETER_SLICES = 4.0
GLOBAL_LABEL_STRIDE = 100000
TIFF_TILE_EDGE = 256
TIFF_LABEL_COMPRESSION = "zlib"
OME_ZARR_CHUNK_Z = 8
OME_ZARR_CHUNK_YX = 1024
OME_ZARR_INNER_CHUNK_Z = 1
OME_ZARR_INNER_CHUNK_YX = 256
BUILTIN_MODEL_NAMES = {
    "cyto",
    "cyto2",
    "cyto3",
    "nuclei",
    "livecell",
    "tissuenet",
    "tissuenet_cp3",
    "cpsam",
}


@dataclass
class LevelInfo:
    key: str
    array: object
    x_factor: float = 1.0
    y_factor: float = 1.0
    z_factor: float = 1.0
    x_um: float | None = None
    y_um: float | None = None
    z_um: float | None = None


@dataclass
class ChannelPlan:
    input_zarr: object
    source_zarr: object
    source_channel_axis: int | None
    source_channel_count: int
    preprocessing_steps: list
    processed_channel_count: int


class SpatialChannelAdapter:
    """Expose a spatial-only Zarr-like view for channelled arrays."""

    def __init__(self, source, channel_axis):
        self.source = source
        self.source_channel_axis = normalize_axis(channel_axis, source.ndim)
        self.shape = tuple(
            size
            for axis, size in enumerate(source.shape)
            if axis != self.source_channel_axis
        )
        chunks = getattr(source, "chunks", None)
        self.chunks = (
            tuple(
                size
                for axis, size in enumerate(chunks)
                if axis != self.source_channel_axis
            )
            if chunks is not None
            else None
        )
        self.dtype = source.dtype
        self.ndim = len(self.shape)
        self.attrs = getattr(source, "attrs", {})

    def _normalize_key(self, key):
        if key is Ellipsis:
            return (slice(None),) * self.ndim
        if not isinstance(key, tuple):
            key = (key,)
        expanded = []
        saw_ellipsis = False
        for item in key:
            if item is Ellipsis and not saw_ellipsis:
                saw_ellipsis = True
                remaining = self.ndim - (len(key) - 1)
                expanded.extend([slice(None)] * remaining)
            elif item is not Ellipsis:
                expanded.append(item)
        if len(expanded) < self.ndim:
            expanded.extend([slice(None)] * (self.ndim - len(expanded)))
        return tuple(expanded[: self.ndim])

    def __getitem__(self, key):
        spatial_key = self._normalize_key(key)
        source_key = []
        spatial_index = 0
        for axis in range(self.source.ndim):
            if axis == self.source_channel_axis:
                source_key.append(slice(None))
            else:
                source_key.append(spatial_key[spatial_index])
                spatial_index += 1
        return self.source[tuple(source_key)]


class Tee:
    def __init__(self, stream, file_handle, lock=None):
        self.stream = stream
        self.file_handle = file_handle
        self.lock = lock or threading.Lock()

    def write(self, message):
        with self.lock:
            if self.stream:
                self.stream.write(message)
                try:
                    self.stream.flush()
                except Exception:
                    pass
            if self.file_handle:
                self.file_handle.write(message)
                self.file_handle.flush()

    def flush(self):
        with self.lock:
            if self.stream and hasattr(self.stream, "flush"):
                self.stream.flush()
            if self.file_handle and hasattr(self.file_handle, "flush"):
                self.file_handle.flush()


def update_log_handlers():
    try:
        root_logger = logging.getLogger()
        for handler in root_logger.handlers:
            if isinstance(handler, logging.StreamHandler):
                handler.setStream(sys.stderr)

        for name in logging.Logger.manager.loggerDict:
            log_instance = logging.getLogger(name)
            for handler in log_instance.handlers:
                if isinstance(handler, logging.StreamHandler):
                    handler.setStream(sys.stderr)
    except Exception:
        pass


def setup_persistent_process_log(output_path):
    output_directory = pathlib.Path(output_path).parent
    output_directory.mkdir(parents=True, exist_ok=True)

    timestamp = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
    log_path = output_directory / f"distributed_cellpose_{timestamp}.log"
    log_handle = open(log_path, "a", encoding="utf-8")
    log_lock = threading.Lock()

    sys.stdout = Tee(sys.stdout, log_handle, lock=log_lock)
    sys.stderr = Tee(sys.stderr, log_handle, lock=log_lock)
    update_log_handlers()

    print(f"Persistent process log created at: {log_path}")
    return log_handle, log_path


def parse_args():
    parser = argparse.ArgumentParser(
        description="Distributed Cellpose segmentation on a Zarr input"
    )
    parser.add_argument(
        "--zarr_input",
        default=None,
        help="Path to the input Zarr",
    )
    parser.add_argument(
        "--tiff_input_folder",
        default=None,
        help="Path to a tiled TIFF folder readable by Cellpose wrap_folder_of_tiffs",
    )
    parser.add_argument(
        "--tiff_glob",
        default=None,
        help="Optional TIFF glob pattern inside --tiff_input_folder, for example '*.tif'",
    )
    parser.add_argument(
        "--tiff_block_pattern",
        default=r"_(Z)(\d+)(Y)(\d+)(X)(\d+)",
        help="Regex used by Cellpose wrap_folder_of_tiffs to infer tile positions",
    )
    parser.add_argument(
        "--output_path",
        default=None,
        help="Path for the output labels, either an OME-TIFF file or an OME-Zarr directory.",
    )
    parser.add_argument(
        "--output_format",
        choices=("ome-tiff", "ome-zarr"),
        default="ome-tiff",
        help="Output format for the exported labels.",
    )
    parser.add_argument(
        "--output_tiff",
        default=None,
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--output_resolution",
        choices=("native", "level0"),
        default="native",
        help=(
            "Resolution of the written label image: 'native' keeps the selected working pyramid level, "
            "'level0' upsamples labels back to full resolution using nearest-neighbor only."
        ),
    )
    parser.add_argument(
        "--pyramidal_output",
        action="store_true",
        dest="pyramidal_output",
        help="Write a pyramidal OME-TIFF using nearest-neighbor label resampling only (default).",
    )
    parser.add_argument(
        "--no_pyramidal_output",
        action="store_false",
        dest="pyramidal_output",
        help="Write a single-resolution OME-TIFF instead of the default pyramidal output.",
    )
    parser.add_argument(
        "--model_type",
        default="cyto3",
        help="Cellpose 3 model name such as cyto3 or nuclei",
    )
    parser.add_argument(
        "--pretrained_model",
        default=None,
        help=(
            "Regular Cellpose-style pretrained model input. If this is an existing "
            "file path it is treated as a custom model; otherwise it is treated as "
            "a built-in model name."
        ),
    )
    parser.add_argument(
        "--diameter",
        type=float,
        default=30.0,
        help="Cell diameter in pixels at level 0 when --diameter_um is unset",
    )
    parser.add_argument(
        "--diameter_um",
        type=float,
        default=None,
        help="Cell diameter in micrometers; overrides --diameter when set",
    )
    parser.add_argument(
        "--pixel_size_xy_um",
        type=float,
        default=None,
        help="Level-0 XY pixel size in micrometers; overrides Zarr metadata",
    )
    parser.add_argument(
        "--pixel_size_z_um",
        type=float,
        default=None,
        help="Level-0 Z pixel size in micrometers; overrides Zarr metadata",
    )
    parser.add_argument(
        "--channel",
        type=int,
        default=None,
        help=(
            "User-friendly 1-based primary channel index. "
            "Converted internally to the 0-based channel Cellpose expects."
        ),
    )
    parser.add_argument(
        "--nucleus_channel",
        type=int,
        default=None,
        help=(
            "User-friendly 1-based secondary nucleus channel index. "
            "Converted internally to the 0-based channel Cellpose expects."
        ),
    )
    parser.add_argument(
        "--chan",
        dest="ch1",
        type=int,
        help="Regular Cellpose-style alias for --ch1",
    )
    parser.add_argument(
        "--ch1",
        type=int,
        default=-1,
        help="0-based cytoplasm channel index; -1 means grayscale mode",
    )
    parser.add_argument(
        "--chan2",
        dest="ch2",
        type=int,
        help="Regular Cellpose-style alias for --ch2",
    )
    parser.add_argument(
        "--ch2",
        type=int,
        default=-1,
        help="0-based nucleus channel index; -1 means none",
    )
    parser.add_argument(
        "--channel_axis",
        type=int,
        default=-1,
        help="Channel axis in the source array; default is the last axis",
    )
    parser.add_argument(
        "--blocksize",
        default="auto",
        help=(
            "Spatial block size as '128x512x512' or '128,512,512', or 'auto' to "
            "derive it from RAM, diameter, and chunking"
        ),
    )
    parser.add_argument(
        "--resolution_level",
        type=int,
        default=0,
        help="Pyramid level to process; default is 0, use -1 to select automatically",
    )
    parser.add_argument(
        "--auto_cluster",
        action="store_true",
        help="Derive worker count and memory limit from the local machine",
    )
    parser.add_argument("--n_workers", type=int, default=4)
    parser.add_argument(
        "--ncpus",
        type=int,
        default=4,
        help="CPU cores allocated per worker",
    )
    parser.add_argument(
        "--memory_per_worker",
        default="8GB",
        help="Worker memory limit such as 8GB",
    )
    parser.add_argument("--use_gpu", action="store_true")
    parser.add_argument("--do_3D", action="store_true")
    parser.add_argument(
        "--cellprob_threshold",
        type=float,
        default=0.0,
        help="Cellpose cell-probability threshold; higher values suppress weak masks and thin artifacts.",
    )
    parser.add_argument(
        "--min_size",
        type=int,
        default=15,
        help="Discard masks smaller than this many pixels/voxels after reconstruction.",
    )
    parser.add_argument(
        "--max_size_fraction",
        type=float,
        default=0.4,
        help="Discard masks larger than this fraction of the image/block volume.",
    )
    parser.add_argument(
        "--flow3D_smooth",
        type=float,
        default=1.0,
        help="Gaussian smoothing sigma applied to 3D flows before mask reconstruction when do_3D is enabled; Z smoothing is scaled by anisotropy.",
    )
    parser.add_argument(
        "--cellprob_smooth",
        type=float,
        default=0.0,
        help="Gaussian smoothing sigma applied to 3D cell probability before mask reconstruction; Z smoothing is scaled by anisotropy.",
    )
    parser.add_argument(
        "--no_resample",
        action="store_true",
        help=(
            "Skip Cellpose flow and cell-probability resampling back to the original "
            "block size. This is faster, especially for do_3D, but can reduce mask quality."
        ),
    )
    parser.add_argument(
        "--anisotropy",
        type=float,
        default=None,
        help="Explicit Z/XY voxel size ratio; overrides metadata",
    )
    parser.add_argument(
        "--dry_run",
        action="store_true",
        help="Print the computed plan and exit before importing Cellpose",
    )
    parser.add_argument(
        "--dask_temp_directory",
        default=None,
        help="Optional local directory for Dask scratch data; defaults to the system temp directory",
    )
    parser.add_argument(
        "--no_open_dask_dashboard",
        action="store_false",
        dest="open_dask_dashboard",
        help="Disable automatic opening of the local Dask dashboard in a browser",
    )
    parser.set_defaults(open_dask_dashboard=True, pyramidal_output=True)
    return parser.parse_args()


def get_installed_version(name, default="0.0.0"):
    try:
        return package_version(name)
    except PackageNotFoundError:
        return default


def normalize_axis(axis, ndim):
    normalized = axis if axis >= 0 else ndim + axis
    if normalized < 0 or normalized >= ndim:
        raise ValueError(f"Axis {axis} is out of range for ndim={ndim}.")
    return normalized


def resolve_output_args(args):
    if args.output_path is None and args.output_tiff is None:
        sys.exit("ERROR: provide --output_path, or use the deprecated --output_tiff alias.")

    if args.output_path is None and args.output_tiff is not None:
        args.output_path = args.output_tiff
        args.output_format = "ome-tiff"
        return

    if args.output_tiff is not None and args.output_path != args.output_tiff:
        sys.exit("ERROR: --output_path and --output_tiff refer to different locations.")

    if args.output_tiff is not None:
        args.output_format = "ome-tiff"

    output_path = pathlib.Path(args.output_path)
    if args.output_format == "ome-zarr" and output_path.suffix.lower() != ".zarr":
        print(
            "WARNING: --output_format ome-zarr usually writes to a path ending in '.zarr'."
        )
    if args.output_format == "ome-tiff" and output_path.suffix.lower() not in {".tif", ".tiff", ".ome.tif", ".ome.tiff"}:
        print(
            "WARNING: --output_format ome-tiff usually writes to a path ending in '.ome.tif' or '.tif'."
        )


def parse_spatial_blocksize(raw_blocksize, spatial_ndim, channel_axis, source_ndim):
    values = [int(part.strip()) for part in raw_blocksize.replace("x", ",").split(",")]
    if len(values) == spatial_ndim:
        return values
    if len(values) == source_ndim:
        normalized_channel_axis = normalize_axis(channel_axis, source_ndim)
        return [
            value for axis, value in enumerate(values) if axis != normalized_channel_axis
        ]
    raise ValueError(
        f"Blocksize '{raw_blocksize}' has {len(values)} dimension(s), expected "
        f"{spatial_ndim} spatial dimension(s)"
        + (f" or {source_ndim} raw dimension(s)." if source_ndim != spatial_ndim else ".")
    )


def get_available_ram_bytes():
    try:
        import psutil

        return int(psutil.virtual_memory().available)
    except ImportError:
        pass

    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass

    print(
        "WARNING: could not determine available RAM accurately; assuming 8 GiB."
    )
    return 8 * 1024**3


def get_available_gpu_bytes():
    try:
        completed = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=memory.free",
                "--format=csv,noheader,nounits",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        values = [
            int(line.strip())
            for line in completed.stdout.splitlines()
            if line.strip()
        ]
        if values:
            return max(values) * 1024**2
    except Exception:
        pass

    try:
        import torch

        if torch.cuda.is_available():
            free_bytes, _total_bytes = torch.cuda.mem_get_info()
            return int(free_bytes)
    except Exception:
        pass

    print("WARNING: could not determine free GPU memory; assuming 8 GiB.")
    return 8 * 1024**3


def prev_power_of_two(value):
    if value <= 1:
        return 1
    return 2 ** int(math.log2(value))


def format_gib_string(byte_count):
    gib = max(2, int(byte_count // (1024**3)))
    return f"{gib}GB"


def resolve_dask_temp_directory(args):
    if args.dask_temp_directory:
        temp_dir = pathlib.Path(args.dask_temp_directory)
    else:
        base_temp = pathlib.Path(tempfile.gettempdir())
        temp_dir = base_temp / "distributed_cellpose_dask"
    temp_dir.mkdir(parents=True, exist_ok=True)
    return str(temp_dir)


def resolve_user_channel_args(args):
    if args.ch1 is None:
        args.ch1 = -1
    if args.ch2 is None:
        args.ch2 = -1

    if args.channel is not None:
        if args.channel < 1:
            sys.exit("ERROR: --channel is 1-based and must be >= 1.")
        if args.ch1 != -1:
            sys.exit("ERROR: use either --channel or --ch1, not both.")
        args.ch1 = args.channel - 1

    if args.nucleus_channel is not None:
        if args.nucleus_channel < 1:
            sys.exit("ERROR: --nucleus_channel is 1-based and must be >= 1.")
        if args.ch2 != -1:
            sys.exit("ERROR: use either --nucleus_channel or --ch2, not both.")
        args.ch2 = args.nucleus_channel - 1


def resolve_pretrained_model_alias(args):
    if not args.pretrained_model:
        return

    value = args.pretrained_model.strip()
    if not value:
        args.pretrained_model = None
        return

    if os.path.exists(value):
        args.pretrained_model = value
        return

    lower_value = value.lower()
    if lower_value in BUILTIN_MODEL_NAMES:
        args.model_type = value
        args.pretrained_model = None
        return

    if any(separator in value for separator in (os.sep, "/", "\\")):
        sys.exit(
            f"ERROR: --pretrained_model='{value}' looks like a file path but does not exist."
        )


def resolve_input_source_args(args):
    has_zarr = bool(args.zarr_input)
    has_tiff_folder = bool(args.tiff_input_folder)

    if has_zarr == has_tiff_folder:
        sys.exit(
            "ERROR: provide exactly one of --zarr_input or --tiff_input_folder."
        )

    if has_tiff_folder and not os.path.isdir(args.tiff_input_folder):
        sys.exit(
            f"ERROR: TIFF input folder does not exist: {args.tiff_input_folder}"
        )


def infer_axes_names(ndim):
    if ndim == 2:
        return ["y", "x"]
    if ndim == 3:
        return ["z", "y", "x"]
    if ndim == 4:
        return ["z", "y", "x", "c"]
    return [f"axis_{index}" for index in range(ndim)]


def read_root_pixel_sizes(root):
    raw = dict(getattr(root, "attrs", {})).get("physical_pixel_sizes_um", {})
    return {
        "Z": raw.get("Z"),
        "Y": raw.get("Y"),
        "X": raw.get("X"),
    }


def find_tiff_folder_files(folder_path, glob_pattern):
    candidate_patterns = []
    if glob_pattern:
        candidate_patterns.append(os.path.join(folder_path, glob_pattern))
    else:
        candidate_patterns.extend(
            [
                os.path.join(folder_path, "*.tif"),
                os.path.join(folder_path, "*.tiff"),
            ]
        )

    for pattern in candidate_patterns:
        matches = sorted(pathlib.Path(folder_path).glob(pathlib.Path(pattern).name))
        if matches:
            return pattern, matches
    return None, []


def convert_unit_to_um(value, unit):
    if value is None or unit is None:
        return None
    unit_str = str(unit).strip().lower()
    if unit_str in {"um", "micrometer", "micrometers", "micron", "microns"}:
        return float(value)
    if unit_str in {"nm", "nanometer", "nanometers"}:
        return float(value) / 1000.0
    if unit_str in {"mm", "millimeter", "millimeters"}:
        return float(value) * 1000.0
    if unit_str in {"cm", "centimeter", "centimeters"}:
        return float(value) * 10000.0
    if unit_str in {"m", "meter", "meters"}:
        return float(value) * 1_000_000.0
    if unit_str in {"inch", "inches", "in"}:
        return float(value) * 25400.0
    return None


def resolution_tag_to_um(resolution_tag, resolution_unit_tag):
    if resolution_tag is None or resolution_unit_tag is None:
        return None

    try:
        resolution_value = resolution_tag.value
    except Exception:
        resolution_value = resolution_tag

    try:
        unit_value = resolution_unit_tag.value
    except Exception:
        unit_value = resolution_unit_tag

    if isinstance(resolution_value, tuple):
        numerator, denominator = resolution_value
        pixels_per_unit = float(numerator) / float(denominator)
    else:
        pixels_per_unit = float(resolution_value)

    if pixels_per_unit <= 0:
        return None

    unit_name = str(unit_value).lower()
    if unit_name in {"2", "resolutionunit.inch"} or "inch" in unit_name:
        return 25400.0 / pixels_per_unit
    if unit_name in {"3", "resolutionunit.centimeter"} or "centimeter" in unit_name:
        return 10000.0 / pixels_per_unit
    return None


def parse_ome_physical_sizes(ome_xml):
    sizes = {"Z": None, "Y": None, "X": None}
    if not ome_xml:
        return sizes

    try:
        root = ET.fromstring(ome_xml)
    except Exception:
        return sizes

    pixels_element = None
    for element in root.iter():
        if element.tag.endswith("Pixels"):
            pixels_element = element
            break

    if pixels_element is None:
        return sizes

    for axis in ("X", "Y", "Z"):
        value = pixels_element.attrib.get(f"PhysicalSize{axis}")
        unit = pixels_element.attrib.get(f"PhysicalSize{axis}Unit", "um")
        if value is not None:
            try:
                sizes[axis] = convert_unit_to_um(float(value), unit)
            except Exception:
                sizes[axis] = None
    return sizes


def extract_tiff_folder_pixel_sizes(folder_path, glob_pattern):
    _pattern, files = find_tiff_folder_files(folder_path, glob_pattern)
    sizes = {"Z": None, "Y": None, "X": None}
    if not files:
        return sizes

    first_file = files[0]
    try:
        with tifffile.TiffFile(str(first_file)) as tif:
            ome_sizes = parse_ome_physical_sizes(tif.ome_metadata)
            for axis in sizes:
                if ome_sizes[axis] is not None:
                    sizes[axis] = ome_sizes[axis]

            imagej_metadata = tif.imagej_metadata or {}
            imagej_unit = imagej_metadata.get("unit", "um")
            spacing = imagej_metadata.get("spacing")
            if sizes["Z"] is None and spacing is not None:
                try:
                    sizes["Z"] = convert_unit_to_um(float(spacing), imagej_unit)
                except Exception:
                    pass

            page = tif.series[0].pages[0]
            if sizes["X"] is None:
                sizes["X"] = resolution_tag_to_um(
                    page.tags.get("XResolution"),
                    page.tags.get("ResolutionUnit"),
                )
            if sizes["Y"] is None:
                sizes["Y"] = resolution_tag_to_um(
                    page.tags.get("YResolution"),
                    page.tags.get("ResolutionUnit"),
                )
    except Exception as error:
        print(f"WARNING: could not read TIFF calibration metadata: {error}")

    return sizes


def read_axes_metadata(root, fallback_ndim):
    multiscales = dict(getattr(root, "attrs", {})).get("multiscales", [])
    if multiscales:
        axes = multiscales[0].get("axes", [])
        names = [axis.get("name", "") for axis in axes]
        if names:
            return names
    return infer_axes_names(fallback_ndim)


def read_dataset_scale_map(root):
    multiscales = dict(getattr(root, "attrs", {})).get("multiscales", [])
    if not multiscales:
        return {}
    datasets = multiscales[0].get("datasets", [])
    scale_map = {}
    for dataset in datasets:
        path = str(dataset.get("path"))
        scale = None
        for transform in dataset.get("coordinateTransformations", []):
            if transform.get("type") == "scale":
                scale = transform.get("scale")
                break
        if scale is not None:
            scale_map[path] = scale
    return scale_map


def build_level_infos(root, root_pixel_sizes):
    if isinstance(root, zarr.Array):
        return [LevelInfo(key="0", array=root)]

    level_keys = sorted((key for key in root.keys() if str(key).isdigit()), key=int)
    if not level_keys:
        raise ValueError(
            f"Zarr group at {root.store.path if hasattr(root.store, 'path') else 'input'} "
            "has no numeric pyramid levels."
        )

    first_array = root[level_keys[0]]
    axes_names = read_axes_metadata(root, first_array.ndim)
    axis_lookup = {name.lower(): index for index, name in enumerate(axes_names)}
    scale_map = read_dataset_scale_map(root)
    base_scale = scale_map.get(level_keys[0])
    base_shape = first_array.shape

    level_infos = []
    for key in level_keys:
        array = root[key]
        info = LevelInfo(key=str(key), array=array)
        scale = scale_map.get(str(key))
        if scale is not None and base_scale is not None:
            if "x" in axis_lookup:
                info.x_um = float(scale[axis_lookup["x"]])
                info.x_factor = info.x_um / float(base_scale[axis_lookup["x"]])
            if "y" in axis_lookup:
                info.y_um = float(scale[axis_lookup["y"]])
                info.y_factor = info.y_um / float(base_scale[axis_lookup["y"]])
            if "z" in axis_lookup:
                info.z_um = float(scale[axis_lookup["z"]])
                info.z_factor = info.z_um / float(base_scale[axis_lookup["z"]])
        else:
            if len(base_shape) >= 1 and len(array.shape) >= 1:
                info.x_factor = base_shape[-2] / array.shape[-2] if array.ndim >= 2 else 1.0
                info.y_factor = base_shape[-1] / array.shape[-1] if array.ndim >= 2 else 1.0
                info.z_factor = base_shape[0] / array.shape[0] if array.ndim >= 3 else 1.0
            if root_pixel_sizes["X"] is not None:
                info.x_um = float(root_pixel_sizes["X"]) * info.x_factor
            if root_pixel_sizes["Y"] is not None:
                info.y_um = float(root_pixel_sizes["Y"]) * info.y_factor
            if root_pixel_sizes["Z"] is not None:
                info.z_um = float(root_pixel_sizes["Z"]) * info.z_factor
        if info.x_um is None and root_pixel_sizes["X"] is not None:
            info.x_um = float(root_pixel_sizes["X"]) * info.x_factor
        if info.y_um is None and root_pixel_sizes["Y"] is not None:
            info.y_um = float(root_pixel_sizes["Y"]) * info.y_factor
        if info.z_um is None and root_pixel_sizes["Z"] is not None:
            info.z_um = float(root_pixel_sizes["Z"]) * info.z_factor
        level_infos.append(info)

    return level_infos


def resolve_base_pixel_sizes(args, root_pixel_sizes):
    base_x = args.pixel_size_xy_um or root_pixel_sizes["X"] or root_pixel_sizes["Y"]
    base_y = args.pixel_size_xy_um or root_pixel_sizes["Y"] or root_pixel_sizes["X"]
    base_z = args.pixel_size_z_um or root_pixel_sizes["Z"]
    return {"X": base_x, "Y": base_y, "Z": base_z}


def resolve_full_resolution_diameter_px(args, base_pixel_sizes):
    if args.diameter_um is None:
        return float(args.diameter)
    xy_um = base_pixel_sizes["X"] or base_pixel_sizes["Y"]
    if xy_um is None:
        sys.exit(
            "ERROR: --diameter_um was provided but no level-0 XY pixel size is available."
        )
    diameter_px = float(args.diameter_um) / float(xy_um)
    print(f"Diameter: {args.diameter_um} um -> {diameter_px:.2f} px at level 0")
    return diameter_px


def select_resolution_level(level_infos, args, full_diameter_px):
    if len(level_infos) == 1:
        print("Input is a flat Zarr array; using level 0.")
        return level_infos[0]

    if args.resolution_level >= 0:
        chosen_index = min(args.resolution_level, len(level_infos) - 1)
        chosen = level_infos[chosen_index]
        effective = full_diameter_px / max(chosen.x_factor, 1.0)
        print(
            f"Using explicitly requested pyramid level {chosen.key} "
            f"(effective diameter {effective:.2f} px)."
        )
        return chosen

    train_diam = training_diameter_px(args)
    chosen = level_infos[0]
    best_score = None
    for candidate in level_infos:
        xy_factor = max(candidate.x_factor or 1.0, candidate.y_factor or 1.0, 1.0)
        effective = full_diameter_px / xy_factor
        score = abs(math.log(max(effective, 1e-6) / train_diam))

        if effective < MIN_DIAMETER_PX:
            score += 10.0 + (MIN_DIAMETER_PX - effective)

        if args.do_3D and args.anisotropy is not None:
            effective_z_diameter = effective / max(args.anisotropy, 1e-6)
            if effective_z_diameter < MIN_3D_Z_DIAMETER_SLICES:
                score += 5.0 + (MIN_3D_Z_DIAMETER_SLICES - effective_z_diameter)

        if best_score is None or score < best_score or (
            math.isclose(score, best_score)
            and xy_factor
            < max(chosen.x_factor or 1.0, chosen.y_factor or 1.0, 1.0)
        ):
            best_score = score
            chosen = candidate
    effective = full_diameter_px / max(chosen.x_factor or 1.0, 1.0)
    print(
        f"Auto-selected pyramid level {chosen.key} "
        f"(effective diameter {effective:.2f} px, target train diameter {train_diam:.1f} px)."
    )
    return chosen


def resolve_output_pixel_sizes(level_info, base_pixel_sizes):
    x_um = level_info.x_um or (
        float(base_pixel_sizes["X"]) * level_info.x_factor
        if base_pixel_sizes["X"] is not None
        else None
    )
    y_um = level_info.y_um or (
        float(base_pixel_sizes["Y"]) * level_info.y_factor
        if base_pixel_sizes["Y"] is not None
        else None
    )
    z_um = level_info.z_um or (
        float(base_pixel_sizes["Z"]) * level_info.z_factor
        if base_pixel_sizes["Z"] is not None
        else None
    )
    return {"X": x_um, "Y": y_um, "Z": z_um}


def resolve_anisotropy(args, chosen_pixel_sizes):
    if args.anisotropy is not None:
        print(f"Anisotropy override: {args.anisotropy:.4f}")
        return float(args.anisotropy)
    z_um = chosen_pixel_sizes["Z"]
    xy_um = chosen_pixel_sizes["X"] or chosen_pixel_sizes["Y"]
    if z_um is not None and xy_um is not None:
        anisotropy = float(z_um) / float(xy_um)
        print(
            f"Anisotropy from pixel sizes: {z_um} um / {xy_um} um = {anisotropy:.4f}"
        )
        return anisotropy
    print("Anisotropy fallback: 1.0")
    return 1.0


def detect_channel_axis(array, channel_axis_argument):
    if array.ndim <= 3:
        return None
    return normalize_axis(channel_axis_argument, array.ndim)


def validate_channel_index(index, channel_count, label):
    if index < 0 or index >= channel_count:
        raise ValueError(
            f"{label}={index} is out of range for {channel_count} channel(s)."
        )


def extract_single_channel(image, idx, channel_axis, crop=None):
    del crop
    return np.take(image, idx, axis=channel_axis)


def stack_two_channels(image, cyto_idx, nuc_idx, channel_axis, crop=None):
    del crop
    cyto = np.take(image, cyto_idx, axis=channel_axis)
    nuc = np.take(image, nuc_idx, axis=channel_axis)
    return np.stack((cyto, nuc), axis=-1)


def build_channel_plan(array, args):
    source_channel_axis = detect_channel_axis(array, args.channel_axis)
    if source_channel_axis is None:
        return ChannelPlan(
            input_zarr=array,
            source_zarr=array,
            source_channel_axis=None,
            source_channel_count=1,
            preprocessing_steps=[],
            processed_channel_count=1,
        )

    source_channel_count = int(array.shape[source_channel_axis])
    adapter = SpatialChannelAdapter(array, source_channel_axis)

    if args.ch1 == -1:
        if source_channel_count != 1:
            sys.exit(
                "ERROR: multi-channel input requires --ch1 to select the cytoplasm channel."
            )
        preprocessing_steps = [
            (
                extract_single_channel,
                {"idx": 0, "channel_axis": source_channel_axis},
            )
        ]
        processed_channel_count = 1
    elif args.ch2 == -1:
        validate_channel_index(args.ch1, source_channel_count, "--ch1")
        preprocessing_steps = [
            (
                extract_single_channel,
                {"idx": args.ch1, "channel_axis": source_channel_axis},
            )
        ]
        processed_channel_count = 1
    else:
        validate_channel_index(args.ch1, source_channel_count, "--ch1")
        validate_channel_index(args.ch2, source_channel_count, "--ch2")
        preprocessing_steps = [
            (
                stack_two_channels,
                {
                    "cyto_idx": args.ch1,
                    "nuc_idx": args.ch2,
                    "channel_axis": source_channel_axis,
                },
            )
        ]
        processed_channel_count = 2

    return ChannelPlan(
        input_zarr=adapter,
        source_zarr=array,
        source_channel_axis=source_channel_axis,
        source_channel_count=source_channel_count,
        preprocessing_steps=preprocessing_steps,
        processed_channel_count=processed_channel_count,
    )


def training_diameter_px(args):
    if args.pretrained_model and os.path.exists(args.pretrained_model):
        return TRAIN_DIAM_CYTO
    if "nuclei" in (args.model_type or "").lower():
        return TRAIN_DIAM_NUCLEI
    return TRAIN_DIAM_CYTO


def auto_configure_cluster(args, avail_ram):
    total_cpus = multiprocessing.cpu_count()
    cpus_per_worker = max(1, int(args.ncpus))

    if args.use_gpu:
        args.n_workers = 1
        args.memory_per_worker = format_gib_string(int(avail_ram * AUTO_RAM_FRACTION))
        print(
            f"Auto cluster: GPU mode -> 1 worker, {args.memory_per_worker} memory limit."
        )
        return

    max_workers_from_cpu = max(1, total_cpus // cpus_per_worker)
    reserve_ram = int(avail_ram * AUTO_RAM_FRACTION)
    min_worker_budget = 2 * 1024**3
    max_workers_from_ram = max(1, reserve_ram // min_worker_budget)
    args.n_workers = max(1, min(max_workers_from_cpu, max_workers_from_ram, 16))
    args.memory_per_worker = format_gib_string(reserve_ram // args.n_workers)
    print(
        f"Auto cluster: {total_cpus} CPUs -> {args.n_workers} workers x {cpus_per_worker} CPUs, "
        f"{args.memory_per_worker} per worker."
    )


def align_blocksize_to_chunks(blocksize, shape, chunks):
    if chunks is None:
        return [int(min(length, max(1, size))) for size, length in zip(blocksize, shape)]
    aligned = []
    for size, length, chunk in zip(blocksize, shape, chunks):
        if chunk and chunk > 0:
            aligned_size = int(math.ceil(size / chunk) * chunk)
        else:
            aligned_size = int(size)
        aligned.append(int(min(length, max(1, aligned_size))))
    return aligned


def resolve_blocksize(args, input_zarr, diameter_px, anisotropy, avail_ram, processed_channels):
    shape = tuple(int(value) for value in input_zarr.shape)
    spatial_ndim = input_zarr.ndim
    source_ndim = getattr(getattr(input_zarr, "source", input_zarr), "ndim", spatial_ndim)

    if args.blocksize != "auto":
        return parse_spatial_blocksize(
            args.blocksize,
            spatial_ndim=spatial_ndim,
            channel_axis=args.channel_axis,
            source_ndim=source_ndim,
        )

    dtype_bytes = np.dtype(input_zarr.dtype).itemsize
    if args.use_gpu:
        mem_budget = getattr(args, "processing_memory_budget_bytes", None)
        if mem_budget is None:
            mem_budget = get_available_gpu_bytes() * GPU_RAM_FRACTION
    else:
        mem_budget = avail_ram * AUTO_RAM_FRACTION / max(1, int(args.n_workers))
    train_diam = training_diameter_px(args)
    safe_diameter_px = max(float(diameter_px), train_diam / 2.0, 1.0)
    r_xy = train_diam / safe_diameter_px
    effective_bytes = dtype_bytes * max(1, int(processed_channels))

    if args.do_3D and spatial_ndim == 3:
        # Cellpose do_3D is still dominated by 2D-style inference passes, so XY
        # memory is the main constraint. Z should stay large enough for robust
        # 3D stitching without forcing cubic blocks.
        max_rescaled_area = mem_budget / (effective_bytes * BLOCK_OVERHEAD)
        raw_edge_xy = math.sqrt(max_rescaled_area) / max(r_xy, 1e-6)
        edge_xy = prev_power_of_two(raw_edge_xy)
        min_xy = max(int(math.ceil(5 * diameter_px)), 64)
        if args.use_gpu:
            max_xy_cap = 2048 if not args.no_resample else 4096
        else:
            max_xy_cap = 1024
        edge_xy = max(min_xy, min(edge_xy, max_xy_cap, shape[-1], shape[-2]))

        target_z_context = max(
            int(math.ceil(6 * diameter_px / max(anisotropy, 1e-3))),
            int(math.ceil(3 * MIN_3D_Z_DIAMETER_SLICES)),
            16,
        )
        edge_z = prev_power_of_two(target_z_context)
        edge_z_cap = 128
        edge_z = max(16, min(edge_z, edge_z_cap, shape[0]))
        blocksize = [edge_z, edge_xy, edge_xy]
    else:
        max_rescaled_area = mem_budget / (effective_bytes * BLOCK_OVERHEAD)
        raw_edge_xy = math.sqrt(max_rescaled_area) / max(r_xy, 1e-6)
        edge_xy = prev_power_of_two(raw_edge_xy)
        min_xy = max(int(math.ceil(5 * diameter_px)), 64)
        max_xy_cap = 4096 if args.use_gpu else 1024
        edge_xy = max(min_xy, min(edge_xy, max_xy_cap))
        if spatial_ndim >= 2:
            edge_xy = min(edge_xy, shape[-1], shape[-2])

        if spatial_ndim == 3:
            edge_z = min(shape[0], max(32, min(256, prev_power_of_two(shape[0]))))
            blocksize = [edge_z, edge_xy, edge_xy]
        else:
            blocksize = [edge_xy, edge_xy]

    blocksize = align_blocksize_to_chunks(blocksize, shape, getattr(input_zarr, "chunks", None))
    print(
        f"Auto blocksize: {blocksize} "
        f"(diameter_px={diameter_px:.2f}, train_diam={train_diam:.0f}, "
        f"processed_channels={processed_channels}, mem_budget={mem_budget / 1024**3:.2f} GiB, "
        f"use_gpu={args.use_gpu})"
    )
    return blocksize


def estimate_total_blocks(shape, blocksize):
    return int(np.prod(np.ceil(np.array(shape) / np.array(blocksize)).astype(int)))


def maybe_reduce_workers_after_blocksize(args, avail_ram, total_blocks):
    if not args.auto_cluster:
        return
    if total_blocks < args.n_workers:
        args.n_workers = max(1, total_blocks)
        args.memory_per_worker = format_gib_string(
            int(avail_ram * AUTO_RAM_FRACTION) // args.n_workers
        )
        print(
            f"Auto cluster adjustment: reduced worker count to {args.n_workers} "
            f"for {total_blocks} planned block(s)."
        )


def build_model_kwargs(args, cp_major):
    if args.pretrained_model and os.path.exists(args.pretrained_model):
        print(f"Using custom pretrained model: {args.pretrained_model}")
        return {"pretrained_model": args.pretrained_model, "gpu": args.use_gpu}

    if cp_major >= 4:
        if args.model_type != "cpsam":
            print(
                f"NOTE: Cellpose {cp_major} detected; built-in model '{args.model_type}' "
                "maps to 'cpsam'."
            )
        return {"pretrained_model": "cpsam", "gpu": args.use_gpu}

    return {"model_type": args.model_type, "gpu": args.use_gpu}


def build_eval_kwargs(args, diameter_px, cp_major, processed_channel_count):
    eval_kwargs = {
        "diameter": float(diameter_px),
        "do_3D": args.do_3D,
        "anisotropy": float(args.anisotropy),
        "resample": not args.no_resample,
        "cellprob_threshold": float(args.cellprob_threshold),
        "min_size": int(args.min_size),
        "max_size_fraction": float(args.max_size_fraction),
    }
    if cp_major < 4:
        if processed_channel_count == 1:
            eval_kwargs["channels"] = [0, 0]
        else:
            eval_kwargs["channels"] = [1, 2]
    return eval_kwargs


def open_root_zarr(path):
    print(f"Opening Zarr: {path}")
    try:
        root = zarr.open_consolidated(path, mode="r")
        print("Using consolidated metadata.")
        return root
    except Exception:
        return zarr.open(path, mode="r")


def wrap_folder_of_tiffs_compat(
    filename_pattern,
    block_index_pattern=r"_(Z)(\d+)(Y)(\d+)(X)(\d+)",
):
    # Adapted from cellpose.contrib.distributed_segmentation.wrap_folder_of_tiffs
    # so TIFF folders can be planned without importing the full Cellpose stack.
    import imagecodecs

    def imread(filename):
        with open(filename, "rb") as handle:
            return imagecodecs.tiff_decode(handle.read(), index=None)

    store = tifffile.imread(
        filename_pattern,
        aszarr=True,
        imread=imread,
        pattern=block_index_pattern,
        axestiled={axis: axis for axis in range(3)},
    )
    return zarr.open(store=store)


def wrap_tiff_sequence_compat(filename_pattern):
    store = tifffile.imread(filename_pattern, aszarr=True)
    return zarr.open(store=store)


def load_tiff_folder_input(folder_path, glob_pattern, block_pattern):
    selected_pattern, selected_files = find_tiff_folder_files(folder_path, glob_pattern)

    if not selected_files:
        sys.exit(
            f"ERROR: no TIFF files found in {folder_path}."
        )

    print(
        f"Opening TIFF folder input: {folder_path} "
        f"({len(selected_files)} files, pattern={pathlib.Path(selected_pattern).name})"
    )

    if len(selected_files) > 1:
        try:
            print("Trying Cellpose tiled TIFF wrapper.")
            return wrap_folder_of_tiffs_compat(selected_pattern, block_pattern)
        except Exception as error:
            print(
                "Tiled TIFF wrapper failed; falling back to TIFF sequence wrapper. "
                f"Reason: {error}"
            )

    print("Using TIFF sequence wrapper.")
    return wrap_tiff_sequence_compat(selected_pattern)


def open_input_source(args):
    if args.zarr_input:
        return open_root_zarr(args.zarr_input), "zarr", {"Z": None, "Y": None, "X": None}

    patch_zarr_open_for_cellpose()
    wrapped = load_tiff_folder_input(
        args.tiff_input_folder,
        args.tiff_glob,
        args.tiff_block_pattern,
    )
    extracted_pixel_sizes = extract_tiff_folder_pixel_sizes(
        args.tiff_input_folder,
        args.tiff_glob,
    )
    return wrapped, "tiff-folder", extracted_pixel_sizes


def patch_zarr_open_for_cellpose():
    if getattr(zarr.open, "_cellpose_compat_patch", False):
        return

    original_zarr_open = zarr.open

    def patched_zarr_open(*args, **kwargs):
        if len(args) > 1:
            path = args[0]
            mode = args[1]
            kwargs["mode"] = mode
            return original_zarr_open(path, *args[2:], **kwargs)
        return original_zarr_open(*args, **kwargs)

    patched_zarr_open._cellpose_compat_patch = True
    zarr.open = patched_zarr_open


def patch_distributed_segmentation(module):
    if getattr(module, "_ijl_wrappers_compat_patch", False):
        return

    original_distributed_eval = module.distributed_eval
    original_get_block_crops = module.get_block_crops
    original_remove_overlaps = module.remove_overlaps
    original_local_cluster_init = module.myLocalCluster.__init__

    def patched_get_block_crops(shape, blocksize, overlap, mask):
        blocksize = np.asarray(blocksize, dtype=int)
        overlap_int = int(math.ceil(float(overlap)))
        indices, crops = original_get_block_crops(shape, blocksize, overlap_int, mask)
        fixed_crops = []
        for crop in crops:
            fixed_crops.append(
                tuple(
                    slice(
                        int(item.start) if item.start is not None else None,
                        int(item.stop) if item.stop is not None else None,
                        item.step,
                    )
                    for item in crop
                )
            )
        return indices, fixed_crops

    def patched_remove_overlaps(array, crop, overlap, blocksize):
        fixed_crop = tuple(
            slice(
                int(item.start) if item.start is not None else None,
                int(item.stop) if item.stop is not None else None,
                item.step,
            )
            for item in crop
        )
        fixed_blocksize = np.asarray(blocksize, dtype=int)
        return original_remove_overlaps(
            array,
            fixed_crop,
            int(math.ceil(float(overlap))),
            fixed_blocksize,
        )

    def patched_local_cluster_init(self, ncpus, config={}, config_name=module.DEFAULT_CONFIG_FILENAME, persist_config=False, **kwargs):
        default_temp_dir = getattr(module, "_ijl_dask_temp_directory", None)
        merged_config = dict(config)
        if default_temp_dir:
            merged_config.setdefault("temporary-directory", default_temp_dir)
        original_local_cluster_init(
            self,
            ncpus,
            config=merged_config,
            config_name=config_name,
            persist_config=persist_config,
            **kwargs,
        )
        if getattr(module, "_ijl_open_dask_dashboard", False):
            try:
                webbrowser.open(self.dashboard_link)
            except Exception as error:
                print(f"WARNING: could not open Dask dashboard automatically: {error}")

    def patched_distributed_eval(*args, **kwargs):
        working_directory = kwargs.get("temporary_directory")
        if not working_directory:
            return original_distributed_eval(*args, **kwargs)

        pathlib.Path(working_directory).mkdir(parents=True, exist_ok=True)
        previous_cwd = os.getcwd()
        try:
            os.chdir(working_directory)
            print(f"Dask worker logs will be written under: {working_directory}")
            return original_distributed_eval(*args, **kwargs)
        finally:
            os.chdir(previous_cwd)

    def patched_process_block(
        block_index,
        crop,
        input_zarr,
        model_kwargs,
        eval_kwargs,
        blocksize,
        overlap,
        output_zarr,
        preprocessing_steps=[],
        worker_logs_directory=None,
        test_mode=False,
    ):
        print("RUNNING BLOCK: ", block_index, "\tREGION: ", crop, flush=True)
        segmentation = read_preprocess_and_segment(
            input_zarr,
            crop,
            preprocessing_steps,
            model_kwargs,
            eval_kwargs,
            worker_logs_directory,
        )
        fixed_crop = tuple(
            slice(
                int(item.start) if item.start is not None else None,
                int(item.stop) if item.stop is not None else None,
                item.step,
            )
            for item in crop
        )
        fixed_blocksize = np.asarray(blocksize, dtype=int)
        segmentation, crop = original_remove_overlaps(
            segmentation,
            fixed_crop,
            int(math.ceil(float(overlap))),
            fixed_blocksize,
        )

        boxes = []
        local_ids = []
        labeled_boxes = module.scipy.ndimage.find_objects(segmentation)
        for label_id, box in enumerate(labeled_boxes, start=1):
            if box is None:
                continue
            boxes.append(
                tuple(
                    slice(axis_crop.start + axis_box.start, axis_crop.start + axis_box.stop)
                    for axis_crop, axis_box in zip(crop, box)
                )
            )
            local_ids.append(label_id)

        if len(labeled_boxes) >= GLOBAL_LABEL_STRIDE:
            raise ValueError(
                f"Block {block_index} produced {len(labeled_boxes)} labels, exceeding the supported "
                f"per-block maximum of {GLOBAL_LABEL_STRIDE - 1}."
            )

        nblocks = module.get_nblocks(input_zarr.shape, blocksize)
        flat_block_index = int(module.np.ravel_multi_index(tuple(int(v) for v in block_index), nblocks))
        max_block_index = (np.iinfo(np.uint32).max - 1) // GLOBAL_LABEL_STRIDE
        if flat_block_index > max_block_index:
            raise ValueError(
                f"Block index {flat_block_index} exceeds uint32 packing capacity with stride {GLOBAL_LABEL_STRIDE}."
            )

        label_offset = np.uint32(flat_block_index * GLOBAL_LABEL_STRIDE)
        segmentation = np.asarray(segmentation, dtype=np.uint32)
        if int(label_offset) > 0:
            if segmentation.ndim == 2:
                nonzero = segmentation != 0
                if np.any(nonzero):
                    segmentation[nonzero] += label_offset
            else:
                for plane_index in range(segmentation.shape[0]):
                    plane = segmentation[plane_index]
                    nonzero = plane != 0
                    if np.any(nonzero):
                        plane[nonzero] += label_offset

        remap = np.asarray(local_ids, dtype=np.uint32)
        if remap.size:
            remap += label_offset

        if test_mode:
            return segmentation, boxes, remap

        output_zarr[tuple(crop)] = segmentation
        faces = module.block_faces(segmentation)
        return faces, boxes, remap

    module.distributed_eval = patched_distributed_eval
    module.get_block_crops = patched_get_block_crops
    module.remove_overlaps = patched_remove_overlaps
    module.process_block = patched_process_block
    module.myLocalCluster.__init__ = patched_local_cluster_init
    module._ijl_wrappers_compat_patch = True


def load_distributed_eval(
    dask_temp_directory=None,
    open_dask_dashboard=False,
    flow3d_smooth=0.0,
    cellprob_smooth=0.0,
):
    patch_zarr_open_for_cellpose()
    import cellpose.contrib.distributed_segmentation as distributed_segmentation

    distributed_segmentation._ijl_dask_temp_directory = dask_temp_directory
    distributed_segmentation._ijl_open_dask_dashboard = open_dask_dashboard
    patch_distributed_segmentation(distributed_segmentation)
    patch_cellpose_model_behavior(flow3d_smooth=flow3d_smooth, cellprob_smooth=cellprob_smooth)
    return distributed_segmentation.distributed_eval


def patch_cellpose_model_behavior(flow3d_smooth=0.0, cellprob_smooth=0.0):
    import cellpose.models as cellpose_models

    cellpose_models._ijl_flow3d_smooth = float(flow3d_smooth)
    cellpose_models._ijl_cellprob_smooth = float(cellprob_smooth)
    if getattr(cellpose_models, "_ijl_anisotropic_smoothing_patch", False):
        return

    original_run_net = cellpose_models.CellposeModel._run_net

    def patched_run_net(
        self,
        x,
        rescale=1.0,
        resample=True,
        augment=False,
        batch_size=8,
        tile_overlap=0.1,
        bsize=224,
        anisotropy=1.0,
        do_3D=False,
    ):
        dP, cellprob, styles = original_run_net(
            self,
            x,
            rescale=rescale,
            resample=resample,
            augment=augment,
            batch_size=batch_size,
            tile_overlap=tile_overlap,
            bsize=bsize,
            anisotropy=anisotropy,
            do_3D=do_3D,
        )
        if not do_3D:
            return dP, cellprob, styles

        effective_anisotropy = float(anisotropy) if anisotropy not in (None, 0) else 1.0
        z_scale = 1.0 / max(effective_anisotropy, 1e-6)

        flow_sigma = float(getattr(cellpose_models, "_ijl_flow3d_smooth", 0.0) or 0.0)
        if flow_sigma > 0:
            sigma = (0.0, flow_sigma * z_scale, flow_sigma, flow_sigma)
            cellpose_models.models_logger.info(
                "anisotropy-aware flow smoothing sigma(z,y,x)=(%.4f, %.4f, %.4f)"
                % (sigma[1], sigma[2], sigma[3])
            )
            dP = gaussian_filter(dP, sigma)

        cellprob_sigma = float(getattr(cellpose_models, "_ijl_cellprob_smooth", 0.0) or 0.0)
        if cellprob_sigma > 0:
            sigma = (cellprob_sigma * z_scale, cellprob_sigma, cellprob_sigma)
            cellpose_models.models_logger.info(
                "anisotropy-aware cellprob smoothing sigma(z,y,x)=(%.4f, %.4f, %.4f)"
                % sigma
            )
            cellprob = gaussian_filter(cellprob, sigma)

        return dP, cellprob, styles

    cellpose_models.CellposeModel._run_net = patched_run_net
    cellpose_models._ijl_anisotropic_smoothing_patch = True


def load_distributed_segmentation_module(
    dask_temp_directory=None,
    open_dask_dashboard=False,
    flow3d_smooth=0.0,
    cellprob_smooth=0.0,
):
    patch_zarr_open_for_cellpose()
    import cellpose.contrib.distributed_segmentation as distributed_segmentation

    distributed_segmentation._ijl_dask_temp_directory = dask_temp_directory
    distributed_segmentation._ijl_open_dask_dashboard = open_dask_dashboard
    patch_distributed_segmentation(distributed_segmentation)
    patch_cellpose_model_behavior(flow3d_smooth=flow3d_smooth, cellprob_smooth=cellprob_smooth)
    return distributed_segmentation


def apply_preprocessing_steps(image, crop, preprocessing_steps):
    processed = image
    for preprocessing_step in preprocessing_steps:
        preprocessing_step[1]["crop"] = crop
        processed = preprocessing_step[0](processed, **preprocessing_step[1])
    return processed


def setup_worker_log_file(worker_logs_directory):
    if worker_logs_directory is None:
        return None

    worker_name = "single"
    try:
        import distributed

        worker_name = distributed.get_worker().name
    except Exception:
        pass

    log_file = f"dask_worker_{worker_name}.log"
    return pathlib.Path(worker_logs_directory).joinpath(log_file)


def infer_spatial_shape(image, do_3d):
    if do_3d:
        return tuple(int(value) for value in image.shape[:3])
    return tuple(int(value) for value in image.shape[:2])


def read_preprocess_and_segment(
    input_zarr,
    crop,
    preprocessing_steps,
    model_kwargs,
    eval_kwargs,
    worker_logs_directory,
):
    import cellpose.io
    import cellpose.models

    image = input_zarr[crop]
    image = apply_preprocessing_steps(image, crop, preprocessing_steps)

    log_file = setup_worker_log_file(worker_logs_directory)
    cellpose.io.logger_setup(stdout_file_replacement=log_file)
    model = cellpose.models.CellposeModel(**model_kwargs)

    return model.eval(image, **eval_kwargs)[0].astype(np.uint32)


def run_single_block_eval(
    distributed_segmentation,
    input_zarr,
    preprocessing_steps,
    model_kwargs,
    eval_kwargs,
    args,
    level_infos,
    selected_level,
    base_pixel_sizes,
    output_path,
):
    full_crop = tuple(slice(0, int(length)) for length in input_zarr.shape)
    timestamp = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
    worker_logs_dir = pathlib.Path(output_path).parent / f"dask_worker_logs_{timestamp}"
    worker_logs_dir.mkdir(parents=True, exist_ok=True)

    print("Single-block plan detected; bypassing distributed stitching/relabeling.")
    print(f"Dask worker logs will be written under: {worker_logs_dir.parent}")
    labels = read_preprocess_and_segment(
        input_zarr,
        full_crop,
        preprocessing_steps,
        model_kwargs,
        eval_kwargs,
        str(worker_logs_dir),
    )
    labels = np.asarray(labels, dtype=np.uint32)
    pyramid_shapes, pyramid_pixel_sizes = prepare_output_label_specs(
        labels,
        args,
        level_infos,
        selected_level,
        base_pixel_sizes,
    )
    write_output_labels(args, labels, pyramid_shapes, pyramid_pixel_sizes)


def write_output_tiff(output_tiff, labels, pixel_sizes):
    write_output_tiff_with_pyramid(output_tiff, labels, [tuple(labels.shape)], [pixel_sizes])


def spatial_shape_from_array(array, channel_axis_argument):
    channel_axis = detect_channel_axis(array, channel_axis_argument)
    shape = [int(value) for value in array.shape]
    if channel_axis is not None:
        del shape[channel_axis]
    return tuple(shape)


def resize_labels_nearest(labels, target_shape):
    resized = np.asarray(labels)
    target_shape = tuple(int(value) for value in target_shape)
    if resized.shape == target_shape:
        return resized

    for axis, target_len in enumerate(target_shape):
        source_len = int(resized.shape[axis])
        if source_len == target_len:
            continue
        indices = np.floor(
            np.arange(target_len, dtype=np.float64) * (float(source_len) / float(target_len))
        ).astype(np.intp)
        indices = np.clip(indices, 0, max(0, source_len - 1))
        resized = np.take(resized, indices, axis=axis)
    return resized


def build_resize_indices(source_len, target_len, start=0, stop=None):
    if stop is None:
        stop = target_len
    if start < 0 or stop < start or stop > target_len:
        raise ValueError("Invalid resize index bounds.")
    if source_len <= 0 or target_len <= 0:
        raise ValueError("source_len and target_len must be positive.")
    if source_len == target_len:
        return np.arange(start, stop, dtype=np.intp)
    indices = np.floor(
        np.arange(start, stop, dtype=np.float64) * (float(source_len) / float(target_len))
    ).astype(np.intp)
    return np.clip(indices, 0, max(0, source_len - 1))


def round_up_to_multiple(value, multiple):
    return int(max(multiple, math.ceil(float(value) / float(multiple)) * multiple))


def estimate_tile_shape(target_shape):
    target_y = int(target_shape[-2])
    target_x = int(target_shape[-1])
    tile_y = round_up_to_multiple(min(target_y, TIFF_TILE_EDGE), 16)
    tile_x = round_up_to_multiple(min(target_x, TIFF_TILE_EDGE), 16)
    return (tile_y, tile_x)


def iter_resized_label_tiles(source_array, target_shape, tile_shape, dtype=np.uint32):
    source_shape = tuple(int(value) for value in source_array.shape)
    target_shape = tuple(int(value) for value in target_shape)
    tile_shape = tuple(int(value) for value in tile_shape)
    dtype = np.dtype(dtype)

    if len(source_shape) != len(target_shape):
        raise ValueError("source_array and target_shape must have the same dimensionality.")
    if len(target_shape) not in {2, 3}:
        raise ValueError("Only 2D and 3D label exports are supported.")

    target_y = int(target_shape[-2])
    target_x = int(target_shape[-1])
    source_y = int(source_shape[-2])
    source_x = int(source_shape[-1])
    x_indices = build_resize_indices(source_x, target_x)
    tile_y, tile_x = tile_shape
    y_ranges = [
        (start, min(target_y, start + tile_y))
        for start in range(0, target_y, tile_y)
    ]
    y_indices_per_tile = [
        build_resize_indices(source_y, target_y, start, stop)
        for start, stop in y_ranges
    ]
    x_ranges = [
        (start, min(target_x, start + tile_x))
        for start in range(0, target_x, tile_x)
    ]

    def tile_arrays(source_plane, y_indices):
        row_block = np.take(source_plane, y_indices, axis=0)
        if source_x != target_x:
            row_block = np.take(row_block, x_indices, axis=1)
        row_block = np.asarray(row_block, dtype=dtype)
        for x_start, x_stop in x_ranges:
            tile = np.zeros(tile_shape, dtype=dtype)
            part = row_block[:, x_start:x_stop]
            tile[: part.shape[0], : part.shape[1]] = part
            yield tile

    if len(target_shape) == 2:
        source_plane = np.asarray(source_array, dtype=dtype)
        for y_indices in y_indices_per_tile:
            yield from tile_arrays(source_plane, y_indices)
        return

    source_z = int(source_shape[0])
    target_z = int(target_shape[0])
    cached_source_index = None
    cached_source_plane = None
    for target_plane_index in range(target_z):
        source_plane_index = int(
            math.floor(float(target_plane_index) * (float(source_z) / float(target_z)))
        )
        source_plane_index = min(source_plane_index, max(0, source_z - 1))
        if cached_source_index != source_plane_index:
            cached_source_plane = np.asarray(source_array[source_plane_index], dtype=dtype)
            cached_source_index = source_plane_index
        for y_indices in y_indices_per_tile:
            yield from tile_arrays(cached_source_plane, y_indices)


def estimate_ome_zarr_chunks(target_shape):
    if len(target_shape) == 3:
        return (
            min(int(target_shape[0]), OME_ZARR_CHUNK_Z),
            min(int(target_shape[1]), OME_ZARR_CHUNK_YX),
            min(int(target_shape[2]), OME_ZARR_CHUNK_YX),
        )
    if len(target_shape) == 2:
        return (
            min(int(target_shape[0]), OME_ZARR_CHUNK_YX),
            min(int(target_shape[1]), OME_ZARR_CHUNK_YX),
        )
    raise ValueError("Only 2D and 3D label exports are supported.")


def estimate_ome_zarr_inner_chunks(target_shape):
    if len(target_shape) == 3:
        return (
            min(int(target_shape[0]), OME_ZARR_INNER_CHUNK_Z),
            min(int(target_shape[1]), OME_ZARR_INNER_CHUNK_YX),
            min(int(target_shape[2]), OME_ZARR_INNER_CHUNK_YX),
        )
    if len(target_shape) == 2:
        return (
            min(int(target_shape[0]), OME_ZARR_INNER_CHUNK_YX),
            min(int(target_shape[1]), OME_ZARR_INNER_CHUNK_YX),
        )
    raise ValueError("Only 2D and 3D label exports are supported.")


def ome_zarr_supports_sharding():
    if BytesCodec is None or BloscCodec is None:
        return False
    try:
        return "shards" in inspect.signature(zarr.Group.create_array).parameters
    except Exception:
        return False


def open_zarr_group_ome_compatible(path, mode="w", zarr_format=2):
    kwargs = {"mode": mode, "synchronizer": None}
    if "w" in mode or mode == "a":
        kwargs["zarr_format"] = zarr_format
    try:
        return zarr.open_group(path, **kwargs)
    except TypeError:
        return zarr.open_group(path, mode=mode, synchronizer=None)


def build_ome_zarr_axes(target_shape):
    if len(target_shape) == 3:
        return [
            {"name": "z", "type": "space", "unit": "micrometer"},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
        ]
    if len(target_shape) == 2:
        return [
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"},
        ]
    raise ValueError("Only 2D and 3D label exports are supported.")


def build_ome_zarr_scale(pixel_sizes, target_shape):
    if len(target_shape) == 3:
        return [
            float(pixel_sizes["Z"] or 1.0),
            float(pixel_sizes["Y"] or 1.0),
            float(pixel_sizes["X"] or 1.0),
        ]
    if len(target_shape) == 2:
        return [
            float(pixel_sizes["Y"] or 1.0),
            float(pixel_sizes["X"] or 1.0),
        ]
    raise ValueError("Only 2D and 3D label exports are supported.")


def write_resized_labels_to_zarr(source_array, target_array):
    source_shape = tuple(int(value) for value in source_array.shape)
    target_shape = tuple(int(value) for value in target_array.shape)

    if len(source_shape) != len(target_shape):
        raise ValueError("source_array and target_array must have the same dimensionality.")

    target_y = int(target_shape[-2])
    target_x = int(target_shape[-1])
    source_y = int(source_shape[-2])
    source_x = int(source_shape[-1])
    chunk_shape = getattr(target_array, "shards", None)
    if chunk_shape is None:
        chunk_shape = getattr(target_array, "chunks", target_shape)
    tile_y = max(1, min(int(chunk_shape[-2]), target_y))
    tile_x = max(1, min(int(chunk_shape[-1]), target_x))

    y_ranges = [
        (start, min(target_y, start + tile_y))
        for start in range(0, target_y, tile_y)
    ]
    y_indices_per_tile = [
        build_resize_indices(source_y, target_y, start, stop)
        for start, stop in y_ranges
    ]
    x_ranges = [
        (start, min(target_x, start + tile_x))
        for start in range(0, target_x, tile_x)
    ]
    x_indices_per_tile = [
        build_resize_indices(source_x, target_x, start, stop)
        for start, stop in x_ranges
    ]

    def write_plane(source_plane, target_plane_index=None):
        source_plane = np.asarray(source_plane, dtype=np.uint32)
        for (y_start, y_stop), y_indices in zip(y_ranges, y_indices_per_tile):
            row_block = np.take(source_plane, y_indices, axis=0)
            for (x_start, x_stop), x_indices in zip(x_ranges, x_indices_per_tile):
                if source_x == target_x:
                    part = row_block[:, x_start:x_stop]
                else:
                    part = np.take(row_block, x_indices, axis=1)
                if target_plane_index is None:
                    target_array[y_start:y_stop, x_start:x_stop] = np.asarray(
                        part,
                        dtype=np.uint32,
                    )
                else:
                    target_array[target_plane_index, y_start:y_stop, x_start:x_stop] = np.asarray(
                        part,
                        dtype=np.uint32,
                    )

    if len(target_shape) == 2:
        write_plane(source_array)
        return

    source_z = int(source_shape[0])
    target_z = int(target_shape[0])
    cached_source_index = None
    cached_source_plane = None

    for target_plane_index in range(target_z):
        source_plane_index = int(
            math.floor(float(target_plane_index) * (float(source_z) / float(target_z)))
        )
        source_plane_index = min(source_plane_index, max(0, source_z - 1))
        if cached_source_index != source_plane_index:
            cached_source_plane = source_array[source_plane_index]
            cached_source_index = source_plane_index
        write_plane(cached_source_plane, target_plane_index=target_plane_index)


def _write_output_ome_zarr_once(output_path, labels_source, pyramid_shapes, pyramid_pixel_sizes):
    output_path = pathlib.Path(output_path)
    output_path.mkdir(parents=True, exist_ok=True)
    use_sharding = ome_zarr_supports_sharding()
    zarr_format = 3 if use_sharding else 2
    zstore = open_zarr_group_ome_compatible(str(output_path), mode="w", zarr_format=zarr_format)
    axes = build_ome_zarr_axes(pyramid_shapes[0])
    datasets_meta = []

    if use_sharding:
        print("OME-Zarr export storage: sharded Zarr V3")
    else:
        print("OME-Zarr export storage: plain chunks (sharding codec unavailable)")

    for level_index, (level_shape, pixel_sizes) in enumerate(
        zip(pyramid_shapes, pyramid_pixel_sizes)
    ):
        level_shape = tuple(int(value) for value in level_shape)
        outer_chunks = estimate_ome_zarr_chunks(level_shape)
        if use_sharding:
            dataset = zstore.create_array(
                str(level_index),
                shape=level_shape,
                chunks=estimate_ome_zarr_inner_chunks(level_shape),
                shards=outer_chunks,
                dtype=np.uint32,
                serializer=BytesCodec(),
                compressors=(
                    BloscCodec(cname="zstd", clevel=1, shuffle="bitshuffle"),
                ),
                overwrite=True,
            )
        else:
            dataset = zstore.create_dataset(
                str(level_index),
                shape=level_shape,
                chunks=outer_chunks,
                dtype=np.uint32,
                overwrite=True,
            )
        write_resized_labels_to_zarr(labels_source, dataset)
        datasets_meta.append(
            {
                "path": str(level_index),
                "coordinateTransformations": [
                    {"type": "scale", "scale": build_ome_zarr_scale(pixel_sizes, level_shape)}
                ],
            }
        )

    zstore.attrs["multiscales"] = [
        {
            "version": "0.4",
            "name": output_path.name,
            "axes": axes,
            "datasets": datasets_meta,
            "type": "label_nearest",
            "metadata": {
                "description": (
                    "Pyramidal OME-Zarr labels generated by distributed_cellpose_run.py. "
                    "Axes convention: ZYX or YX."
                )
            },
        }
    ]
    zstore.attrs["physical_pixel_sizes_um"] = dict(pyramid_pixel_sizes[0])
    zstore.attrs["storage"] = {
        "zarr_format": zarr_format,
        "sharded": use_sharding,
        "outer_chunks": list(estimate_ome_zarr_chunks(pyramid_shapes[0])),
        "inner_chunks": (
            list(estimate_ome_zarr_inner_chunks(pyramid_shapes[0]))
            if use_sharding
            else None
        ),
    }
    zarr.consolidate_metadata(str(output_path))


def validate_output_ome_zarr(output_path, pyramid_shapes):
    zstore = open_zarr_group_ome_compatible(str(output_path), mode="r")
    multiscales = dict(getattr(zstore, "attrs", {})).get("multiscales", [])
    if not multiscales:
        raise ValueError("written OME-Zarr is missing multiscales metadata")
    datasets = multiscales[0].get("datasets", [])
    if len(datasets) != len(pyramid_shapes):
        raise ValueError(
            f"expected {len(pyramid_shapes)} pyramid levels, found {len(datasets)}"
        )
    for level_index, expected_shape in enumerate(pyramid_shapes):
        dataset = zstore[str(level_index)]
        actual_shape = tuple(int(value) for value in dataset.shape)
        if actual_shape != tuple(int(value) for value in expected_shape):
            raise ValueError(
                f"expected level shape {tuple(int(value) for value in expected_shape)}, found {actual_shape}"
            )


def write_output_ome_zarr(output_path, labels_source, pyramid_shapes, pyramid_pixel_sizes, max_attempts=2):
    output_path = pathlib.Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    last_error = None

    for attempt in range(1, max_attempts + 1):
        temp_output = output_path.with_name(f"{output_path.name}.attempt{attempt}.tmpdir")
        try:
            if temp_output.exists():
                shutil.rmtree(temp_output, ignore_errors=True)
        except Exception:
            pass

        print(f"OME-Zarr export attempt {attempt}/{max_attempts}: {temp_output}")
        try:
            _write_output_ome_zarr_once(
                temp_output,
                labels_source,
                pyramid_shapes,
                pyramid_pixel_sizes,
            )
            validate_output_ome_zarr(temp_output, pyramid_shapes)
            if output_path.exists():
                shutil.rmtree(output_path, ignore_errors=True)
            os.replace(temp_output, output_path)
            validate_output_ome_zarr(output_path, pyramid_shapes)
            return
        except Exception as error:
            last_error = error
            print(f"WARNING: OME-Zarr export attempt {attempt} failed: {error}")
            print(f"Keeping failed export artifact for inspection: {temp_output}")

    raise RuntimeError(
        f"OME-Zarr export failed after {max_attempts} attempts."
    ) from last_error


def write_output_labels(args, labels_source, pyramid_shapes, pyramid_pixel_sizes):
    if args.output_format == "ome-zarr":
        print(f"Writing OME-Zarr: {args.output_path}")
        write_output_ome_zarr(
            args.output_path,
            labels_source,
            pyramid_shapes,
            pyramid_pixel_sizes,
        )
        return

    print(f"Writing OME-TIFF: {args.output_path}")
    write_output_tiff_with_pyramid(
        args.output_path,
        labels_source,
        pyramid_shapes,
        pyramid_pixel_sizes,
    )


def _write_output_tiff_with_pyramid_once(output_tiff, labels_source, pyramid_shapes, pyramid_pixel_sizes):
    if not pyramid_shapes:
        raise ValueError("pyramid_shapes must contain at least one level.")

    axes = "ZYX" if len(pyramid_shapes[0]) == 3 else "YX"

    def metadata_for(pixel_sizes):
        metadata = {"axes": axes, "PhysicalSizeXUnit": "um", "PhysicalSizeYUnit": "um"}
        if pixel_sizes["X"] is not None:
            metadata["PhysicalSizeX"] = float(pixel_sizes["X"])
        if pixel_sizes["Y"] is not None:
            metadata["PhysicalSizeY"] = float(pixel_sizes["Y"])
        if axes == "ZYX":
            metadata["PhysicalSizeZUnit"] = "um"
            if pixel_sizes["Z"] is not None:
                metadata["PhysicalSizeZ"] = float(pixel_sizes["Z"])
        return metadata

    def resolution_for(pixel_sizes):
        xy_um = pixel_sizes["X"] or pixel_sizes["Y"]
        if xy_um is None or float(xy_um) <= 0:
            return None
        return (1.0 / float(xy_um), 1.0 / float(xy_um))

    pathlib.Path(output_tiff).parent.mkdir(parents=True, exist_ok=True)
    subifds = max(0, len(pyramid_shapes) - 1)
    with tifffile.TiffWriter(output_tiff, bigtiff=True, ome=True) as writer:
        tile_shape = estimate_tile_shape(pyramid_shapes[0])
        writer.write(
            iter_resized_label_tiles(labels_source, pyramid_shapes[0], tile_shape),
            shape=pyramid_shapes[0],
            dtype=np.uint32,
            tile=tile_shape,
            metadata=metadata_for(pyramid_pixel_sizes[0]),
            resolution=resolution_for(pyramid_pixel_sizes[0]),
            compression=TIFF_LABEL_COMPRESSION,
            subifds=subifds,
        )
        for level_shape, pixel_sizes in zip(pyramid_shapes[1:], pyramid_pixel_sizes[1:]):
            tile_shape = estimate_tile_shape(level_shape)
            writer.write(
                iter_resized_label_tiles(labels_source, level_shape, tile_shape),
                shape=level_shape,
                dtype=np.uint32,
                tile=tile_shape,
                metadata=None,
                resolution=resolution_for(pixel_sizes),
                compression=TIFF_LABEL_COMPRESSION,
                subfiletype=1,
            )


def validate_output_ome_tiff(output_tiff, pyramid_shapes):
    output_path = pathlib.Path(output_tiff)
    expected_axes = "ZYX" if len(pyramid_shapes[0]) == 3 else "YX"
    expected_shapes = [tuple(int(value) for value in shape) for shape in pyramid_shapes]

    with tifffile.TiffFile(output_path) as tif:
        if not tif.is_ome:
            raise ValueError("written file is not recognized as OME-TIFF")
        if not tif.ome_metadata:
            raise ValueError("written file is missing OME metadata")
        if len(tif.series) != 1:
            raise ValueError(f"expected 1 TIFF series, found {len(tif.series)}")

        series = tif.series[0]
        if series.axes != expected_axes:
            raise ValueError(f"expected axes {expected_axes}, found {series.axes}")

        levels = list(getattr(series, "levels", [])) or [series]
        if len(levels) != len(expected_shapes):
            raise ValueError(
                f"expected {len(expected_shapes)} pyramid levels, found {len(levels)}"
            )

        for level, expected_shape in zip(levels, expected_shapes):
            actual_shape = tuple(int(value) for value in level.shape)
            if actual_shape != expected_shape:
                raise ValueError(
                    f"expected level shape {expected_shape}, found {actual_shape}"
                )

        if len(expected_shapes) > 1:
            if "SubIFDs" not in tif.pages[0].tags:
                raise ValueError("missing SubIFDs tag for pyramidal OME-TIFF")
            offsets = tuple(int(value) for value in tif.pages[0].tags["SubIFDs"].value)
            if len(offsets) < len(expected_shapes) - 1:
                raise ValueError(
                    f"expected at least {len(expected_shapes) - 1} subIFD offsets, found {len(offsets)}"
                )
            if any(offset <= 0 for offset in offsets[: len(expected_shapes) - 1]):
                raise ValueError("one or more SubIFD offsets are invalid")


def write_output_tiff_with_pyramid(output_tiff, labels_source, pyramid_shapes, pyramid_pixel_sizes, max_attempts=2):
    output_path = pathlib.Path(output_tiff)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    last_error = None

    for attempt in range(1, max_attempts + 1):
        temp_output = output_path.with_name(f"{output_path.name}.attempt{attempt}.tmp")
        try:
            if temp_output.exists():
                temp_output.unlink()
        except Exception:
            pass

        print(f"OME-TIFF export attempt {attempt}/{max_attempts}: {temp_output}")
        try:
            _write_output_tiff_with_pyramid_once(
                temp_output,
                labels_source,
                pyramid_shapes,
                pyramid_pixel_sizes,
            )
            validate_output_ome_tiff(temp_output, pyramid_shapes)
            os.replace(temp_output, output_path)
            validate_output_ome_tiff(output_path, pyramid_shapes)
            return
        except Exception as error:
            last_error = error
            print(f"WARNING: OME-TIFF export attempt {attempt} failed: {error}")
            print(f"Keeping failed export artifact for inspection: {temp_output}")

    raise RuntimeError(
        f"OME-TIFF export failed after {max_attempts} attempts."
    ) from last_error


def resolve_output_level_infos(level_infos, selected_level, output_resolution):
    selected_index = next(
        index for index, level_info in enumerate(level_infos) if level_info.key == selected_level.key
    )
    if output_resolution == "level0":
        return level_infos
    return level_infos[selected_index:]


def prepare_output_label_specs(labels_source, args, level_infos, selected_level, base_pixel_sizes):
    output_level_infos = resolve_output_level_infos(
        level_infos,
        selected_level,
        args.output_resolution,
    )

    source_shape = tuple(int(value) for value in labels_source.shape)
    pyramid_shapes = []
    pyramid_pixel_sizes = []
    for level_info in output_level_infos:
        target_shape = spatial_shape_from_array(level_info.array, args.channel_axis)
        if len(target_shape) != len(source_shape):
            raise ValueError("Output level dimensionality does not match labels.")

        if pyramid_shapes and len(target_shape) == 3:
            # OME-TIFF pyramids in SubIFDs keep the same Z extent and only
            # downsample in-plane.
            target_shape = (pyramid_shapes[0][0], target_shape[1], target_shape[2])

        pixel_sizes = resolve_output_pixel_sizes(level_info, base_pixel_sizes)
        if pyramid_pixel_sizes and len(target_shape) == 3:
            pixel_sizes = dict(pixel_sizes)
            pixel_sizes["Z"] = pyramid_pixel_sizes[0]["Z"]

        pyramid_shapes.append(target_shape)
        pyramid_pixel_sizes.append(pixel_sizes)

    if not args.pyramidal_output:
        pyramid_shapes = pyramid_shapes[:1]
        pyramid_pixel_sizes = pyramid_pixel_sizes[:1]

    return pyramid_shapes, pyramid_pixel_sizes


def print_plan(
    args,
    input_kind,
    cp_version,
    selected_level,
    source_array,
    channel_plan,
    diameter_px,
    output_pixel_sizes,
    blocksize,
    total_blocks,
    model_kwargs,
    eval_kwargs,
    cluster_kwargs,
):
    print("Plan summary")
    print(f"  Input kind: {input_kind}")
    print(f"  Cellpose version: {cp_version}")
    print(f"  Source array shape: {source_array.shape}")
    print(f"  Working array shape: {channel_plan.input_zarr.shape}")
    print(f"  Selected level: {selected_level.key}")
    print(f"  Effective diameter: {diameter_px:.2f} px")
    print(f"  Output pixel sizes (um): {output_pixel_sizes}")
    print(
        f"  Output export: format={args.output_format}, resolution={args.output_resolution}, "
        f"pyramidal={args.pyramidal_output}"
    )
    print(
        f"  3D smoothing: flow3D_smooth={args.flow3D_smooth}, "
        f"cellprob_smooth={args.cellprob_smooth}"
    )
    print(
        f"  Source channels: {channel_plan.source_channel_count} "
        f"(axis={channel_plan.source_channel_axis})"
    )
    print(f"  Processed channels for Cellpose: {channel_plan.processed_channel_count}")
    if channel_plan.source_channel_axis is not None:
        primary_channel = args.channel if args.channel is not None else (args.ch1 + 1 if args.ch1 >= 0 else None)
        secondary_channel = (
            args.nucleus_channel
            if args.nucleus_channel is not None
            else (args.ch2 + 1 if args.ch2 >= 0 else None)
        )
        print(
            f"  User channel selection (1-based): primary={primary_channel}, secondary={secondary_channel}"
        )
    print(f"  Blocksize: {blocksize}")
    print(f"  Estimated blocks: {total_blocks}")
    print(f"  model_kwargs: {model_kwargs}")
    print(f"  eval_kwargs: {eval_kwargs}")
    print(f"  cluster_kwargs: {cluster_kwargs}")


def main():
    args = parse_args()
    resolve_output_args(args)
    log_handle, log_path = setup_persistent_process_log(args.output_path)
    resolve_user_channel_args(args)
    resolve_pretrained_model_alias(args)
    resolve_input_source_args(args)
    try:
        cp_version = get_installed_version("cellpose")
        cp_major = int(cp_version.split(".")[0]) if cp_version else 0
        zarr_version = get_installed_version("zarr")
        print(f"Detected Cellpose {cp_version}, Zarr {zarr_version}")

        avail_ram = get_available_ram_bytes()
        print(f"Available RAM: {avail_ram / 1024**3:.2f} GiB")
        dask_temp_directory = resolve_dask_temp_directory(args)
        print(f"Dask temp directory: {dask_temp_directory}")
        if args.use_gpu:
            gpu_ram = get_available_gpu_bytes()
            args.processing_memory_budget_bytes = gpu_ram * GPU_RAM_FRACTION
            print(
                f"Available GPU RAM: {gpu_ram / 1024**3:.2f} GiB "
                f"(planning budget {args.processing_memory_budget_bytes / 1024**3:.2f} GiB)"
            )

        if args.auto_cluster:
            auto_configure_cluster(args, avail_ram)

        root, input_kind, input_pixel_sizes = open_input_source(args)
        root_pixel_sizes = read_root_pixel_sizes(root)
        for axis in ("Z", "Y", "X"):
            if root_pixel_sizes[axis] is None and input_pixel_sizes[axis] is not None:
                root_pixel_sizes[axis] = input_pixel_sizes[axis]
        if input_kind == "tiff-folder" and any(value is not None for value in input_pixel_sizes.values()):
            print(f"TIFF-derived pixel sizes (um): {input_pixel_sizes}")
        base_pixel_sizes = resolve_base_pixel_sizes(args, root_pixel_sizes)

        level_infos = build_level_infos(root, root_pixel_sizes)
        full_diameter_px = resolve_full_resolution_diameter_px(args, base_pixel_sizes)
        selected_level = select_resolution_level(level_infos, args, full_diameter_px)
        output_pixel_sizes = resolve_output_pixel_sizes(selected_level, base_pixel_sizes)

        args.anisotropy = resolve_anisotropy(args, output_pixel_sizes)
        level_xy_factor = max(selected_level.x_factor or 1.0, selected_level.y_factor or 1.0, 1.0)
        diameter_px = full_diameter_px / level_xy_factor

        source_array = selected_level.array
        print(f"Processing array shape: {source_array.shape} dtype: {source_array.dtype}")

        channel_plan = build_channel_plan(source_array, args)
        blocksize = resolve_blocksize(
            args,
            channel_plan.input_zarr,
            diameter_px,
            args.anisotropy,
            avail_ram,
            channel_plan.processed_channel_count,
        )

        total_blocks = estimate_total_blocks(channel_plan.input_zarr.shape, blocksize)
        maybe_reduce_workers_after_blocksize(args, avail_ram, total_blocks)
        if args.auto_cluster:
            blocksize = resolve_blocksize(
                args,
                channel_plan.input_zarr,
                diameter_px,
                args.anisotropy,
                avail_ram,
                channel_plan.processed_channel_count,
            )
            total_blocks = estimate_total_blocks(channel_plan.input_zarr.shape, blocksize)

        model_kwargs = build_model_kwargs(args, cp_major)
        eval_kwargs = build_eval_kwargs(
            args,
            diameter_px,
            cp_major,
            channel_plan.processed_channel_count,
        )
        cluster_kwargs = {
            "ncpus": int(args.ncpus),
            "n_workers": int(args.n_workers),
            "memory_limit": args.memory_per_worker,
            "threads_per_worker": 1,
        }

        print_plan(
            args,
            input_kind,
            cp_version,
            selected_level,
            source_array,
            channel_plan,
            diameter_px,
            output_pixel_sizes,
            blocksize,
            total_blocks,
            model_kwargs,
            eval_kwargs,
            cluster_kwargs,
        )

        if args.dry_run:
            print("Dry run requested; exiting before Cellpose import.")
            print(f"Dry run completed; persistent process log written to: {log_path}")
            return

        distributed_segmentation = load_distributed_segmentation_module(
            dask_temp_directory=dask_temp_directory,
            open_dask_dashboard=args.open_dask_dashboard,
            flow3d_smooth=args.flow3D_smooth,
            cellprob_smooth=args.cellprob_smooth,
        )

        if total_blocks <= 1:
            run_single_block_eval(
                distributed_segmentation,
                channel_plan.input_zarr,
                channel_plan.preprocessing_steps,
                model_kwargs,
                eval_kwargs,
                args,
                level_infos,
                selected_level,
                base_pixel_sizes,
                args.output_path,
            )
        else:
            distributed_eval = distributed_segmentation.distributed_eval
            output_dir = pathlib.Path(args.output_path).parent
            write_path = output_dir / "_dist_cellpose_result.zarr"
            export_succeeded = False
            result_zarr = None

            try:
                if write_path.exists():
                    print(
                        f"Found existing segmentation results at {write_path}; "
                        f"skipping segmentation and retrying {args.output_format} export only."
                    )
                    result_zarr = zarr.open(str(write_path), mode="r")
                else:
                    print("Starting distributed_eval")
                    result_zarr, _ = distributed_eval(
                        input_zarr=channel_plan.input_zarr,
                        blocksize=blocksize,
                        write_path=str(write_path),
                        preprocessing_steps=channel_plan.preprocessing_steps,
                        model_kwargs=model_kwargs,
                        eval_kwargs=eval_kwargs,
                        cluster_kwargs=cluster_kwargs,
                        temporary_directory=str(output_dir),
                    )

                pyramid_shapes, pyramid_pixel_sizes = prepare_output_label_specs(
                    result_zarr,
                    args,
                    level_infos,
                    selected_level,
                    base_pixel_sizes,
                )
                write_output_labels(
                    args,
                    result_zarr,
                    pyramid_shapes,
                    pyramid_pixel_sizes,
                )
                export_succeeded = True
            finally:
                if export_succeeded and write_path.exists():
                    shutil.rmtree(write_path, ignore_errors=True)
                elif write_path.exists():
                    print(
                        f"Preserving segmentation results at {write_path} so {args.output_format} export can be retried without rerunning segmentation."
                    )

        print("Done.")
    finally:
        log_handle.close()


if __name__ == "__main__":
    main()
