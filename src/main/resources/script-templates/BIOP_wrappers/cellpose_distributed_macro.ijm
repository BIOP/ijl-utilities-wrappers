#@File(style="directory", label="Select conda environment") conda_env_path
#@File(style="directory", label="Select output directory") output_directory

// ============================================================
// Distributed Cellpose — IJ Macro template
//
// The Fiji command accepts an open image, a regular file,
// a TIFF folder, or an existing Zarr store.
// ============================================================


// ============================================================
// SCENARIO A — Segment the active open image
// ============================================================

image_title = getTitle();
run("Cellpose Distributed...",
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
*/


// ============================================================
// SCENARIO C — Segment an existing Zarr or TIFF folder on disk
// ============================================================

/*
run("Cellpose Distributed...",
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
*/

