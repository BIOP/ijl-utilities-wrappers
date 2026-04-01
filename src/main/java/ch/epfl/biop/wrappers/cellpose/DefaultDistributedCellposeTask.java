package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.ExecutePythonInConda;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class DefaultDistributedCellposeTask extends DistributedCellposeTask {

    static final String SCRIPT_NAME = "distributed_cellpose_run.py";

    /**
     * Copies the bundled Python script from the JAR to the conda environment
     * directory, always overwriting any previously deployed version so that
     * updates bundled in a new JAR are picked up automatically.
     */
    static boolean ensureScriptIsCopied(String envPath) {
        File dest = new File(envPath, SCRIPT_NAME);

        try {
            InputStream is = DefaultDistributedCellposeTask.class.getResourceAsStream("/" + SCRIPT_NAME);
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
        String envPath = settings.envPath;
        String envType = settings.envType;

        if (!ensureScriptIsCopied(envPath)) {
            throw new RuntimeException(
                "The script " + SCRIPT_NAME + " could not be copied to " + envPath +
                ". Please copy it manually.");
        }

        String scriptPath = new File(envPath, SCRIPT_NAME).getAbsolutePath();

        ArrayList<String> arguments = new ArrayList<>();
        arguments.add(scriptPath);

        if (settings.zarrInputPath != null && !settings.zarrInputPath.trim().isEmpty()) {
            arguments.add("--zarr_input");
            arguments.add(settings.zarrInputPath);
        } else if (settings.tiffInputFolderPath != null && !settings.tiffInputFolderPath.trim().isEmpty()) {
            arguments.add("--tiff_input_folder");
            arguments.add(settings.tiffInputFolderPath);
        } else {
            throw new IllegalArgumentException("Either zarrInputPath or tiffInputFolderPath must be set.");
        }

        arguments.add("--output_tiff");
        arguments.add(settings.outputTiffPath);

        arguments.add("--pretrained_model");
        if (settings.pretrainedModel != null && !settings.pretrainedModel.trim().isEmpty()) {
            arguments.add(settings.pretrainedModel.trim());
        } else {
            arguments.add(settings.modelType);
        }

        // Diameter: µm takes priority over pixels
        if (settings.diameterUm > 0) {
            arguments.add("--diameter_um");
            arguments.add(String.valueOf(settings.diameterUm));
        } else {
            arguments.add("--diameter");
            arguments.add(String.valueOf(settings.diameter));
        }

        if (settings.pixelSizeXyUm > 0) {
            arguments.add("--pixel_size_xy_um");
            arguments.add(String.valueOf(settings.pixelSizeXyUm));
        }

        if (settings.pixelSizeZUm > 0) {
            arguments.add("--pixel_size_z_um");
            arguments.add(String.valueOf(settings.pixelSizeZUm));
        }

        arguments.add("--chan");
        arguments.add(String.valueOf(settings.ch1));

        arguments.add("--chan2");
        arguments.add(String.valueOf(settings.ch2));

        arguments.add("--channel_axis");
        arguments.add(String.valueOf(settings.channelAxis));

        arguments.add("--blocksize");
        // Use 'x' as dimension separator: commas are stripped by ExecutePythonInConda
        // when it builds the shell command string (ArrayList.toString().replace(",","")).
        arguments.add(settings.blocksize.replace(",", "x"));

        if (settings.autoCluster) arguments.add("--auto_cluster");

        arguments.add("--n_workers");
        arguments.add(String.valueOf(settings.nWorkers));

        arguments.add("--ncpus");
        arguments.add(String.valueOf(settings.nCpus));

        arguments.add("--memory_per_worker");
        arguments.add(settings.memoryPerWorker);

        if (settings.useGpu)  arguments.add("--use_gpu");
        if (settings.do3D)    arguments.add("--do_3D");

        if (settings.anisotropy != 1.0f) {
            arguments.add("--anisotropy");
            arguments.add(String.valueOf(settings.anisotropy));
        }

        if (!settings.additionalFlags.trim().isEmpty()) {
            for (String flag : settings.additionalFlags.split(",")) {
                if (!flag.trim().isEmpty()) {
                    arguments.add(flag.trim());
                }
            }
        }

        ExecutePythonInConda.execute(envPath, envType, true, arguments, null);
    }
}
