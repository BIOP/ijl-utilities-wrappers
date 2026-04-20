package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.DefaultZarrConversionTask;
import ch.epfl.biop.wrappers.cellpose.ZarrConversionTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Convert to Zarr (for Distributed Cellpose)...")
public class ConvertToZarr implements Command {

    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/opt/conda/envs/cellpose";
        } else if (IJ.isWindows()) {
            default_conda_env_path = "C:/Users/username/.conda/envs/cellpose";
        } else {
            default_conda_env_path = "/Users/username/.conda/envs/cellpose";
        }
    }

    static String default_conda_env_path;

    @Parameter
    LogService ls;

    @Parameter(required = false, label = "Input image (open image, highest priority)")
    ImagePlus imp;

    @Parameter(required = false, label = "Input file (IMS, OME-TIFF, …; used when no image is open)")
    File input_file;

    @Parameter(label = "Output folder (a .ome.zarr with the input basename is created here)", style = "directory")
    File output_folder;

    @Parameter(visibility = ItemVisibility.MESSAGE)
    String env_section = "── Environment ─────────────────────────────";

    @Parameter(label = "Environment path", style = "directory", description = "Path to the environment. For Pixi, select either the project root containing pyproject.toml and .pixi, or a specific .pixi/envs/<name> directory.")
    File env_path = new File(default_conda_env_path);

    @Parameter(label = "Environment type", choices = {"conda", "venv", "pixi"}, description = "For Pixi, the wrapper accepts a project root, the .pixi/envs directory, or a specific .pixi/envs/<name> directory.")
    String env_type = "conda";

    @Parameter(visibility = ItemVisibility.MESSAGE)
    String zarr_section = "── Zarr settings ─────────────────────────";

    @Parameter(label = "Chunk sizes Z,Y,X (voxels)", description = "Use 'auto' for Cellpose optimization.")
    String chunks = "auto";

    @Parameter(label = "Pyramid levels", description = "Use 'auto' to match input file structure.")
    String n_levels = "auto";

    @Parameter(visibility = ItemVisibility.MESSAGE)
    String pixel_sizes_note = "── Pixel sizes (leave blank to read from file metadata) ─";

    @Parameter(label = "Pixel size X (µm)", required = false)
    String pixel_size_x_um;

    @Parameter(label = "Pixel size Y (µm)", required = false)
    String pixel_size_y_um;

    @Parameter(label = "Pixel size Z (µm)", required = false)
    String pixel_size_z_um;

    @Override
    public void run() {
        if (env_path == null || !env_path.exists()) {
            ls.error("environment path does not exist: " + env_path);
            return;
        }

        String inputPath;
        String baseName;
        File tempTif = null;
        double pxX = parseUm(pixel_size_x_um);
        double pxY = parseUm(pixel_size_y_um);
        double pxZ = parseUm(pixel_size_z_um);

        if (imp != null) {
            try {
                tempTif = Files.createTempFile("zarr_input_", ".tif").toFile();
                FileSaver fs = new FileSaver(imp);
                fs.saveAsTiff(tempTif.getAbsolutePath());
                inputPath = tempTif.getAbsolutePath();
                baseName = stripExtension(imp.getTitle());

                // Extract calibration unless user provided explicit overrides
                Calibration cal = imp.getCalibration();
                double factor = unitToMicronFactor(cal.getUnit());
                if (pxX <= 0) pxX = cal.pixelWidth  * factor;
                if (pxY <= 0) pxY = cal.pixelHeight * factor;
                if (pxZ <= 0) pxZ = cal.pixelDepth  * factor;
            } catch (IOException e) {
                ls.error("Could not save ImagePlus to temp TIFF: " + e.getMessage());
                return;
            }
        } else if (input_file != null && input_file.exists()) {
            inputPath = input_file.getAbsolutePath();
            baseName = stripExtension(input_file.getName());
        } else {
            ls.error("Provide either an open image or an existing input file (OME-TIFF, IMS, …).");
            return;
        }

        File outputZarr = new File(output_folder, baseName + ".ome.zarr");

        ZarrConversionTaskSettings settings = new ZarrConversionTaskSettings();
        settings.setEnvPath(env_path.getAbsolutePath())
                .setEnvType(env_type)
                .setInputPath(inputPath)
                .setOutputZarrPath(outputZarr.getAbsolutePath())
                .setChunks(chunks)
                .setNLevels(n_levels);

        if (pxX > 0) settings.setPixelSizeXUm(pxX);
        if (pxY > 0) settings.setPixelSizeYUm(pxY);
        if (pxZ > 0) settings.setPixelSizeZUm(pxZ);

        DefaultZarrConversionTask task = new DefaultZarrConversionTask();
        task.setSettings(settings);
        try {
            task.run();
            ls.info("OME-Zarr written to: " + outputZarr.getAbsolutePath());
        } catch (Exception e) {
            ls.error("OME-Zarr conversion failed: " + e.getMessage());
        } finally {
            if (tempTif != null) tempTif.delete();
        }
    }

    /** Strips the file extension from a name, e.g. "image.tif" → "image". */
    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static double parseUm(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Returns a factor to convert the given unit to µm. */
    static double unitToMicronFactor(String unit) {
        if (unit == null || unit.isEmpty()) return 1.0;
        switch (unit.toLowerCase()) {
            case "nm":                        return 0.001;
            case "mm":                        return 1000.0;
            case "cm":                        return 10000.0;
            case "µm": case "um": case "micron": return 1.0;
            default:                          return 1.0;
        }
    }
}
