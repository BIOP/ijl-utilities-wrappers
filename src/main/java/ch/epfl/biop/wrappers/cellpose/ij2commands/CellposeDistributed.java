package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.wrappers.cellpose.DefaultDistributedCellposeTask;
import ch.epfl.biop.wrappers.cellpose.DefaultZarrConversionTask;
import ch.epfl.biop.wrappers.cellpose.DistributedCellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.ZarrConversionTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Cellpose Distributed ...")
public class CellposeDistributed implements Command {

    static {
        if (IJ.isLinux()) {
            defaultEnvPath = "/opt/conda/envs/cellpose";
        } else if (IJ.isWindows()) {
            defaultEnvPath = "C:/Users/username/.conda/envs/cellpose";
        } else if (IJ.isMacOSX()) {
            defaultEnvPath = "/Users/username/.conda/envs/cellpose";
        }
    }

    static String defaultEnvPath;

    @Parameter
    LogService ls;

    @Parameter(required = false, label = "Input image (open image, highest priority)")
    ImagePlus imp;

    @Parameter(required = false, label = "Input File or Folder", description = "A pre-existing Zarr, a folder of TIFFs, or any file that will be converted to OME-Zarr before segmentation.")
    File input_file_or_folder;

    @Parameter(label = "Environment path", style = "directory")
    File env_path = new File(defaultEnvPath);

    @Parameter(label = "Environment type", choices = {"conda", "venv"})
    String env_type = "conda";

    @Parameter(label = "Model")
    String model = "cyto3";

    @Parameter(required = false, label = "Custom pretrained model path")
    File pretrained_model;

    @Parameter(label = "Diameter", description = "For open images this uses calibrated units. For path-based inputs this is treated as pixels unless pixel sizes are specified.")
    double diameter = 30.0;

    @Parameter(required = false, label = "Pixel size XY (µm)")
    String pixel_size_xy_um = "";

    @Parameter(required = false, label = "Pixel size Z (µm)")
    String pixel_size_z_um = "";

    @Parameter(label = "Primary channel")
    int ch1 = 1;

    @Parameter(label = "Secondary channel")
    int ch2 = 0;

    @Parameter(label = "Channel axis")
    int channel_axis = -1;

    @Parameter(label = "Output format", choices = {"ome-tiff", "ome-zarr"})
    String output_format = "ome-tiff";

    @Parameter(label = "Output resolution", choices = {"level0", "native"})
    String output_resolution = "level0";

    @Parameter(required = false, label = "Output name", description = "Optional basename for the result. Leave blank to derive it from the input.")
    String output_name = "";

    @Parameter(required = false, label = "Save Results Directory", style = "directory")
    File output_directory;

    @Parameter(label = "Blocksize (Z,Y,X) or auto")
    String blocksize = "auto";

    @Parameter(label = "Resolution level", description = "Use -1 for automatic selection.")
    int resolution_level = -1;

    @Parameter(label = "Auto cluster")
    boolean auto_cluster = true;

    @Parameter(label = "Workers")
    int n_workers = 1;

    @Parameter(label = "CPUs per worker")
    int ncpus = 4;

    @Parameter(label = "Memory per worker")
    String memory_per_worker = "8GB";

    @Parameter(label = "Use GPU")
    boolean use_gpu = true;

    @Parameter(label = "3D mode")
    boolean do_3D = false;

    @Parameter(label = "Open Dask dashboard")
    boolean show_dashboard = true;

    @Parameter(label = "Reuse converted input Zarr")
    boolean reuse_zarr = true;

    @Parameter(label = "Cell probability threshold")
    double cellprob_threshold = 0.0;

    @Parameter(label = "Minimum object size")
    int min_size = 15;

    @Parameter(label = "Flow 3D smoothing")
    double flow3D_smooth = 1.0;

    @Parameter(label = "Cell probability smoothing")
    double cellprob_smooth = 0.0;

    @Parameter(label = "No resample")
    boolean no_resample = false;

