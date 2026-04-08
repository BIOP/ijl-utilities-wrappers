# Distributed Cellpose README

This document explains the distributed Cellpose workflow in this repository.

It is written for users who want to run segmentation from Fiji, from the Python CLI, or both.

The goal is simple:

- make the available options easy to understand
- explain which settings matter most in practice
- show the difference between everyday settings and expert-only settings


## What this tool does

The distributed Cellpose workflow is designed for large 2D and 3D datasets.

Instead of loading the whole image into memory at once, it:

- reads a Zarr or OME-Zarr input, or converts another file to Zarr first
- selects a suitable pyramid level
- splits the image into blocks
- runs Cellpose block by block
- writes the labels as OME-TIFF or OME-Zarr

The main Python entry point is:

- `src/main/resources/distributed_cellpose_run.py`

The Fiji command is:

- `Plugins > BIOP > Cellpose > Cellpose Distributed ...`

The repository also includes a dedicated conversion script to prepare input data as OME-Zarr before segmentation:

- `src/main/resources/convert_to_zarr_for_cellpose.py`

This is useful when:

- your source data is an IMS, CZI, or another file format not already stored as Zarr
- you want to reuse the same converted input for several segmentation runs
- you want to inspect the converted pyramid before launching Cellpose


## Preparing input data as OME-Zarr

Distributed Cellpose works best when the input is already available as a Zarr or OME-Zarr pyramid.

There are two ways to do that:

- let the Fiji plugin convert the input automatically
- run the converter script yourself first

The bundled converter script is:

- `src/main/resources/convert_to_zarr_for_cellpose.py`

Example:

```powershell
s:/pixi/cellpose/.pixi/envs/cellpose3/python.exe src/main/resources/convert_to_zarr_for_cellpose.py \
  --input_path "U:/path/to/input.ims" \
  --output_zarr "U:/path/to/input_for_cellpose.zarr" \
  --chunks auto \
  --n_levels auto
```

Why you may want to do the conversion explicitly:

- it lets you prepare the data once and segment it multiple times
- it makes debugging easier because conversion and segmentation become separate steps
- it gives you a persistent OME-Zarr input that can be checked before a long run

In Fiji, the distributed Cellpose command can also do this conversion automatically when the input is not already a Zarr store.


## Typical usage

### Environment path in Fiji

The Fiji wrappers now support three environment types:

- `conda`
- `venv`
- `pixi`

For `conda` and `venv`, select the environment directory itself.

For `pixi`, select the Pixi project root, meaning the folder that contains:

- `.pixi`

Example:

- `S:/pixi/napari`

The wrappers resolve Pixi paths like this:

- if the selected path is a Pixi project root, they look inside `.pixi/envs`
- if `.pixi/envs/default` exists, they use it
- if there is only one environment inside `.pixi/envs`, they use that one automatically
- if there are multiple environments, you should point Fiji to the specific environment directory you want, for example `S:/pixi/cellpose/.pixi/envs/cellpose3`

This is important for projects such as:

- `S:/pixi/cellpose/.pixi/envs`

where several Pixi environments may exist side by side.

### From Fiji

Use the Fiji command when you want a guided interface.

The plugin can start from:

- the currently open image
- a file on disk
- a folder of TIFF tiles
- an existing Zarr store

If the input is not already a Zarr store, the plugin can convert it automatically before segmentation.


### From CLI

Use the CLI when you want exact control, scripting, or reproducibility.

Example:

```powershell
s:/pixi/cellpose/.pixi/envs/cellpose3/python.exe src/main/resources/distributed_cellpose_run.py \
  --zarr_input "U:/path/to/input.zarr" \
  --output_path "U:/path/to/output.ome.zarr" \
  --output_format ome-zarr \
  --model_type nuclei \
  --diameter_um 7 \
  --channel 1 \
  --use_gpu \
  --do_3D \
  --auto_cluster \
  --resolution_level -1 \
  --output_resolution level0
```


## Viewing the original Zarr and result in Napari

If you want to inspect the original image together with the segmentation result, Napari is a convenient viewer.

### Creating the Napari Pixi environment

To recreate an equivalent Napari Pixi environment, create a new Pixi project and install the same Python version and dependencies:

```powershell
mkdir S:/pixi/napari
cd S:/pixi/napari
pixi init --format pyproject
pixi add python=3.11.*
pixi add "napari[pyqt6,optional]>=0.7.0,<0.8" "napari-tiff>=0.1.5,<0.2" "napari-animation"
```

This will create the Pixi-managed environment for that project, typically under:

- `S:/pixi/napari/.pixi/envs/default`

You can also let Pixi create it automatically the first time you run Napari:

```powershell
cd S:/pixi/napari
pixi run napari
```

This Napari environment includes:

