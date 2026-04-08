#@ImagePlus imp
#@File(style="directory", label="Select conda environment") conda_env_path
#@File(style="directory", label="Select output directory") output_directory

<<<<<<< HEAD
// ============================================================
// Distributed Cellpose — Groovy script template
//
// Required conda packages:
//   conda install -c conda-forge cellpose tifffile scikit-image
//   pip install "cellpose[distributed]" bioio bioio-bioformats
//
// Three usage scenarios are shown below.
// Un-comment the one you need and comment out the others.
=======
import ch.epfl.biop.wrappers.cellpose.ij2commands.CellposeDistributed
import ch.epfl.biop.wrappers.cellpose.ij2commands.ConvertToZarr

// ============================================================
// Distributed Cellpose — Groovy script template
//
// The Fiji command now accepts either an open image, a regular file,
// a folder of TIFFs, or an existing Zarr. Non-Zarr inputs are converted
// automatically before distributed Cellpose is launched.
>>>>>>> gpu_cpu_split
// ============================================================


// ============================================================
<<<<<<< HEAD
// SCENARIO A — Run directly from an open ImagePlus
// The image is automatically converted to a pyramidal OME-Zarr
// before segmentation.  Fiji calibration is preserved.
// ============================================================

def cp = new CellposeDistributed()
cp.imp               = imp
cp.env_path          = conda_env_path
cp.env_type          = "conda"
cp.output_directory  = output_directory
cp.output_name       = "cellpose_mask.ome.tif"

// Model: Cellpose 3 name (e.g. "cyto3", "nuclei") or "cpsam" for Cellpose 4 SAM
cp.model             = "cyto3"
// cp.model_path     = new File("/path/to/custom_model")  // optional

// Diameter in µm (recommended — converted to pixels using Fiji calibration)
cp.diameter          = 30f
cp.diameter_unit     = "µm"

// Channel indices (0-based zarr).  -1 = grayscale.
// ch1 = cytoplasm, ch2 = optional nucleus channel.
cp.ch1               = -1
cp.ch2               = -1
cp.channel_axis      = -1

// Blocksize and cluster are auto-configured from available RAM and CPU count
// when autoCluster = true (default). Override manually if needed:
// cp.blocksize         = "256,256,256"  // spatial voxels at chosen pyramid level
// cp.n_workers         = 4
// cp.ncpus             = 4
// cp.memory_per_worker = "8GB"

cp.use_gpu           = false
cp.do_3D             = false
cp.additional_flags  = ""
=======
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
>>>>>>> gpu_cpu_split

cp.run()


// ============================================================
<<<<<<< HEAD
// SCENARIO B — Pre-convert a file to OME-Zarr, then segment
//
// Use this when you want to reuse the same Zarr for multiple
// segmentation runs with different parameters, or when the
// source file is a multi-scene / proprietary format.
// ============================================================

/*

// --- Step 1: convert to OME-Zarr ---
def zarr_out = new File(output_directory, "my_image.zarr")

def conv = new ConvertToZarr()
// conv.imp        = imp                             // from open image
conv.input_file    = new File("/path/to/my_image.czi")  // or from file
conv.output_zarr_path = zarr_out
conv.env_path      = conda_env_path
conv.env_type      = "conda"
conv.chunks        = "64,64,64"   // chunk size in voxels (Z,Y,X)
conv.n_levels      = 4            // pyramid depth
// Pixel size overrides (leave 0 to read from file metadata):
=======
// SCENARIO B — Convert a source file to Zarr, then segment it
// ============================================================

/*
def zarrOut = new File(output_directory, "my_image_input.zarr")

def conv = new ConvertToZarr()
conv.input_file = new File("/path/to/my_image.czi")
conv.output_zarr_path = zarrOut
conv.env_path = conda_env_path
conv.env_type = "conda"
conv.chunks = "auto"
conv.n_levels = "auto"
>>>>>>> gpu_cpu_split
// conv.pixel_size_x_um = 0.65
// conv.pixel_size_y_um = 0.65
// conv.pixel_size_z_um = 1.0
conv.run()

<<<<<<< HEAD
// --- Step 2: segment the Zarr ---
def seg = new CellposeDistributed()
seg.input_zarr_path  = zarr_out
seg.env_path         = conda_env_path
seg.env_type         = "conda"
seg.output_directory = output_directory
seg.output_name      = "cellpose_mask.ome.tif"
seg.model            = "cyto3"
seg.diameter         = 30f
seg.diameter_unit    = "µm"
seg.ch1 = -1; seg.ch2 = -1; seg.channel_axis = -1
seg.use_gpu = false; seg.do_3D = false
// Pixel sizes are stored in the Zarr by ConvertToZarr; override here if needed:
// seg.pixel_size_xy_um = 0.108   // µm/px
// seg.pixel_size_z_um  = 1.0    // µm/px  (required for correct anisotropy in 3-D)
seg.run()

=======
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
>>>>>>> gpu_cpu_split
*/


// ============================================================
<<<<<<< HEAD
// SCENARIO C — Segment a pre-existing OME-Zarr on disk
//              (no open image needed)
// ============================================================

/*

def seg = new CellposeDistributed()
seg.input_zarr_path  = new File("/path/to/existing.zarr")
seg.env_path         = conda_env_path
seg.env_type         = "conda"
seg.output_directory = output_directory
seg.output_name      = "cellpose_mask.ome.tif"
seg.model            = "cyto3"
// Diameter in µm requires pixel size stored in the zarr (done by ConvertToZarr);
// OR specify it explicitly:
// seg.pixel_size_xy_um = 0.108
// seg.pixel_size_z_um  = 1.0    // needed for correct anisotropy in 3-D
seg.diameter         = 30f
seg.diameter_unit    = "µm"
seg.ch1 = -1; seg.ch2 = -1; seg.channel_axis = -1
seg.use_gpu = false; seg.do_3D = false
// The script auto-selects the coarsest pyramid level where the effective
// diameter is still >=15 px.  Override if needed:
// seg.additional_flags = "--resolution_level,2"
seg.run()

*/

return

import ch.epfl.biop.wrappers.cellpose.ij2commands.CellposeDistributed
import ch.epfl.biop.wrappers.cellpose.ij2commands.ConvertToZarr
=======
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
>>>>>>> gpu_cpu_split