    @Parameter(required = false, label = "Additional CLI flags", description = "Comma-separated list of extra CLI flags forwarded verbatim.")
    String additional_flags = "";

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    @Override
    public void run() {
        if (env_path == null || !env_path.exists()) {
            ls.error("Environment path does not exist: " + env_path);
            return;
        }

        try {
            InputContext context = prepareInputContext();
            File workingDirectory = determineWorkingDirectory(context);
            if (workingDirectory == null) {
                ls.error("Could not determine an output directory.");
                return;
            }
            workingDirectory.mkdirs();

            String outputBaseName = (output_name != null && !output_name.trim().isEmpty())
                ? stripExtension(output_name.trim())
                : context.baseName + "_cellpose";
            String outputSuffix = output_format.equals("ome-zarr") ? ".ome.zarr" : ".ome.tif";
            File outputPath = new File(workingDirectory, outputBaseName + outputSuffix);

            String zarrInputPath = null;
            String tiffInputFolderPath = null;
            boolean inputIsZarr = isZarrPath(context.inputPath);
            boolean inputIsTiffFolder = context.inputPath.isDirectory() && !inputIsZarr;

            if (inputIsZarr) {
                zarrInputPath = context.inputPath.getAbsolutePath();
            } else if (inputIsTiffFolder) {
                tiffInputFolderPath = context.inputPath.getAbsolutePath();
            } else {
                File conversionTargetDirectory = (reuse_zarr && output_directory != null && output_directory.exists())
                    ? output_directory
                    : workingDirectory;
                File convertedInputZarr = new File(conversionTargetDirectory, context.baseName + "_input.zarr");
                if (!convertedInputZarr.exists()) {
                    ZarrConversionTaskSettings conversionSettings = new ZarrConversionTaskSettings()
                        .setEnvPath(env_path.getAbsolutePath())
                        .setEnvType(env_type)
                        .setInputPath(context.inputPath.getAbsolutePath())
                        .setOutputZarrPath(convertedInputZarr.getAbsolutePath())
                        .setChunks("auto")
                        .setNLevels("auto");

                    if (context.pixelSizeXyUm > 0) {
                        conversionSettings.setPixelSizeXUm(context.pixelSizeXyUm);
                        conversionSettings.setPixelSizeYUm(context.pixelSizeXyUm);
                    }
                    if (context.pixelSizeZUm > 0) {
                        conversionSettings.setPixelSizeZUm(context.pixelSizeZUm);
                    }

                    DefaultZarrConversionTask conversionTask = new DefaultZarrConversionTask();
                    conversionTask.setSettings(conversionSettings);
                    ls.info("Converting input to OME-Zarr: " + convertedInputZarr.getAbsolutePath());
                    conversionTask.run();
                }
                zarrInputPath = convertedInputZarr.getAbsolutePath();
            }

            int effectiveCh1 = ch1 > 0 ? ch1 - 1 : -1;
            int effectiveCh2 = ch2 > 0 ? ch2 - 1 : -1;

            DistributedCellposeTaskSettings settings = new DistributedCellposeTaskSettings()
                .setEnvPath(env_path.getAbsolutePath())
                .setEnvType(env_type)
                .setOutputPath(outputPath.getAbsolutePath())
                .setOutputFormat(output_format)
                .setOutputResolution(output_resolution)
                .setResolutionLevel(resolution_level)
                .setModelType(model)
                .setDiameter((float) context.diameterPixels)
                .setChannel1(effectiveCh1)
                .setChannel2(effectiveCh2)
                .setChannelAxis(channel_axis)
                .setBlocksize(blocksize)
                .setAutoCluster(auto_cluster)
                .setNWorkers(n_workers)
                .setNCpus(ncpus)
                .setMemoryPerWorker(memory_per_worker)
                .setUseGpu(use_gpu)
                .setDo3D(do_3D)
                .setOpenDaskDashboard(show_dashboard)
                .setAnisotropy((float) context.anisotropy)
                .setCellprobThreshold(cellprob_threshold)
                .setMinSize(min_size)
                .setFlow3DSmooth(flow3D_smooth)
                .setCellprobSmooth(cellprob_smooth)
                .setNoResample(no_resample)
                .setAdditionalFlags(additional_flags);

            if (pretrained_model != null && pretrained_model.exists()) {
                settings.setPretrainedModel(pretrained_model.getAbsolutePath());
            }
            if (context.diameterUm > 0) {
                settings.setDiameterUm((float) context.diameterUm);
            }
            if (context.pixelSizeXyUm > 0) {
                settings.setPixelSizeXyUm(context.pixelSizeXyUm);
            }
            if (context.pixelSizeZUm > 0) {
                settings.setPixelSizeZUm(context.pixelSizeZUm);
            }
            if (zarrInputPath != null) {
                settings.setZarrInputPath(zarrInputPath);
            }
            if (tiffInputFolderPath != null) {
                settings.setTiffInputFolderPath(tiffInputFolderPath);
            }

            DefaultDistributedCellposeTask task = new DefaultDistributedCellposeTask();
            task.setSettings(settings);
            task.run();

            if (output_format.equals("ome-tiff") && outputPath.isFile()) {
                cellpose_imp = IJ.openImage(outputPath.getAbsolutePath());
                if (cellpose_imp != null) {
                    cellpose_imp.setTitle(outputBaseName);
                    if (imp != null) {
                        cellpose_imp.setCalibration(imp.getCalibration());
                    }
                    cellpose_imp.show();
                    ls.info("Cellpose Distributed finished. Result opened: " + outputPath.getAbsolutePath());
                } else {
                    ls.warn("Cellpose finished, but Fiji could not open the OME-TIFF automatically: " + outputPath.getAbsolutePath());
                }
            } else {
                ls.info("Cellpose Distributed finished. Result written to: " + outputPath.getAbsolutePath());
            }
        } catch (Exception exception) {
            ls.error("Cellpose Distributed failed: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private InputContext prepareInputContext() {
        InputContext context = new InputContext();
        context.pixelSizeXyUm = parseOptionalDouble(pixel_size_xy_um);
        context.pixelSizeZUm = parseOptionalDouble(pixel_size_z_um);
        context.diameterPixels = diameter;
        context.diameterUm = -1.0;
        context.anisotropy = 1.0;

        if (imp != null) {
            context.tempDir = new TempDirectory("cellpose_dist").getPath().toFile();
            context.tempDir.mkdir();
            context.baseName = stripExtension(imp.getTitle());
            context.tempInputFile = new File(context.tempDir, context.baseName + ".tif");
            new FileSaver(imp).saveAsTiff(context.tempInputFile.getAbsolutePath());

            Calibration calibration = imp.getCalibration();
            double factor = unitToMicronFactor(calibration.getUnit());
            double calibratedX = calibration.pixelWidth * factor;
            double calibratedZ = calibration.pixelDepth * factor;
            if (context.pixelSizeXyUm <= 0 && calibratedX > 0) {
                context.pixelSizeXyUm = calibratedX;
            }
            if (context.pixelSizeZUm <= 0 && calibratedZ > 0) {
                context.pixelSizeZUm = calibratedZ;
            }
            context.diameterUm = diameter;
            if (context.pixelSizeXyUm > 0) {
                context.diameterPixels = diameter / context.pixelSizeXyUm;
            }
            if (context.pixelSizeXyUm > 0 && context.pixelSizeZUm > 0) {
                context.anisotropy = context.pixelSizeZUm / context.pixelSizeXyUm;
            }
            context.inputPath = context.tempInputFile;
            return context;
        }

        if (input_file_or_folder == null || !input_file_or_folder.exists()) {
            throw new IllegalArgumentException("Provide either an open image or an input file/folder.");
        }

        context.inputPath = input_file_or_folder;
        context.baseName = stripExtension(input_file_or_folder.getName());
        return context;
    }

    private File determineWorkingDirectory(InputContext context) {
        if (output_directory != null && output_directory.exists()) {
            return output_directory;
        }
        if (context.tempDir != null) {
            return context.tempDir;
        }
        return context.inputPath != null ? context.inputPath.getParentFile() : null;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    static boolean isZarrPath(File file) {
        return file != null && (
            file.getName().toLowerCase().endsWith(".zarr") ||
            new File(file, ".zarray").exists() ||
            new File(file, ".zgroup").exists()
        );
    }

    static double parseOptionalDouble(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    static double unitToMicronFactor(String unit) {
        if (unit == null || unit.isEmpty()) return 1.0;
        switch (unit.toLowerCase()) {
            case "nm":
                return 0.001;
            case "mm":
                return 1000.0;
            case "cm":
                return 10000.0;
            case "µm":
            case "um":
            case "micron":
                return 1.0;
            default:
                return 1.0;
        }
    }

    private static class InputContext {
        File inputPath;
        String baseName;
        double diameterPixels;
        double diameterUm;
        double pixelSizeXyUm;
        double pixelSizeZUm;
        double anisotropy;
        File tempDir;
        File tempInputFile;
    }
}
