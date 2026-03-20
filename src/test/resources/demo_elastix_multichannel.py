"""
Test script: multi-channel registration using itk-elastix
SetFixedImage + AddFixedImage / SetMovingImage + AddMovingImage API.

Run via DemoElastixMultiChannelAPI.java which injects fixed_image_paths,
moving_image_paths, and parameter_file as Python variables.
"""

import itk
import os
import tempfile
import shutil

task.update(f"Loading {len(fixed_image_paths)} fixed and {len(moving_image_paths)} moving image(s)...")
fixed_imgs = [itk.imread(p, itk.F) for p in fixed_image_paths]
moving_imgs = [itk.imread(p, itk.F) for p in moving_image_paths]

# Load parameter file
param_obj = itk.ParameterObject.New()
param_obj.ReadParameterFile(parameter_file)

out_dir = tempfile.mkdtemp(prefix="elastix_mc_")

task.update("=== Multi-channel registration via SetFixedImage + AddFixedImage ===")
ImageType = type(fixed_imgs[0])
erm = itk.ElastixRegistrationMethod[ImageType, ImageType].New()

# First image via Set, additional images via Add
erm.SetFixedImage(fixed_imgs[0])
for fimg in fixed_imgs[1:]:
    erm.AddFixedImage(fimg)

erm.SetMovingImage(moving_imgs[0])
for mimg in moving_imgs[1:]:
    erm.AddMovingImage(mimg)

erm.SetParameterObject(param_obj)
erm.SetOutputDirectory(out_dir)
erm.SetLogToConsole(False)

task.update(f"  NumberOfFixedImages: {erm.GetNumberOfFixedImages()}")
task.update(f"  NumberOfMovingImages: {erm.GetNumberOfMovingImages()}")

task.update("  Running registration...")
erm.UpdateLargestPossibleRegion()

result_params = erm.GetTransformParameterObject()
task.update(f"  Result parameter maps: {result_params.GetNumberOfParameterMaps()}")

# Check output files
files = os.listdir(out_dir)
task.update(f"  Output dir contents: {files}")

tp_file = os.path.join(out_dir, "TransformParameters.0.txt")
if os.path.exists(tp_file):
    task.update("  SUCCESS! TransformParameters.0.txt created.")
    with open(tp_file, 'r') as f:
        for line in f:
            task.update(f"    {line.rstrip()}")
else:
    task.update("  FAILURE: TransformParameters.0.txt not found")

# Cleanup
shutil.rmtree(out_dir, ignore_errors=True)
task.update("\n=== Done ===")