- `napari[pyqt6,optional]`
- `napari-tiff`
- `napari-animation`

So it is suitable for opening the original Zarr and the segmentation result in the same viewer session.

With a Pixi project such as:

- `S:/pixi/napari`

you can launch Napari from that Pixi project root and open both datasets in the same session.

Typical examples:

```powershell
cd S:/pixi/napari
pixi run napari U:/path/to/input.zarr U:/path/to/output.ome.zarr
```

If your output was written as OME-TIFF instead:

```powershell
cd S:/pixi/napari
pixi run napari U:/path/to/input.zarr U:/path/to/output.ome.tif
```

What this does:

- opens the original Zarr or OME-Zarr image
- opens the segmentation result in the same Napari session
- lets you compare the raw image and labels directly as separate layers

Practical note:

- OME-Zarr output is usually the most convenient choice for large results in Napari
- if you use the Fiji wrappers with `env_type = pixi`, you can select either the Pixi project root or the exact `.pixi/envs/<name>` directory
- if the Pixi project has multiple environments and no `default`, select the exact environment directory you want


## The most important options

If you ignore everything else, these are the settings most users actually need:

### Input

- `--zarr_input`: use an existing Zarr or OME-Zarr input
- `--tiff_input_folder`: use a folder of tiled TIFFs

In Fiji, this is handled by the input image or the `Input File or Folder` field.


### Output

- `--output_path`: where the labels will be written
- `--output_format`: `ome-tiff` or `ome-zarr`
- `--output_resolution`: `native` or `level0`

Meaning:

- `native`: keep the selected working pyramid level as the highest-resolution output
- `level0`: resize labels back to full-resolution output space


### Model and object size

- `--model_type`: built-in Cellpose model such as `cyto3` or `nuclei`
- `--pretrained_model`: custom model path or built-in model name
- `--diameter`: object size in pixels
- `--diameter_um`: object size in micrometers

Recommendation:

- prefer `--diameter_um` if your data has correct pixel size metadata
- use `--diameter` only when you want to work directly in pixels


### Channels

- `--channel`: user-friendly primary channel, 1-based
- `--nucleus_channel`: user-friendly secondary channel, 1-based
- `--ch1`: primary channel, 0-based internal form
- `--ch2`: secondary channel, 0-based internal form
- `--channel_axis`: where the channel axis is in the source array

Recommendation:

- in Fiji, use the GUI channel fields
- in scripts, use `--channel` and `--nucleus_channel` unless you specifically want the internal indexing


### Compute strategy

- `--use_gpu`: use GPU inference if available
- `--do_3D`: enable 3D Cellpose mode
- `--auto_cluster`: automatically choose workers and memory settings
- `--resolution_level`: pick a specific pyramid level, or use `-1` for automatic selection

Recommendation:

- for large 3D data, start with `--use_gpu --do_3D --auto_cluster --resolution_level -1`


## How automatic optimization works

There are two separate automatic choices during planning:

- the working pyramid level selected by `--resolution_level -1`
- the processing block size selected by `--blocksize auto`

These two decisions are linked, because the chosen pyramid level changes the effective object size in pixels, and that effective size is then used to estimate a safe block size.


### Automatic pyramid level selection

When `--resolution_level` is set to `-1`, the script inspects all available pyramid levels and scores them.

The main idea is:

- convert the requested object diameter to full-resolution pixels
- compute what that diameter would look like at each pyramid level
- prefer the level where the effective diameter is closest to the model's training scale

In practice, the script uses these training targets:

- `nuclei` models target about `17 px`
- most other models target about `30 px`

For each candidate level, it computes an effective diameter:

- effective diameter at level = full-resolution diameter / XY downsampling factor of that level

It then prefers the level whose effective diameter is closest to the model training diameter.

The selection is not based only on closeness. The script also adds penalties when a level would make the objects too small:

- if the effective XY diameter drops below `15 px`, that level is strongly penalized
- in `--do_3D` mode, if the effective Z diameter drops below about `4 slices`, that level is also penalized

If two levels score similarly, the script prefers the finer one, meaning the less-downsampled level.

Practical interpretation:

- large objects are more likely to be segmented from a coarser pyramid level
- small objects are more likely to stay at a finer level
- 3D data is protected against choosing a level that would leave too little Z context

This choice only controls the working resolution used during segmentation. The exported labels can still be written either:

- at the selected working resolution with `output_resolution = native`
- resampled back to the original full-resolution space with `output_resolution = level0`


### Automatic block size selection

When `--blocksize auto` is used, the script estimates a block size that is large enough to contain several objects, but small enough to fit the available memory.

The blocksize planner uses:

- the selected working pyramid level
- the effective object diameter at that level
- whether the run uses CPU or GPU
- the number of processed channels
- the per-worker memory budget
- the array chunking of the input Zarr

