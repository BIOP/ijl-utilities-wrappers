package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.ExecutePythonInConda;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class DefaultZarrConversionTask extends ZarrConversionTask {

    static final String SCRIPT_NAME = "convert_to_zarr_for_cellpose.py";

    /**
     * Copies the bundled Python script from the JAR to the conda environment
     * directory, always overwriting any previously deployed version so that
     * updates bundled in a new JAR are picked up automatically.
     */
    static boolean ensureScriptIsCopied(String envPath) {
        File dest = new File(envPath, SCRIPT_NAME);

        try {
            InputStream is = DefaultZarrConversionTask.class.getResourceAsStream("/" + SCRIPT_NAME);
            if (is == null) {
                throw new Exception("Cannot find resource \"" + SCRIPT_NAME + "\" in JAR.");
            }
            Path destPath = Paths.get(envPath, SCRIPT_NAME);
            Files.copy(is, destPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied " + SCRIPT_NAME + " to: " + destPath);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not copy script: " + e.getMessage());
            return false;
        }
        return dest.exists();
    }

    @Override
    public void run() throws Exception {
        String envPath  = settings.envPath;
        String envType  = settings.envType;

        if (!ensureScriptIsCopied(envPath)) {
            throw new RuntimeException(
                "The script " + SCRIPT_NAME + " could not be copied to " + envPath +
                ". Please copy it manually.");
        }

        String scriptPath = new File(envPath, SCRIPT_NAME).getAbsolutePath();

        ArrayList<String> arguments = new ArrayList<>();
        arguments.add(scriptPath);
        arguments.add("--input_path");
        arguments.add(settings.inputPath);
        arguments.add("--output_zarr");
        arguments.add(settings.outputZarrPath);
        arguments.add("--chunks");
        // Use 'x' as dimension separator: commas are stripped by ExecutePythonInConda.
        arguments.add(settings.chunks.replace(",", "x"));
        arguments.add("--n_levels");
        arguments.add(String.valueOf(settings.nLevels));

        if (settings.pixelSizeXUm > 0) {
            arguments.add("--pixel_size_x_um");
            arguments.add(String.valueOf(settings.pixelSizeXUm));
        }
        if (settings.pixelSizeYUm > 0) {
            arguments.add("--pixel_size_y_um");
            arguments.add(String.valueOf(settings.pixelSizeYUm));
        }
        if (settings.pixelSizeZUm > 0) {
            arguments.add("--pixel_size_z_um");
            arguments.add(String.valueOf(settings.pixelSizeZUm));
        }

        ExecutePythonInConda.execute(envPath, envType, true, arguments, null);
    }
}
