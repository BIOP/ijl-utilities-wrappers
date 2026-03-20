import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo that registers the test blob images (TIF files on disk) via itk-elastix + Appose.
 *
 * <p>This is the closest to the real workflow:
 * Java passes image file paths and an output folder to Python,
 * Python does registration and transformation, writes results to the output folder.</p>
 *
 * <p>Requires the test resources: {@code src/test/resources/blobs.tif}
 * and {@code src/test/resources/blobs-rot15deg.tif}.</p>
 */
public class DemoItkElastixWithImages {

    public static void main(String... args) throws Exception {

        // 1. Locate test images
        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        System.out.println("Fixed image:  " + fixedImage.getAbsolutePath());
        System.out.println("Moving image: " + movingImage.getAbsolutePath());

        // 2. Create output directory
        Path outputDir = Files.createTempDirectory("itk-elastix-blobs-demo");
        System.out.println("Output directory: " + outputDir);

        // 3. Build environment
        System.out.println("Building Appose environment (first run may take a few minutes)...");
        final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();

        // 4. Run registration
        System.out.println("--- STEP 1: Registration ---");
        try (Service python = env.python().init(callImports())) {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("fixed_image_path", fixedImage.getAbsolutePath().replace("\\", "/"));
            inputs.put("moving_image_path", movingImage.getAbsolutePath().replace("\\", "/"));
            inputs.put("output_folder", outputDir.toString().replace("\\", "/"));

            // Specify transform types to chain (like the Java Elastix_Register command)
            List<String> transform_types = new ArrayList<>();
            transform_types.add("rigid");
            inputs.put("transform_types", transform_types);

            final Service.Task task = python.task(getRegistrationScript(), inputs);
            task.listen(evt -> System.out.println("[Registration] " + evt.message));
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("Registration failed: " + task.error);
            }

            System.out.println("Transform file: " + task.outputs.get("transform_file"));
        }

        // 5. Apply transform to the moving image
        System.out.println("--- STEP 2: Transformation ---");
        try (Service python = env.python().init(callImports())) {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("image_path", movingImage.getAbsolutePath().replace("\\", "/"));
            inputs.put("output_folder", outputDir.toString().replace("\\", "/"));

            // Point to the last transform parameter file
            File tpFile = new File(outputDir.toFile(), "TransformParameters.0.txt");
            inputs.put("transform_file", tpFile.getAbsolutePath().replace("\\", "/"));

            final Service.Task task = python.task(getTransformScript(), inputs);
            task.listen(evt -> System.out.println("[Transform] " + evt.message));
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("Transformation failed: " + task.error);
            }

            System.out.println("Result image: " + task.outputs.get("result_image"));
        }

        // 6. Verify
        File transformFile = new File(outputDir.toFile(), "TransformParameters.0.txt");
        File resultImage = new File(outputDir.toFile(), "result.tif");

        System.out.println();
        System.out.println("=== Verification ===");
        System.out.println("TransformParameters.0.txt exists: " + transformFile.exists());
        System.out.println("result.tif exists:                " + resultImage.exists());

        if (transformFile.exists() && resultImage.exists()) {
            System.out.println("SUCCESS - Full round-trip (registration + transformation) works!");
            System.out.println("You can open " + resultImage.getAbsolutePath() + " to inspect the result.");
        } else {
            System.err.println("FAILURE - output files missing");
        }
    }

    private static String callImports() {
        return ""
                + "import itk\n"
                + "import numpy as np\n"
                + "import os\n";
    }

    /**
     * Python script for elastix registration.
     * Reads images from disk, runs registration, writes TransformParameters files.
     */
    private static String getRegistrationScript() {
        return ""
                + "task.update('Loading images...')\n"
                + "fixed = itk.imread(fixed_image_path, itk.F)\n"
                + "moving = itk.imread(moving_image_path, itk.F)\n"
                + "\n"
                + "task.update('Setting up parameter maps...')\n"
                + "parameter_object = itk.ParameterObject.New()\n"
                + "for tt in transform_types:\n"
                + "    pm = parameter_object.GetDefaultParameterMap(tt)\n"
                + "    parameter_object.AddParameterMap(pm)\n"
                + "\n"
                + "task.update('Running registration...')\n"
                + "result_image, result_params = itk.elastix_registration_method(\n"
                + "    fixed, moving,\n"
                + "    parameter_object=parameter_object\n"
                + ")\n"
                + "\n"
                + "task.update('Saving transform parameters...')\n"
                + "last_tp = None\n"
                + "for i in range(result_params.GetNumberOfParameterMaps()):\n"
                + "    tp_path = os.path.join(output_folder, f'TransformParameters.{i}.txt')\n"
                + "    result_params.WriteParameterFile(\n"
                + "        result_params.GetParameterMap(i),\n"
                + "        tp_path\n"
                + "    )\n"
                + "    last_tp = tp_path\n"
                + "\n"
                + "task.outputs['transform_file'] = last_tp\n"
                + "task.update('done.')\n";
    }

    /**
     * Python script for transformix image transformation.
     * Reads an image and a transform parameter file, applies the transform, writes the result.
     */
    private static String getTransformScript() {
        return ""
                + "task.update('Loading image...')\n"
                + "moving = itk.imread(image_path, itk.F)\n"
                + "\n"
                + "task.update('Loading transform parameters...')\n"
                + "param_object = itk.ParameterObject.New()\n"
                + "param_object.ReadParameterFile(transform_file)\n"
                + "\n"
                + "task.update('Applying transform...')\n"
                + "result = itk.transformix_filter(moving, param_object)\n"
                + "\n"
                + "result_path = os.path.join(output_folder, 'result.tif')\n"
                + "itk.imwrite(result, result_path)\n"
                + "\n"
                + "task.outputs['result_image'] = result_path\n"
                + "task.update('done.')\n";
    }
}