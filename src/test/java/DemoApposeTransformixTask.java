import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import ch.epfl.biop.wrappers.elastix.RegParamAffine_Fast;
import ch.epfl.biop.wrappers.elastix.RegParamRigid_Default;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ch.epfl.biop.wrappers.transformix.ApposeTransformixTask;
import ch.epfl.biop.wrappers.transformix.TransformHelper;

import java.io.File;

/**
 * Tests {@link ApposeTransformixTask} as a drop-in replacement for DefaultTransformixTask.
 *
 * <p>Chains Appose-based registration (ApposeElastixTask) with Appose-based
 * transformation (ApposeTransformixTask) for a full end-to-end test.</p>
 *
 * <p>Run from the project root (needs {@code src/test/resources/blobs*.tif}).</p>
 */
public class DemoApposeTransformixTask {

    public static void main(String... args) throws Exception {

        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // --- Step 1: Register with ApposeElastixTask ---
        System.out.println("=== Step 1: Registration via ApposeElastixTask ===");
        RegisterHelper rh = new RegisterHelper();
        rh.setFixedImage(fixedImage.getAbsolutePath());
        rh.setMovingImage(movingImage.getAbsolutePath());
        rh.verbose();
        rh.addTransform(new RegParamRigid_Default());
        rh.addTransform(new RegParamAffine_Fast());
        rh.align(new ApposeElastixTask());

        String finalTransform = rh.getFinalTransformFile();
        System.out.println("Registration done. Transform: " + finalTransform);

        // --- Step 2: Transform with ApposeTransformixTask ---
        System.out.println();
        System.out.println("=== Step 2: Transformation via ApposeTransformixTask ===");
        TransformHelper th = new TransformHelper();
        th.setTransformFile(rh);
        th.setImage(movingImage.getAbsolutePath());
        th.verbose();

        th.transform(new ApposeTransformixTask());

        // --- Verify output ---
        System.out.println();
        System.out.println("=== Verification ===");
        File resultFile = new File(th.getTransformedImage().to(File.class).toString());
        if (resultFile.exists()) {
            System.out.println("SUCCESS - result.tif exists: " + resultFile.getAbsolutePath());
            System.out.println("File size: " + resultFile.length() + " bytes");
        } else {
            System.err.println("FAILURE - result.tif not found at expected location");
        }
    }
}