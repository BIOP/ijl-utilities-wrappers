import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import ch.epfl.biop.wrappers.elastix.RegParamAffine_Fast;
import ch.epfl.biop.wrappers.elastix.RegParamRigid_Default;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;

import java.io.File;

/**
 * Tests {@link ApposeElastixTask} as a drop-in replacement for DefaultElastixTask.
 *
 * <p>Uses the existing Java infrastructure ({@link RegisterHelper}, {@link RegParamRigid_Default}, etc.)
 * exactly as {@code Elastix_Register} does, but swaps the task implementation.</p>
 *
 * <p>Run from the project root (needs {@code src/test/resources/blobs*.tif}).</p>
 */
public class DemoApposeElastixTask {

    public static void main(String... args) throws Exception {

        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // --- Set up RegisterHelper exactly like Elastix_Register does ---
        RegisterHelper rh = new RegisterHelper();
        rh.setFixedImage(fixedImage.getAbsolutePath());
        rh.setMovingImage(movingImage.getAbsolutePath());
        rh.verbose();

        // Add a rigid + fast affine transform chain (common workflow)
        rh.addTransform(new RegParamRigid_Default());
        rh.addTransform(new RegParamAffine_Fast());

        // --- This is the only line that changes: ApposeElastixTask instead of DefaultElastixTask ---
        System.out.println("Running registration via ApposeElastixTask...");
        rh.align(new ApposeElastixTask());

        // --- Verify outputs (same as CLI elastix would produce) ---
        System.out.println();
        System.out.println("=== Verification ===");
        System.out.println("Output dir: " + rh.outputDir.get());

        for (int i = 0; i < rh.getNumberOfTransform(); i++) {
            String tpFile = rh.getTransformFile(i);
            boolean exists = new File(tpFile).exists();
            System.out.println("TransformParameters." + i + ".txt exists: " + exists);
        }

        String finalTf = rh.getFinalTransformFile();
        if (new File(finalTf).exists()) {
            System.out.println("SUCCESS - ApposeElastixTask is a working drop-in replacement!");
        } else {
            System.err.println("FAILURE - final transform file missing: " + finalTf);
        }
    }
}
