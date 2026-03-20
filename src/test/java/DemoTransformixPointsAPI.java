import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import ch.epfl.biop.wrappers.elastix.RegParamAffine_Fast;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import org.apposed.appose.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the Python script {@code demo_transformix_points.py} via Appose
 * to explore the itk-elastix point transformation API.
 *
 * <p>First registers two test images to produce a TransformParameters file,
 * then runs the exploration script against it.</p>
 */
public class DemoTransformixPointsAPI {

    public static void main(String... args) throws Exception {

        // --- Step 1: Register to get a transform file ---
        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        System.out.println("=== Step 1: Registration to produce a TransformParameters file ===");
        RegisterHelper rh = new RegisterHelper();
        rh.setFixedImage(fixedImage.getAbsolutePath());
        rh.setMovingImage(movingImage.getAbsolutePath());
        rh.verbose();
        rh.addTransform(new RegParamAffine_Fast());
        rh.align(new ApposeElastixTask());

        String transformFile = rh.getFinalTransformFile().replace("\\", "/");
        System.out.println("Transform file: " + transformFile);

        // --- Step 2: Load the Python script from resources ---
        String script;
        try (InputStream is = DemoTransformixPointsAPI.class.getResourceAsStream("/demo_transformix_points.py")) {
            if (is == null) {
                System.err.println("Could not find demo_transformix_points.py in resources.");
                return;
            }
            script = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        }

        // --- Step 3: Run via Appose, injecting the transform file path ---
        System.out.println();
        System.out.println("=== Step 2: Running demo_transformix_points.py via Appose ===");

        Service python = ApposeElastixTask.getElastixApposeService();

        // Inject TRANSFORM_FILE as a variable before running the script
        String preamble = "TRANSFORM_FILE = " + quote(transformFile) + "\n";
        // Replace the placeholder in the script
        script = script.replace(
                "TRANSFORM_FILE = r\"PUT_YOUR_TRANSFORM_PARAMETERS_FILE_HERE\"",
                "TRANSFORM_FILE = " + quote(transformFile)
        );

        final Map<String, Object> inputs = new HashMap<>();
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

    private static String quote(String path) {
        return "r\"" + path + "\"";
    }
}