The memory budget depends on the execution mode:

- in GPU mode, it uses about `85%` of the available GPU memory for planning
- in CPU mode, it uses about `80%` of the available RAM, divided by the planned number of workers

The script then estimates how much image area can safely fit in memory after accounting for:

- image datatype size
- one or two processed channels
- internal overhead during Cellpose processing
- rescaling between the chosen level and the model training scale

The result is converted into block edges with a few guardrails.

For 2D runs:

- XY block edges are kept at least around `5 x diameter`, with a minimum of `64 px`
- XY block edges are capped at `4096 px` on GPU and `1024 px` on CPU

For `--do_3D` runs:

- XY is still the main memory constraint, because the processing is dominated by 2D-style inference passes
- XY edges use the same diameter-based minimum, but GPU runs are usually capped at `2048 px`
- if `--no_resample` is active, the GPU XY cap can grow to `4096 px`
- Z is chosen separately to keep enough depth context, typically targeting about `6 x diameter / anisotropy`, with lower and upper safeguards

After that, the block size is aligned to the input Zarr chunk grid so that reads are more storage-friendly.

Practical interpretation:

- bigger objects usually lead to bigger minimum blocks
- more channels usually reduce the chosen block size
- more workers in CPU mode usually reduce the per-worker memory budget, which can reduce block size
- chunk alignment can make the final blocksize slightly larger than the raw estimate


### Interaction with `auto_cluster`

`auto_cluster` affects auto blocksize because it decides how many workers share the available RAM.

The sequence is:

1. choose workers and memory per worker
2. compute auto blocksize from that per-worker memory budget
3. estimate the number of planned blocks
4. if there are fewer blocks than workers, reduce the worker count
5. recompute blocksize with the larger memory budget per remaining worker

This means the final blocksize is sometimes larger than the first estimate, especially on small or moderately sized datasets where the initial worker count would have been excessive.


### What to use in practice

For most large datasets, the intended default is:

- `resolution_level = -1`
- `blocksize = auto`
- `auto_cluster = true`

Use manual values only when:

- you already know a specific pyramid level gives better biological results
- you want reproducible benchmarking with a fixed block layout
- you are debugging performance or storage behavior


## Full option reference

This section describes the available CLI options in plain language.


### Input options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--zarr_input` | Path to an existing Zarr or OME-Zarr input | Best choice when data is already prepared |
| `--tiff_input_folder` | Path to a folder of tiled TIFFs | Use when your input is already tiled on disk |
| `--tiff_glob` | Restrict which TIFF files are used | Useful when a folder contains extra files |
| `--tiff_block_pattern` | Regex used to infer tile positions from file names | Expert option for custom TIFF tile naming |


### Output options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--output_path` | Output labels path | Required |
| `--output_format` | `ome-tiff` or `ome-zarr` | Choose based on your viewer and downstream workflow |
| `--output_tiff` | Deprecated old alias for TIFF output | Backward compatibility only |
| `--output_resolution` | `native` or `level0` | `level0` is usually easiest for downstream use |
| `--pyramidal_output` | Write a pyramid | Default behavior |
| `--no_pyramidal_output` | Write only one level | Use only if you explicitly want a single-resolution result |

Notes:

- OME-Zarr output is pyramidal by default unless `--no_pyramidal_output` is used.
- OME-Zarr output now uses sharding automatically when the runtime supports it.


### Model options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--model_type` | Built-in model name | `cyto3`, `nuclei`, `cpsam`, and similar |
| `--pretrained_model` | Custom model path or explicit model selection | Use for your own trained model |


### Size and calibration options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--diameter` | Object diameter in level-0 pixels | Use when working in pixels |
| `--diameter_um` | Object diameter in micrometers | Preferred when metadata is reliable |
| `--pixel_size_xy_um` | Override XY pixel size | Use when metadata is missing or wrong |
| `--pixel_size_z_um` | Override Z pixel size | Important for correct 3D anisotropy |
| `--anisotropy` | Explicit Z/XY ratio override | Expert override when you want to force anisotropy manually |


### Channel options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--channel` | Primary channel, 1-based | Most user-friendly CLI form |
| `--nucleus_channel` | Secondary channel, 1-based | Use for nucleus-assisted segmentation |
| `--chan` / `--ch1` | Primary channel, 0-based | Internal-style option |
| `--chan2` / `--ch2` | Secondary channel, 0-based | Internal-style option |
| `--channel_axis` | Position of the channel axis in the array | Needed for non-standard array layouts |


### Blocking and resolution options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--blocksize` | Processing block size, or `auto` | Leave on `auto` unless tuning manually |
| `--resolution_level` | Pyramid level to segment | Use `-1` for automatic choice |


