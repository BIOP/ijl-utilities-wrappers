import ch.epfl.biop.wrappers.elastix.*;
import ch.epfl.biop.wrappers.transformix.DefaultTransformixTask;
import ch.epfl.biop.wrappers.transformix.TransformHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Compatibility test: Appose registration (itk-elastix) → CLI transformation (transformix).
 *
 * <p>This verifies that TransformParameters files produced by ApposeElastixTask
 * can be consumed by the existing CLI-based DefaultTransformixTask.</p>
 *
 * <p>Requires: CLI transformix configured + test images in src/test/resources/.</p>
 */
public class DemoApposeToCLITransformix {

    public static void main(String... args) throws Exception {

        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // --- Step 1: Register with Appose (itk-elastix) ---
        System.out.println("=== Step 1: Registration with ApposeElastixTask ===");
        RegisterHelper rh = new RegisterHelper();
        rh.setFixedImage(fixedImage.getAbsolutePath());
        rh.setMovingImage(movingImage.getAbsolutePath());
        rh.verbose();
        rh.addTransform(new RegParamRigid_Default());
        rh.addTransform(new RegParamAffine_Fast());
        rh.align(new ApposeElastixTask());
        //rh.align(new DefaultElastixTask());

        String finalTransform = rh.getFinalTransformFile();
        System.out.println("Final transform file: " + finalTransform);
        System.out.println("Exists: " + new File(finalTransform).exists());

        // Print the transform file so we can inspect paths
        System.out.println();
        System.out.println("--- TransformParameters.0.txt content ---");
        System.out.println(new String(
                Files.readAllBytes(new File(finalTransform).toPath()),
                StandardCharsets.UTF_8));

        // --- Step 2: Transform with CLI transformix ---
        System.out.println("=== Step 2: Transformation with DefaultTransformixTask (CLI) ===");
        TransformHelper th = new TransformHelper();
        th.setTransformFile(rh);
        th.setImage(movingImage.getAbsolutePath());
        th.verbose();
        th.transform(new DefaultTransformixTask());

        // --- Step 3: Check result ---
        // Note: TransformHelper.getOutput() returns "result.mhd" (outdated),
        // but transform() correctly sets the result to "result.tif" via imageTransformed.
        // Check both to understand what's happening.
        System.out.println();
        System.out.println("=== Verification ===");

        // List all files in output dir to see what transformix actually wrote
        File outputDir = new File(th.getOutput()).getParentFile();
        System.out.println("Output dir contents:");
        File[] files = outputDir.listFiles();
        if (files != null) {
            for (File f : files) {
                System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
            }
        }

        // Check the real result via ConvertibleImage (how IJ2 commands use it)
        ij.ImagePlus result = (ij.ImagePlus) th.getTransformedImage().to(ij.ImagePlus.class);
        if (result != null) {
            System.out.println("SUCCESS - Appose registration -> CLI transformix works!");
            System.out.println("Result image: " + result.getWidth() + "x" + result.getHeight()
                    + ", type=" + result.getBitDepth() + "-bit");
        } else {
            System.err.println("FAILURE - could not load transformed image");
        }
    }
}