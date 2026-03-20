import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import org.apposed.appose.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the Python script {@code demo_elastix_multichannel.py} via Appose
 * to explore how itk-elastix handles multi-channel registration.
 *
 * <p>Uses the same test images as both "channels" — the goal is to discover
 * the API, not produce meaningful multi-channel results.</p>
 */
public class DemoElastixMultiChannelAPI {

    public static void main(String... args) throws Exception {

        File img1 = new File("src/test/resources/blobs.tif");
        File img2 = new File("src/test/resources/blobs-rot15deg.tif");

        if (!img1.exists() || !img2.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // Use the same image as both channels for fixed, and both for moving
        List<String> fixedPaths = Arrays.asList(
                img1.getAbsolutePath().replace("\\", "/"),
                img2.getAbsolutePath().replace("\\", "/")
        );
        List<String> movingPaths = Arrays.asList(
                img2.getAbsolutePath().replace("\\", "/"),
                img1.getAbsolutePath().replace("\\", "/")
        );

        // We need a parameter file — use the rigid default
        File paramFile = new File("src/test/resources/elastix_rigid_default.txt");
        if (!paramFile.exists()) {
            // Create a minimal rigid parameter file
            System.out.println("Creating a minimal rigid parameter file...");
            createMinimalRigidParam(paramFile);
        }

        // --- Load the Python script from resources ---
        String script;
        try (InputStream is = DemoElastixMultiChannelAPI.class.getResourceAsStream("/demo_elastix_multichannel.py")) {
            if (is == null) {
                System.err.println("Could not find demo_elastix_multichannel.py in resources.");
                return;
            }
            script = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        }

        // --- Run via Appose ---
        System.out.println("=== Running multi-channel API exploration via Appose ===");
        System.out.println("Fixed images:  " + fixedPaths);
        System.out.println("Moving images: " + movingPaths);

        Service python = ApposeElastixTask.getElastixApposeService();

        final Map<String, Object> inputs = new HashMap<>();
        inputs.put("fixed_image_paths", fixedPaths);
        inputs.put("moving_image_paths", movingPaths);
        inputs.put("parameter_file", paramFile.getAbsolutePath().replace("\\", "/"));

        final Service.Task task = python.task(script, inputs);
        task.listen(evt -> System.out.println("[py] " + evt.message));
        task.start();
        task.waitFor();

        if (task.status == Service.TaskStatus.COMPLETE) {
            System.out.println("\nScript completed successfully.");
        } else {
            System.err.println("\nScript failed: " + task.error);
        }
    }

    private static void createMinimalRigidParam(File file) throws Exception {
        // Use RegisterHelper to generate a proper parameter file
        // For simplicity, write a minimal one inline
        String content =
                "(FixedInternalImagePixelType \"float\")\n" +
                "(MovingInternalImagePixelType \"float\")\n" +
                "(FixedImageDimension 2)\n" +
                "(MovingImageDimension 2)\n" +
                "(UseDirectionCosines \"true\")\n" +
                "(Registration \"MultiResolutionRegistration\")\n" +
                "(Interpolator \"BSplineInterpolator\")\n" +
                "(ResampleInterpolator \"FinalBSplineInterpolator\")\n" +
                "(Resampler \"DefaultResampler\")\n" +
                "(FixedImagePyramid \"FixedSmoothingImagePyramid\")\n" +
                "(MovingImagePyramid \"MovingSmoothingImagePyramid\")\n" +
                "(Optimizer \"AdaptiveStochasticGradientDescent\")\n" +
                "(Transform \"EulerTransform\")\n" +
                "(Metric \"AdvancedMattesMutualInformation\")\n" +
                "(AutomaticScalesEstimation \"true\")\n" +
                "(AutomaticTransformInitialization \"true\")\n" +
                "(HowToCombineTransforms \"Compose\")\n" +
                "(NumberOfHistogramBins 32)\n" +
                "(ErodeMask \"false\")\n" +
                "(NumberOfResolutions 2)\n" +
                "(MaximumNumberOfIterations 250)\n" +
                "(NumberOfSpatialSamples 2048)\n" +
                "(NewSamplesEveryIteration \"true\")\n" +
                "(ImageSampler \"Random\")\n" +
                "(BSplineInterpolationOrder 1)\n" +
                "(FinalBSplineInterpolationOrder 3)\n" +
                "(DefaultPixelValue 0)\n" +
                "(WriteResultImage \"false\")\n" +
                "(ResultImagePixelType \"float\")\n" +
                "(ResultImageFormat \"nii\")\n";

        java.io.FileWriter fw = new java.io.FileWriter(file);
        fw.write(content);
        fw.close();
    }
}