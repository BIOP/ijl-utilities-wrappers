#@File(style="directory", label="Select conda environment") conda_env_path
#@File(style="directory", label="Select output directory") output_directory

// ============================================================
// Distributed Cellpose — IJ Macro template
//
<<<<<<< HEAD
// Required conda packages:
//   conda install -c conda-forge cellpose tifffile scikit-image
//   pip install "cellpose[distributed]" bioio bioio-bioformats
//
// Three usage scenarios are shown below.
// Un-comment the one you need and comment out the others.
=======
// The Fiji command accepts an open image, a regular file,
// a TIFF folder, or an existing Zarr store.
>>>>>>> gpu_cpu_split
// ============================================================


// ============================================================
// SCENARIO A — Segment the active open image
<<<<<<< HEAD
// The image is automatically converted to a pyramidal OME-Zarr,
// and blocksize / cluster are auto-configured from available RAM.
//run("Blobs (25K)"); // uncomment to test on a sample image
=======
>>>>>>> gpu_cpu_split
// ============================================================

image_title = getTitle();
run("Cellpose Distributed...",
<<<<<<< HEAD
    "imp=" + image_title +
    " env_path=" + conda_env_path +
    " env_type=conda" +
    " output_directory=" + output_directory +
    " output_name=cellpose_mask.ome.tif" +
    " pretrained_model=cyto3" +
    " diameter=30" +
    " diameter_unit=µm" +
    " chan=-1 chan2=-1 channel_axis=-1" +
    " use_gpu=false do_3D=false" +
    " additional_flags=");


// ============================================================
// SCENARIO B — Pre-convert a file to OME-Zarr, then segment
//
// Useful for multi-scene / proprietary formats and when you
// want to reuse the Zarr across multiple segmentation runs.
// ============================================================

/*
zarr_path = output_directory + File.separator + "my_image.zarr";

// Step 1: convert to OME-Zarr pyramid
run("Convert to Zarr (for Distributed Cellpose)...",
    "input_file=/path/to/my_image.czi" +
    " output_zarr_path=" + zarr_path +
    " env_path=" + conda_env_path +
    " env_type=conda" +
    " chunks=64,64,64" +
    " n_levels=4" +
    " pixel_size_x_um=0" +  // 0 = read from file metadata
    " pixel_size_y_um=0" +
    " pixel_size_z_um=0");

// Step 2: segment
run("Cellpose Distributed...",
    "input_zarr_path=" + zarr_path +
    " env_path=" + conda_env_path +
    " env_type=conda" +
    " output_directory=" + output_directory +
    " output_name=cellpose_mask.ome.tif" +
    " pretrained_model=cyto3" +
    " diameter=30" +
    " diameter_unit=µm" +
    " chan=-1 chan2=-1 channel_axis=-1" +
    " pixel_size_z_um=0" +  // 0 = read from zarr metadata; set e.g. 1.0 for correct anisotropy
    " use_gpu=false do_3D=false" +
    " additional_flags=");
=======
    "imp=[" + image_title + "]" +
    " env_path=[" + conda_env_path + "]" +
    " env_type=conda" +
    " output_directory=[" + output_directory + "]" +
    " output_name=cellpose_mask" +
    " output_format=ome-zarr" +
    " output_resolution=level0" +
    " model=cyto3" +
    " diameter=30" +
    " ch1=1 ch2=0 channel_axis=-1" +
    " blocksize=auto resolution_level=-1" +
    " auto_cluster use_gpu=false do_3D=false" +
    " show_dashboard reuse_zarr" +
    " cellprob_threshold=0 min_size=15" +
    " flow3D_smooth=1 cellprob_smooth=0" +
    " no_resample=false" +
    " additional_flags=[]");


// ============================================================
// SCENARIO B — Convert a source file to Zarr, then segment it
// ============================================================

/*
zarr_path = output_directory + File.separator + "my_image_input.zarr";

run("Convert to Zarr (for Distributed Cellpose)...",
    "input_file=[/path/to/my_image.czi]" +
    " output_zarr_path=[" + zarr_path + "]" +
    " env_path=[" + conda_env_path + "]" +
    " env_type=conda" +
    " chunks=auto" +
    " n_levels=auto");

run("Cellpose Distributed...",
    "input_file_or_folder=[" + zarr_path + "]" +
    " env_path=[" + conda_env_path + "]" +
    " env_type=conda" +
    " output_directory=[" + output_directory + "]" +
    " output_name=cellpose_mask" +
    " output_format=ome-zarr" +
    " output_resolution=level0" +
    " model=nuclei" +
    " diameter=30" +
    " pixel_size_xy_um=0.65 pixel_size_z_um=1.0" +
    " ch1=1 ch2=0 channel_axis=-1" +
    " use_gpu do_3D" +
    " additional_flags=[]");
>>>>>>> gpu_cpu_split
*/


// ============================================================
<<<<<<< HEAD
// SCENARIO C — Segment a pre-existing OME-Zarr on disk
=======
// SCENARIO C — Segment an existing Zarr or TIFF folder on disk
>>>>>>> gpu_cpu_split
// ============================================================

/*
run("Cellpose Distributed...",
<<<<<<< HEAD
    "input_zarr_path=/path/to/existing.zarr" +
    " env_path=" + conda_env_path +
    " env_type=conda" +
    " output_directory=" + output_directory +
    " output_name=cellpose_mask.ome.tif" +
    " pretrained_model=cyto3" +
    " diameter=30" +
    " diameter_unit=µm" +
    " pixel_size_xy_um=0.108" +  // omit or set to e.g. 0.108 to override zarr metadata
    " pixel_size_z_um=1.0" +     // omit or set to e.g. 1.0 for correct anisotropy in 3-D
    " chan=-1 chan2=-1 channel_axis=-1" +
    " use_gpu=false do_3D=false" +
    " additional_flags=");
=======
    "input_file_or_folder=[/path/to/existing.zarr]" +
    // "input_file_or_folder=[/path/to/tiff_folder]" +
    " env_path=[" + conda_env_path + "]" +
    " env_type=conda" +
    " output_directory=[" + output_directory + "]" +
    " output_name=cellpose_mask" +
    " output_format=ome-tiff" +
    " output_resolution=native" +
    " model=cyto3" +
    " diameter=30" +
    " pixel_size_xy_um=0.108 pixel_size_z_um=1.0" +
    " ch1=1 ch2=0 channel_axis=-1" +
    " blocksize=auto resolution_level=-1" +
    " auto_cluster use_gpu do_3D" +
    " additional_flags=[]");
>>>>>>> gpu_cpu_split
*/

