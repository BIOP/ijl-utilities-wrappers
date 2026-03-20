import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Self-contained demo of itk-elastix via Appose.
 *
 * <p>Creates two synthetic images in Python (a square and a shifted square),
 * runs rigid registration with itk-elastix, applies the transform, and saves
 * the result. No ImageJ UI, no external executables, no conda env pre-setup
 * required &mdash; Appose + pixi handles everything.</p>
 *
 * <p>Run with {@code mvn exec:java -Dexec.mainClass=DemoItkElastix} or
 * directly from your IDE.</p>
 */
public class DemoItkElastix {

    public static void main(String... args) throws Exception {

        // 1. Create a temp output directory
        Path outputDir = Files.createTempDirectory("itk-elastix-demo");
        System.out.println("Output directory: " + outputDir);

        // 2. Build the Appose environment (pixi will install itk-elastix on first run)
        System.out.println("Building Appose environment (first run may take a few minutes)...");
        final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();

        // 3. Run registration + transformation in Python
        try (Service python = env.python().init(callImports())) {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("output_folder", outputDir.toString().replace("\\", "/"));

            final Service.Task task = python.task(getScript(), inputs);
            task.listen(evt -> System.out.println("[Python] " + evt.message));
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("itk-elastix demo failed: " + task.error);
            }

            System.out.println("Transform file: " + task.outputs.get("transform_file"));
            System.out.println("Result image:   " + task.outputs.get("result_image"));
        }

        // 4. Verify output files exist
        File transformFile = new File(outputDir.toFile(), "TransformParameters.0.txt");
        File resultImage = new File(outputDir.toFile(), "result.tif");

        System.out.println();
        System.out.println("=== Verification ===");
        System.out.println("TransformParameters.0.txt exists: " + transformFile.exists());
        System.out.println("result.tif exists:                " + resultImage.exists());

        if (transformFile.exists() && resultImage.exists()) {
            System.out.println("SUCCESS - itk-elastix via Appose works!");
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

    private static String getScript() {
        return ""
                // Create synthetic fixed image: centered square
                + "task.update('Creating synthetic test images...')\n"
                + "fixed_array = np.zeros((100, 100), dtype=np.float32)\n"
                + "fixed_array[30:70, 30:70] = 1.0\n"
                + "fixed_image = itk.image_from_array(fixed_array)\n"
                + "\n"
                // Create synthetic moving image: shifted square
                + "moving_array = np.zeros((100, 100), dtype=np.float32)\n"
                + "moving_array[35:75, 35:75] = 1.0\n"
                + "moving_image = itk.image_from_array(moving_array)\n"
                + "\n"
                // Set up registration parameters (rigid)
                + "task.update('Setting up registration parameters...')\n"
                + "parameter_object = itk.ParameterObject.New()\n"
                + "parameter_map = parameter_object.GetDefaultParameterMap('rigid')\n"
                + "parameter_object.AddParameterMap(parameter_map)\n"
                + "\n"
                // Run registration
                + "task.update('Running registration...')\n"
                + "result_image, result_params = itk.elastix_registration_method(\n"
                + "    fixed_image, moving_image,\n"
                + "    parameter_object=parameter_object\n"
                + ")\n"
                + "\n"
                // Save transform parameters (same format as CLI elastix)
                + "task.update('Saving transform parameters...')\n"
                + "for i in range(result_params.GetNumberOfParameterMaps()):\n"
                + "    tp_path = os.path.join(output_folder, f'TransformParameters.{i}.txt')\n"
                + "    result_params.WriteParameterFile(\n"
                + "        result_params.GetParameterMap(i),\n"
                + "        tp_path\n"
                + "    )\n"
                + "\n"
                // Apply transform (transformix equivalent)
                + "task.update('Applying transform...')\n"
                + "result_transformed = itk.transformix_filter(moving_image, result_params)\n"
                + "\n"
                // Save result image
                + "result_path = os.path.join(output_folder, 'result.tif')\n"
                + "itk.imwrite(result_transformed, result_path)\n"
                + "\n"
                // Return outputs
                + "tp0 = os.path.join(output_folder, 'TransformParameters.0.txt')\n"
                + "task.outputs['transform_file'] = tp0\n"
                + "task.outputs['result_image'] = result_path\n"
                + "task.update('done.')\n";
    }
}
