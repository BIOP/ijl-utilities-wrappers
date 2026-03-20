import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import ch.epfl.biop.wrappers.elastix.RegParamAffine_Default;
import ch.epfl.biop.wrappers.elastix.RegParamBSpline_Default;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;

import java.io.File;

/**
 * Minimal reproducible example for the Appose service reuse bug.
 *
 * <p>Runs two consecutive registrations using the same cached Appose service:
 * <ol>
 *   <li>Task 1 — affine registration</li>
 *   <li>Task 2 — BSpline (spline) registration, initialized from the affine result</li>
 * </ol>
 * Each is a separate Appose {@link org.apposed.appose.Service.Task}. The Appose
 * {@link org.apposed.appose.Service} itself is reused (cached in
 * {@link ApposeElastixTask#CACHED_SERVICE}).
 *
 * <p>Run from the project root. Requires {@code src/test/resources/blobs.tif}
 * and {@code src/test/resources/blobs-rot15deg.tif}.
 */
public class DemoApposeElastixTwoConsecutiveTasks {

    public static void main(String... args) throws Exception {
        for (int i = 0; i<40;i++) {
            new Thread(() -> {
                try {
                    runTask();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    static public void runTask() throws Exception {

        File fixedImage  = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        ApposeElastixTask task = new ApposeElastixTask();

        // ---------------------------------------------------------------
        // Task 1 — affine registration
        // ---------------------------------------------------------------
        System.out.println("=== Task 1: Affine registration ===");
        RegisterHelper rh1 = new RegisterHelper();
        rh1.setFixedImage(fixedImage.getAbsolutePath());
        rh1.setMovingImage(movingImage.getAbsolutePath());
        rh1.addTransform(new RegParamAffine_Default());
        rh1.verbose();

        rh1.align(task);

        /*String affineTransformFile = rh1.getFinalTransformFile();
        System.out.println("Affine transform written to: " + affineTransformFile);
        System.out.println("Exists: " + new File(affineTransformFile).exists());*/
    }
}