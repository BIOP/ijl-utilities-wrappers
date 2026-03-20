"""
Demo script to explore itk-elastix point transformation API.

Run this in the itk-elastix-v0 environment after a registration has been done.
Adjust TRANSFORM_FILE to point to an existing TransformParameters.X.txt file.

Goal: find out how to transform points using the parameter file directly
(no deformation field computation).
"""

import itk
import os
import tempfile

# --- CONFIGURE THIS ---
# Point to a TransformParameters file from a previous registration
TRANSFORM_FILE = r"PUT_YOUR_TRANSFORM_PARAMETERS_FILE_HERE"

# Test points (x, y) - adjust to match your image coordinates
TEST_POINTS = [
    (100.0, 200.0),
    (150.0, 250.0),
    (300.0, 400.0),
]

# =====================================================================
# Step 1: Check what TransformixFilter exposes
# =====================================================================
task.update("=== Checking TransformixFilter API ===")
tfx_methods = [m for m in dir(itk.TransformixFilter) if 'oint' in m or 'def' in m.lower()]
task.update(f"Methods with 'oint' or 'def': {tfx_methods}")

all_set_methods = [m for m in dir(itk.TransformixFilter) if m.startswith('Set')]
task.update(f"\nAll Set* methods: {all_set_methods}")

# =====================================================================
# Step 2: Check transformix_filter function signature
# =====================================================================
task.update("\n=== transformix_filter signature ===")
help(itk.transformix_filter)

# =====================================================================
# Step 3: Try point transformation using TransformixFilter directly
# =====================================================================
if not os.path.exists(TRANSFORM_FILE):
    task.update(f"\n!!! Transform file not found: {TRANSFORM_FILE}")
    task.update("Set TRANSFORM_FILE to a valid path and re-run.")
    exit(0)

task.update(f"\n=== Attempting point transformation with {TRANSFORM_FILE} ===")

# Write input points in transformix format
tmp_dir = tempfile.mkdtemp(prefix="transformix_pts_")
input_pts_file = os.path.join(tmp_dir, "inputpoints.txt")

with open(input_pts_file, 'w') as f:
    f.write("point\n")
    f.write(f"{len(TEST_POINTS)}\n")
    for x, y in TEST_POINTS:
        f.write(f"{x}\t{y}\n")

task.update(f"Wrote input points to: {input_pts_file}")

# Load transform parameters
param_object = itk.ParameterObject.New()
param_object.ReadParameterFile(TRANSFORM_FILE)

# --- Approach A: Try SetFixedPointSetFileName if it exists ---
try:
    task.update("\n--- Approach A: TransformixFilter with SetFixedPointSetFileName ---")
    # We need a dummy image to create the filter (transformix requires moving image type)
    ImageType = itk.Image[itk.F, 2]

    tfx = itk.TransformixFilter[ImageType].New()
    tfx.SetTransformParameterObject(param_object)
    tfx.SetFixedPointSetFileName(input_pts_file)
    tfx.SetOutputDirectory(tmp_dir)

    # Some versions need ComputeDeformationField off, ComputeSpatialJacobian off
    try:
        tfx.SetComputeDeformationField(False)
    except:
        pass
    try:
        tfx.SetComputeSpatialJacobian(False)
    except:
        pass

    # TransformixFilter may not need an actual input image for point-only transforms
    # But it might require one — let's try without first, then with a dummy
    try:
        tfx.UpdateLargestPossibleRegion()
    except Exception as e:
        task.update(f"  Without input image failed: {e}")
        task.update("  Trying with a dummy 10x10 image...")
        dummy = itk.image_from_array(itk.array_from_image(itk.Image[itk.F, 2].New(Regions=[10, 10])))
        tfx.SetMovingImage(dummy)
        tfx.UpdateLargestPossibleRegion()

    # Check for output points file
    output_pts_file = os.path.join(tmp_dir, "outputpoints.txt")
    if os.path.exists(output_pts_file):
        task.update(f"\n  SUCCESS! Output points file created: {output_pts_file}")
        task.update("  Content:")
        with open(output_pts_file, 'r') as f:
            for line in f:
                task.update(f"    {line.rstrip()}")
    else:
        task.update(f"  No outputpoints.txt found in {tmp_dir}")
        task.update(f"  Files in tmp_dir: {os.listdir(tmp_dir)}")

except AttributeError as e:
    task.update(f"  SetFixedPointSetFileName not available: {e}")
except Exception as e:
    task.update(f"  Approach A failed: {type(e).__name__}: {e}")

# --- Approach B: Try via transformix_filter function kwargs ---
try:
    task.update("\n--- Approach B: itk.transformix_filter with fixed_point_set_file_name ---")
    # Create a small dummy image
    import numpy as np
    dummy_arr = np.zeros((10, 10), dtype=np.float32)
    dummy_img = itk.image_from_array(dummy_arr)

    result = itk.transformix_filter(
        dummy_img,
        param_object,
        fixed_point_set_file_name=input_pts_file,
        output_directory=tmp_dir,
    )
    output_pts_file = os.path.join(tmp_dir, "outputpoints.txt")
    if os.path.exists(output_pts_file):
        task.update(f"  SUCCESS! Output points file created.")
        with open(output_pts_file, 'r') as f:
            for line in f:
                task.update(f"    {line.rstrip()}")
    else:
        task.update(f"  Files in tmp_dir: {os.listdir(tmp_dir)}")

except TypeError as e:
    task.update(f"  Kwarg not supported: {e}")
except Exception as e:
    task.update(f"  Approach B failed: {type(e).__name__}: {e}")

task.update(f"\n=== Done. Temp dir: {tmp_dir} ===")