### Cluster and compute options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--auto_cluster` | Automatically choose worker settings | Recommended default |
| `--n_workers` | Number of workers | Manual cluster tuning |
| `--ncpus` | CPU cores per worker | Manual cluster tuning |
| `--memory_per_worker` | Memory limit per worker | Manual cluster tuning |
| `--use_gpu` | Enable GPU inference | Recommended if a compatible GPU is available |
| `--do_3D` | Enable 3D Cellpose | Required for volumetric 3D segmentation |
| `--dask_temp_directory` | Scratch directory for Dask | Useful when the default temp disk is too small or too slow |
| `--no_open_dask_dashboard` | Do not auto-open the Dask dashboard | Use in headless or quiet runs |


### Mask filtering and smoothing options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--cellprob_threshold` | Suppress weak masks | Increase to be stricter, decrease to be more permissive |
| `--min_size` | Remove very small masks | Useful to suppress debris or noise |
| `--max_size_fraction` | Remove overly large masks | Prevent giant merged masks |
| `--flow3D_smooth` | Smooth 3D flow field before reconstruction | Helpful for difficult 3D masks |
| `--cellprob_smooth` | Smooth 3D cell-probability field | Another 3D stabilization option |
| `--no_resample` | Skip flow/cellprob resampling | Faster, but can reduce mask quality |


### Utility options

| Option | Meaning | Typical use |
| --- | --- | --- |
| `--dry_run` | Print the computed plan and exit | Best option for checking settings before a long run |


## Fiji GUI coverage

The Fiji command exposes the most important settings directly.

Directly available in the GUI:

- input image or input file/folder
- environment path and environment type
- model and custom pretrained model path
- diameter
- pixel size XY and Z overrides
- primary and secondary channel
- channel axis
- output format
- output resolution
- output name and output directory
- blocksize
- resolution level
- auto cluster
- workers, CPUs per worker, memory per worker
- use GPU
- 3D mode
- open Dask dashboard
- reuse converted input Zarr
- cell probability threshold
- minimum object size
- flow 3D smoothing
- cell probability smoothing
- no resample
- additional CLI flags

Not currently exposed as dedicated GUI fields:

- `--tiff_glob`
- `--tiff_block_pattern`
- `--pyramidal_output`
- `--no_pyramidal_output`
- `--max_size_fraction`
- `--anisotropy`
- `--dry_run`
- `--dask_temp_directory`

These can still be passed through the Fiji field:

- `Additional CLI flags`

Example:

```text
--dry_run,--max_size_fraction,0.25,--dask_temp_directory,D:/dask_tmp
```

Important:

- the Fiji field is comma-separated
- each token must be separated by a comma
- flags with values must be written as separate comma-separated entries


## Recommended presets

### Fast first test on a large dataset

- `output_format = ome-zarr`
- `output_resolution = native`
- `resolution_level = -1`
- `auto_cluster = true`
- `use_gpu = true` if available
- `do_3D = true` for volumetric data
- `diameter_um` set correctly


### Highest practical downstream compatibility

- `output_format = ome-tiff`
- `output_resolution = level0`
- pyramidal output enabled

Use this when another tool expects TIFF, but be aware that some viewers struggle with very large 3D label TIFFs.


### Most viewer-friendly large-label output

- `output_format = ome-zarr`
- `output_resolution = level0`
- pyramidal output enabled

Use this when the result is very large and you want chunked, sharded access rather than one huge TIFF.


## Practical advice

- Start with `resolution_level = -1` unless you have a strong reason to force a level.
- Prefer `diameter_um` over `diameter` when the image calibration is known.
- Use `--dry_run` before expensive runs if you are unsure about the chosen level, blocksize, or output path.
- Choose OME-Zarr if the result is very large or if you want modern chunked storage.
- Choose OME-TIFF if your downstream tools require TIFF specifically.


## Troubleshooting shortcuts

### The run starts but uses too much memory

Try:

- `resolution_level = -1`
- `blocksize = auto`
- `auto_cluster = true`
- `output_format = ome-zarr`


### The diameter seems wrong

Check:

- whether pixel size metadata exists in the input Zarr
- whether `pixel_size_xy_um` and `pixel_size_z_um` should be set manually
- whether you should use `diameter_um` instead of `diameter`


### The output is hard to open in a viewer

Try:

- `output_format = ome-zarr`
- `output_resolution = level0`

This is often more robust for huge label volumes than OME-TIFF.


## Summary

If you want the shortest useful guidance:

- use Fiji for convenience
- use the CLI for scripting and full control
- use `diameter_um`, `resolution_level = -1`, and `blocksize = auto`
- use `ome-zarr` for very large outputs
- use `Additional CLI flags` in Fiji for the few advanced options not yet shown as their own GUI fields