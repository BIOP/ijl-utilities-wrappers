import ch.epfl.biop.wrappers.elastix.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Runs both DefaultElastixTask (CLI) and ApposeElastixTask (itk-elastix) on the
 * same inputs and compares the output TransformParameters files.
 *
 * <p>Requires: elastix CLI configured + test images in src/test/resources/.</p>
 */
public class DemoCompareElastixTasks {

    public static void main(String... args) throws Exception {

        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // --- Run with DefaultElastixTask (CLI) ---
        System.out.println("========== DefaultElastixTask (CLI elastix) ==========");
        RegisterHelper rhCli = new RegisterHelper();
        rhCli.setFixedImage(fixedImage.getAbsolutePath());
        rhCli.setMovingImage(movingImage.getAbsolutePath());
        rhCli.verbose();
        rhCli.addTransform(new RegParamRigid_Default());
        rhCli.addTransform(new RegParamAffine_Fast());
        rhCli.align(new DefaultElastixTask());

        System.out.println("CLI output dir: " + rhCli.outputDir.get());

        // --- Run with ApposeElastixTask (itk-elastix) ---
        System.out.println();
        System.out.println("========== ApposeElastixTask (itk-elastix) ==========");
        RegisterHelper rhAppose = new RegisterHelper();
        rhAppose.setFixedImage(fixedImage.getAbsolutePath());
        rhAppose.setMovingImage(movingImage.getAbsolutePath());
        rhAppose.verbose();
        rhAppose.addTransform(new RegParamRigid_Default());
        rhAppose.addTransform(new RegParamAffine_Fast());
        rhAppose.align(new ApposeElastixTask());

        System.out.println("Appose output dir: " + rhAppose.outputDir.get());

        // --- Compare output files ---
        System.out.println();
        System.out.println("========== Comparison ==========");

        for (int i = 0; i < 2; i++) {
            File cliFile = new File(rhCli.getTransformFile(i));
            File apposeFile = new File(rhAppose.getTransformFile(i));

            System.out.println();
            System.out.println("--- TransformParameters." + i + ".txt ---");
            System.out.println("CLI exists:    " + cliFile.exists());
            System.out.println("Appose exists: " + apposeFile.exists());

            if (cliFile.exists()) {
                System.out.println();
                System.out.println("CLI content:");
                try {
                    String content = new String(Files.readAllBytes(cliFile.toPath()), StandardCharsets.UTF_8);
                    System.out.println(content);
                } catch (IOException e) {
                    System.err.println("Error reading CLI file: " + e.getMessage());
                }
            }

            if (apposeFile.exists()) {
                System.out.println();
                System.out.println("Appose content:");
                try {
                    String content = new String(Files.readAllBytes(apposeFile.toPath()), StandardCharsets.UTF_8);
                    System.out.println(content);
                } catch (IOException e) {
                    System.err.println("Error reading Appose file: " + e.getMessage());
                }
            }
        }
    }
}
