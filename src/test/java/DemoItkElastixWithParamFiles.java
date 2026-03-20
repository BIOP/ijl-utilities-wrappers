import ch.epfl.biop.wrappers.elastix.RegParamAffine_Fast;
import ch.epfl.biop.wrappers.elastix.RegistrationParameters;
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
 * Demo that bridges the existing Java {@link RegistrationParameters} system
 * with itk-elastix via Appose.
 *
 * <p>This shows that:</p>
 * <ol>
 *   <li>Java creates parameter files using the existing {@code RegParam*} presets</li>
 *   <li>Those files are passed to Python via Appose</li>
 *   <li>Python reads them with {@code itk.ParameterObject.ReadParameterFile()}</li>
 *   <li>Registration runs via itk-elastix</li>
 *   <li>Transform parameters are written back in the same format the Java side expects</li>
 * </ol>
 *
 * <p>This proves the migration path: keep RegistrationParameters, swap the execution backend.</p>
 */
public class DemoItkElastixWithParamFiles {

    public static void main(String... args) throws Exception {

        // 1. Use existing Java infrastructure to create a parameter file
        RegistrationParameters rp = new RegParamAffine_Fast();
        File paramFile = rp.toFile(rp);
        System.out.println("Java-generated parameter file: " + paramFile.getAbsolutePath());
        System.out.println("Parameter file content:\n" + RegistrationParameters.toString(rp));

        // 2. Create output directory
        Path outputDir = Files.createTempDirectory("itk-elastix-param-demo");
        System.out.println("Output directory: " + outputDir);

        // 3. Build environment
        System.out.println("Building Appose environment...");
        final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();

        // 4. Run registration using Java-generated parameter file
        try (Service python = env.python().init(callImports())) {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("output_folder", outputDir.toString().replace("\\", "/"));

            // Pass parameter file paths as a list (supports chained transforms)
            List<String> paramFiles = new ArrayList<>();
            paramFiles.add(paramFile.getAbsolutePath().replace("\\", "/"));
            inputs.put("parameter_files", paramFiles);

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

        // 5. Verify
        File transformFile = new File(outputDir.toFile(), "TransformParameters.0.txt");
        File resultImage = new File(outputDir.toFile(), "result.tif");

        System.out.println();
        System.out.println("=== Verification ===");
        System.out.println("TransformParameters.0.txt exists: " + transformFile.exists());
        System.out.println("result.tif exists:                " + resultImage.exists());

        if (transformFile.exists() && resultImage.exists()) {
            System.out.println("SUCCESS - Java parameter files work with itk-elastix via Appose!");
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
                // Create synthetic images
                + "task.update('Creating synthetic test images...')\n"
                + "fixed_array = np.zeros((100, 100), dtype=np.float32)\n"
                + "fixed_array[30:70, 30:70] = 1.0\n"
                + "fixed_image = itk.image_from_array(fixed_array)\n"
                + "\n"
                + "moving_array = np.zeros((100, 100), dtype=np.float32)\n"
                + "moving_array[35:75, 35:75] = 1.0\n"
                + "moving_image = itk.image_from_array(moving_array)\n"
                + "\n"
                // Load Java-generated parameter files
                + "task.update('Loading Java-generated parameter files...')\n"
                + "parameter_object = itk.ParameterObject.New()\n"
                + "for pf in parameter_files:\n"
                + "    task.update(f'  Reading: {pf}')\n"
                + "    parameter_object.ReadParameterFile(pf)\n"
                + "\n"
                // Run registration
                + "task.update('Running registration with Java parameters...')\n"
                + "result_image, result_params = itk.elastix_registration_method(\n"
                + "    fixed_image, moving_image,\n"
                + "    parameter_object=parameter_object\n"
                + ")\n"
                + "\n"
                // Save transform parameters
                + "task.update('Saving transform parameters...')\n"
                + "for i in range(result_params.GetNumberOfParameterMaps()):\n"
                + "    tp_path = os.path.join(output_folder, f'TransformParameters.{i}.txt')\n"
                + "    result_params.WriteParameterFile(\n"
                + "        result_params.GetParameterMap(i),\n"
                + "        tp_path\n"
                + "    )\n"
                + "\n"
                // Apply transform
                + "task.update('Applying transform...')\n"
                + "result_transformed = itk.transformix_filter(moving_image, result_params)\n"
                + "\n"
                // Save result
                + "result_path = os.path.join(output_folder, 'result.tif')\n"
                + "itk.imwrite(result_transformed, result_path)\n"
                + "\n"
                + "tp0 = os.path.join(output_folder, 'TransformParameters.0.txt')\n"
                + "task.outputs['transform_file'] = tp0\n"
                + "task.outputs['result_image'] = result_path\n"
                + "task.update('done.')\n";
    }
}