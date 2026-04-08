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
import org.scijava.ItemVisibility;
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

    @Parameter(required = false, label = "Input image (open image, highest priority)", description = "Uses the currently open Fiji image. If this is set, the file and folder inputs below are ignored.")
    ImagePlus imp;

    @Parameter(required = false, label = "Input File", description = "A pre-existing Zarr or any regular image file that will be converted to OME-Zarr before segmentation. Ignored if an open image is provided or if Input Folder is set.")
    File input_file_or_folder;

    @Parameter(required = false, label = "Input Folder", style = "directory", description = "A folder of TIFF tiles or a directory-based Zarr store. If set, this takes priority over Input File. Ignored if an open image is provided.")
    File input_folder;

    @Parameter(label = "Environment path", style = "directory", description = "Path to the Python environment used to run Cellpose and conversion scripts. For Pixi, select either the project root or a specific .pixi/envs/<name> directory.")
    File env_path = new File(defaultEnvPath);

    @Parameter(label = "Environment type", choices = {"conda", "venv", "pixi"}, description = "Type of Python environment. This controls how Fiji resolves the interpreter and launches the bundled scripts.")
    String env_type = "conda";

    @Parameter(label = "Model", description = "Built-in Cellpose model name used when Custom pretrained model path is empty, for example cyto3 or nuclei.")
    String model = "cyto3";

    @Parameter(required = false, label = "Custom pretrained model path", description = "Optional path to a custom trained Cellpose model. If set, it overrides the Model field.")
    File pretrained_model;

    @Parameter(label = "Diameter", description = "For open images this uses calibrated units. For path-based inputs this is treated as pixels unless pixel sizes are specified.")
    double diameter = 30.0;

    @Parameter(required = false, visibility = ItemVisibility.INVISIBLE, label = "Pixel size XY (µm)", description = "Optional XY pixel size override in micrometers. Use only when the input metadata is missing or incorrect.")
    String pixel_size_xy_um = "";

    @Parameter(required = false, visibility = ItemVisibility.INVISIBLE, label = "Pixel size Z (µm)", description = "Optional Z pixel size override in micrometers. Use only when the input metadata is missing or incorrect.")
    String pixel_size_z_um = "";

    @Parameter(label = "Primary channel", description = "Primary input channel, using Fiji-style 1-based indexing. Use 1 for the first channel.")
    int ch1 = 1;

    @Parameter(label = "Secondary channel", description = "Optional secondary channel, using Fiji-style 1-based indexing. Set to 0 to disable the secondary channel.")
    int ch2 = 0;

    @Parameter(visibility = ItemVisibility.INVISIBLE, label = "Channel axis", description = "Advanced override for non-standard array layouts. Leave at the default unless the channel dimension is not detected correctly.")
    int channel_axis = -1;

    @Parameter(label = "Output format", choices = {"ome-tiff", "ome-zarr"}, description = "Format used to write the labels. Use OME-Zarr for large datasets and chunked access, or OME-TIFF for broader TIFF-based compatibility.")
    String output_format = "ome-tiff";

    @Parameter(label = "Output resolution", choices = {"level0", "native"}, description = "Resolution of the written labels. level0 upsamples back to full resolution, while native keeps the selected processing level.")
    String output_resolution = "level0";

    @Parameter(required = false, label = "Output name", description = "Optional basename for the result. Leave blank to derive it from the input.")
    String output_name = "";

    @Parameter(required = false, label = "Save Results Directory", style = "directory", description = "Directory used for the final result. For converted non-Zarr inputs, this is also where reusable '<basename>_input.zarr' files are stored when reuse is enabled.")
    File output_directory;

    @Parameter(label = "Blocksize (Z,Y,X) or auto", description = "Processing block size in voxels, for example 64,256,256. If set to auto, Fiji ignores any manual block-size choice and computes it from object size, memory budget, and the selected resolution level.")
    String blocksize = "auto";

    @Parameter(label = "Resolution level", description = "Pyramid level used for segmentation. Use -1 for automatic selection. When -1 is used, Fiji ignores a manual level choice and picks the level that best matches the effective object size.")
    int resolution_level = -1;

    @Parameter(label = "Auto cluster", description = "Automatically chooses the worker count and memory per worker from the available machine resources. When enabled, the Workers and Memory per worker fields below are treated as informational defaults and may be overridden.")
    boolean auto_cluster = true;

    @Parameter(label = "Workers", description = "Number of Dask workers to launch. This is used only when Auto cluster is disabled. When Auto cluster is enabled, this value may be discarded and recalculated.")
    int n_workers = 1;

    @Parameter(label = "CPUs per worker", description = "CPU threads assigned to each worker. This still influences planning when Auto cluster is enabled.")
    int ncpus = 4;

    @Parameter(label = "Memory per worker", description = "Memory limit per worker, for example 8GB. This is used only when Auto cluster is disabled. When Auto cluster is enabled, this value may be discarded and recalculated.")
    String memory_per_worker = "8GB";

    @Parameter(label = "Use GPU", description = "Runs Cellpose inference on the GPU when the selected environment provides GPU support.")
    boolean use_gpu = true;

    @Parameter(label = "3D mode", description = "Enables Cellpose 3D segmentation mode for volumetric data.")
    boolean do_3D = false;

    @Parameter(label = "Open Dask dashboard", description = "Opens the Dask dashboard in a browser when the run starts. Disable this for quieter or headless runs.")
    boolean show_dashboard = true;

    @Parameter(label = "Reuse existing converted input OME-Zarr", description = "If enabled, an existing '<basename>_input.zarr' in the save directory is reused instead of converting the same source file again. This only matters for non-Zarr file inputs.")
    boolean reuse_zarr = true;

    @Parameter(label = "Cell probability threshold", description = "Cellpose cell-probability threshold. Increase it to be stricter, decrease it to accept weaker objects.")
    double cellprob_threshold = 0.0;

    @Parameter(label = "Minimum object size", description = "Removes masks smaller than this size in pixels or voxels, depending on the processing mode.")
    int min_size = 15;

    @Parameter(label = "Flow 3D smoothing", description = "Additional smoothing applied to the 3D flow field before mask reconstruction. Mostly useful for difficult 3D data.")
    double flow3D_smooth = 1.0;

    @Parameter(label = "Cell probability smoothing", description = "Additional smoothing applied to the cell-probability field. Mostly useful for difficult 3D data.")
    double cellprob_smooth = 0.0;

    @Parameter(label = "No resample", description = "Disables Cellpose resampling during evaluation. This can be faster, but may change mask quality and also affects the automatic block-size limits.")
    boolean no_resample = false;

    @Parameter(required = false, label = "Additional CLI flags", description = "Comma-separated list of extra CLI flags forwarded verbatim. Use this for advanced options such as pixel size overrides or channel axis.")
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

        File selectedInput = input_folder != null ? input_folder : input_file_or_folder;
        if (selectedInput == null || !selectedInput.exists()) {
            throw new IllegalArgumentException("Provide either an open image or an input file/folder.");
        }

        context.inputPath = selectedInput;
        context.baseName = stripExtension(selectedInput.getName());
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
