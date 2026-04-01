#@ImagePlus imp
#@File(style="directory", label="Select conda environment") conda_env_path
#@File(style="directory", label="Select output directory") output_directory

// ============================================================
// Distributed Cellpose — Groovy script template
//
// Required conda packages:
//   conda install -c conda-forge cellpose tifffile scikit-image
//   pip install "cellpose[distributed]" bioio bioio-bioformats
//
// Three usage scenarios are shown below.
// Un-comment the one you need and comment out the others.
// ============================================================


// ============================================================
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

cp.run()


// ============================================================
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
// conv.pixel_size_x_um = 0.65
// conv.pixel_size_y_um = 0.65
// conv.pixel_size_z_um = 1.0
conv.run()

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

*/


// ============================================================
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
