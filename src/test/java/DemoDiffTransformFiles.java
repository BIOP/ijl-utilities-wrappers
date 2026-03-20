import ch.epfl.biop.wrappers.elastix.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Runs both DefaultElastixTask and ApposeElastixTask with the same single rigid transform,
 * then copies all output files to a comparison folder for easy diffing.
 *
 * Output goes to: src/test/resources/diff-comparison/cli/ and src/test/resources/diff-comparison/appose/
 */
public class DemoDiffTransformFiles {

    public static void main(String... args) throws Exception {

        File fixedImage = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        Path diffDir = Paths.get("src/test/resources/diff-comparison");
        Path cliDir = diffDir.resolve("cli");
        Path apposeDir = diffDir.resolve("appose");
        Files.createDirectories(cliDir);
        Files.createDirectories(apposeDir);

        // --- CLI ---
        System.out.println("=== Running DefaultElastixTask (CLI) ===");
        RegisterHelper rhCli = new RegisterHelper();
        rhCli.setFixedImage(fixedImage.getAbsolutePath());
        rhCli.setMovingImage(movingImage.getAbsolutePath());
        rhCli.addTransform(new RegParamRigid_Default());
        rhCli.addTransform(new RegParamAffine_Fast());
        rhCli.align(new DefaultElastixTask());

        System.out.println("CLI output dir: " + rhCli.outputDir.get());
        copyAllFiles(new File(rhCli.outputDir.get()), cliDir);

        // Also save the input parameter files for reference
        Files.write(cliDir.resolve("input_params_rigid.txt"),
                RegistrationParameters.toString(new RegParamRigid_Default()).getBytes(StandardCharsets.UTF_8));
        Files.write(cliDir.resolve("input_params_affine.txt"),
                RegistrationParameters.toString(new RegParamAffine_Fast()).getBytes(StandardCharsets.UTF_8));

        // --- Appose ---
        System.out.println("=== Running ApposeElastixTask (Appose) ===");
        RegisterHelper rhAppose = new RegisterHelper();
        rhAppose.setFixedImage(fixedImage.getAbsolutePath());
        rhAppose.setMovingImage(movingImage.getAbsolutePath());
        rhAppose.verbose();
        rhAppose.addTransform(new RegParamRigid_Default());
        rhAppose.addTransform(new RegParamAffine_Fast());
        rhAppose.align(new ApposeElastixTask());

        System.out.println("Appose output dir: " + rhAppose.outputDir.get());
        copyAllFiles(new File(rhAppose.outputDir.get()), apposeDir);

        // --- Summary ---
        System.out.println();
        System.out.println("=== Files copied ===");
        System.out.println("CLI files in:    " + cliDir.toAbsolutePath());
        listFiles(cliDir);
        System.out.println("Appose files in: " + apposeDir.toAbsolutePath());
        listFiles(apposeDir);
        System.out.println();
        System.out.println("You can now diff the two folders.");
    }

    private static void copyAllFiles(File sourceDir, Path targetDir) throws Exception {
        File[] files = sourceDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                Files.copy(f.toPath(), targetDir.resolve(f.getName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void listFiles(Path dir) throws Exception {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
        }
    }
}