#@ImagePlus imp
#@File(style="directory", label="Select conda environment") conda_env_path
#@File(style="directory", label="Select output directory") output_directory

import ch.epfl.biop.wrappers.cellpose.ij2commands.CellposeDistributed
import ch.epfl.biop.wrappers.cellpose.ij2commands.ConvertToZarr

// ============================================================
// Distributed Cellpose — Groovy script template
//
// The Fiji command now accepts either an open image, a regular file,
// a folder of TIFFs, or an existing Zarr. Non-Zarr inputs are converted
// automatically before distributed Cellpose is launched.
// ============================================================


// ============================================================
// SCENARIO A — Segment the active image from Fiji
// ============================================================

def cp = new CellposeDistributed()
cp.imp = imp
cp.env_path = conda_env_path
cp.env_type = "conda"
cp.output_directory = output_directory
cp.output_name = "cellpose_mask"
cp.output_format = "ome-zarr"   // or "ome-tiff"
cp.output_resolution = "level0" // or "native"

cp.model = "cyto3"
// cp.pretrained_model = new File("/path/to/custom_model")

cp.diameter = 30d
cp.ch1 = 1
cp.ch2 = 0
cp.channel_axis = -1

cp.blocksize = "auto"
cp.resolution_level = -1
cp.auto_cluster = true
cp.n_workers = 1
cp.ncpus = 4
cp.memory_per_worker = "8GB"

cp.use_gpu = false
cp.do_3D = false
cp.show_dashboard = true
cp.reuse_zarr = true
cp.cellprob_threshold = 0d
cp.min_size = 15
cp.flow3D_smooth = 1d
cp.cellprob_smooth = 0d
cp.no_resample = false
cp.additional_flags = ""

cp.run()


// ============================================================
// SCENARIO B — Convert a source file to Zarr, then segment it
// ============================================================

/*
def zarrOut = new File(output_directory, "my_image_input.ome.zarr")

def conv = new ConvertToZarr()
conv.input_file = new File("/path/to/my_image.czi")
conv.output_zarr_path = zarrOut
conv.env_path = conda_env_path
conv.env_type = "conda"
conv.chunks = "auto"
conv.n_levels = "auto"
// conv.pixel_size_x_um = 0.65
// conv.pixel_size_y_um = 0.65
// conv.pixel_size_z_um = 1.0
conv.run()

def seg = new CellposeDistributed()
seg.input_file_or_folder = zarrOut
seg.env_path = conda_env_path
seg.env_type = "conda"
seg.output_directory = output_directory
seg.output_name = "cellpose_mask"
seg.output_format = "ome-zarr"
seg.output_resolution = "level0"
seg.model = "nuclei"
seg.diameter = 30d
seg.pixel_size_xy_um = "0.65"
seg.pixel_size_z_um = "1.0"
seg.ch1 = 1
seg.ch2 = 0
seg.channel_axis = -1
seg.use_gpu = true
seg.do_3D = true
seg.run()
*/


// ============================================================
// SCENARIO C — Segment an existing Zarr or TIFF folder on disk
// ============================================================

/*
def seg = new CellposeDistributed()
seg.input_file_or_folder = new File("/path/to/existing.zarr")
// seg.input_file_or_folder = new File("/path/to/tiff_folder")
seg.env_path = conda_env_path
seg.env_type = "conda"
seg.output_directory = output_directory
seg.output_name = "cellpose_mask"
seg.output_format = "ome-tiff"
seg.output_resolution = "native"
seg.model = "cyto3"
seg.diameter = 30d
seg.pixel_size_xy_um = "0.108"
seg.pixel_size_z_um = "1.0"
seg.ch1 = 1
seg.ch2 = 0
seg.channel_axis = -1
seg.blocksize = "auto"
seg.resolution_level = -1
seg.auto_cluster = true
seg.use_gpu = true
seg.do_3D = true
seg.run()
*/
