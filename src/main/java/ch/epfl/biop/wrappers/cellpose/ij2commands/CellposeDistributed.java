package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.wrappers.cellpose.CellposeDistributedTask;
import ch.epfl.biop.wrappers.cellpose.CellposeDistributedTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Cellpose Distributed ...")
public class CellposeDistributed implements Command {

    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/opt/conda/envs/cellpose";
        } else if (IJ.isWindows()) {
            default_conda_env_path = "C:/Users/username/.conda/envs/cellpose";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/cellpose";
        }
    }

    static String default_conda_env_path;

    @Parameter
    LogService ls;

    @Parameter(required = false)
    ImagePlus imp;

    @Parameter(required = false, label = "Input File or Folder", description = "Path to a TIFF file or a folder of TIFFs. If empty, takes the image currently open in Fiji.")
    File input_file_or_folder;

    @Parameter(label = "conda environment path", style = "directory", description = "Path to the conda environment containing cellpose, dask, and zarr.")
    File env_path = new File(default_conda_env_path);

    @Parameter(required = false, label = "--pretrained_model", description = "Cellpose model name (e.g., cyto3, nuclei, cito3, or a path to a custom model if you use additional_flags).")
    String model = "cyto3";

    @Parameter(label = "Diameter", description = "Approximate diameter of the objects in calibrated units (e.g., \u00B5m). Use 0 for auto-detection.")
    double diameter = 30;

    @Parameter(label = "Main channel (Cytoplasm or nuclei)", description = "Channel to be segmented (1-based index, e.g. 1 for ImageJ Channel 1).")
    int ch1 = 1;

    @Parameter(label = "Nuclear channel (Optional)", description = "Optional second channel (e.g., nuclei). Use 0 if not used/not available.")
    int ch2 = 0;

    @Parameter(label = "--flow_threshold", description = "Flow error threshold. Typical values: 0.4 (default). Increase for more masks, decrease for fewer.")
    double flow_threshold = 0.4;

    @Parameter(label = "--cellprob_threshold", description = "Cell probability threshold. Typical values: -6 to 6. Default 0. Decrease to get more masks.")
    double cellprob_threshold = 0.0;

    @Parameter(label = "--stitch_threshold", description = "Stitch threshold for 3D volumes. 0.0 to disable stitching. Typical values: 0.5.")
    double stitch_threshold = 0.0;

    @Parameter(label = "Gaussian blur sigma (XY)", description = "Apply a Gaussian blur before segmentation to reduce noise. 0 to disable.")
    double gauss = 0.0;

    @Parameter(label = "Median filter radius (px)", description = "Apply a median filter to remove hotspots. 0 to disable.")
    int median = 0;

    @Parameter(label = "Global Normalization (Beta)", description = "Normalize the entire volume before tiling. Prevents stitching artifacts in noisy images.")
    boolean global_norm = false;

    @Parameter(label = "--min_size", description = "Minimum size of detected objects in pixels.")
    int min_size = 15;

    @Parameter(label = "Auto Min Intensity Threshold", description = "If checked, automatically calculates background threshold ('auto').")
    boolean auto_min_intensity = true;

    @Parameter(label = "Min Intensity Value", description = "Skip blocks with max intensity below this threshold. Used only if Auto is unchecked.")
    double min_intensity = 0.0;

    @Parameter(required = false, label = "To add more parameters (comma separated)", description = "Comma-separated list of additional CLI flags (e.g., --use_gpu).")
    String additional_flags = "";

    @Parameter(label = "blocksize (Z,Y,X) or 'auto'", description = "Size of processing blocks. 'auto' calculates optimal blocks for large 3D images.")
    String blocksize = "auto";

    @Parameter(label = "Cellpose Internal bsize", description = "Internal Cellpose tile size. Set to 0 to use Cellpose default (224 or 256). Set larger than blocksize to disable internal tiling.")
    int bsize = 0;

    @Parameter(label = "Cellpose Internal tile_overlap", description = "Overlap between internal tiles (0.1 to 0.5). Default is 0.1.")
    double tile_overlap = 0.1;

    @Parameter(label = "Number of workers (0 for auto)", description = "Number of parallel workers. 0 uses half of available CPU cores.")
    int n_workers = 0;

    @Parameter(label = "--batch_size", description = "Batch size for each worker. Default 1 is recommended for stability in 3D.")
    int batch_size = 1;

    @Parameter(label = "Optimize Parallelism", description = "Automatically find the best blocksize and worker count for speed. If unchecked, defaults to 1 worker.")
    boolean optimize_parallel = false;

    @Parameter(label = "--use_gpu", description = "Use GPU acceleration if available.")
    boolean use_gpu = true;

    @Parameter(label = "Show Dask Dashboard", description = "Opens the Dask distributed dashboard in your default web browser.")
    boolean show_dashboard = false;

    @Parameter(required = false, label = "Save Results Directory", style = "directory", description = "Optional: Directory where the segmentation results (.tif and .zarr) will be saved. If empty, a temporary directory is used.")
    File output_directory;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    @Override
    public void run() {
        if ((env_path == null) || (!env_path.exists())) {
            ls.error("Error: the cellpose environment path does not exist: " + env_path);
            return;
        }

        // Optimal calculation for distributed blocksize
        String effectiveBlocksize = blocksize;
        if (blocksize.equalsIgnoreCase("auto")) {
            // We pass "auto" to the CLI, let the Python side decide based on hardware
            effectiveBlocksize = "auto";
            ls.info("Blocksize is 'auto'. Calculating optimal hardware-dependent blocksize...");
        }

        File inputPath;
        File outputPath;
        double diameterInPixels;
        double anisotropy = 1.0;
        String logDirectory;
        String shortTitle;

        if (input_file_or_folder != null && input_file_or_folder.exists()) {
            inputPath = input_file_or_folder;
            diameterInPixels = diameter; // Assumes pixel units when using path-based input
            shortTitle = inputPath.getName();

            if (output_directory != null && output_directory.exists()) {
                if (inputPath.isDirectory()) {
                    outputPath = output_directory;
                } else {
                    outputPath = new File(output_directory, inputPath.getName().replace(".tif", "_cellpose.tif"));
                }
                logDirectory = output_directory.getAbsolutePath();
            } else {
                if (inputPath.isDirectory()) {
                    ls.warn("Input is a folder but no output directory provided. Results will be saved in a temporary folder.");
                    outputPath = new TempDirectory("cellpose_dist_out").getPath().toFile();
                    outputPath.mkdir();
                } else {
                    outputPath = new File(inputPath.getAbsolutePath().replace(".tif", "_cellpose.tif"));
                }
                logDirectory = inputPath.isDirectory() ? outputPath.getAbsolutePath() : inputPath.getParent();
            }
            ls.info("Running Cellpose Distributed on: " + inputPath.getAbsolutePath());
        } else {
            if (imp == null) {
                ls.error("Error: No image open in Fiji and no input path provided.");
                return;
            }
            shortTitle = imp.getShortTitle();
            File tempDir = new TempDirectory("cellpose_dist").getPath().toFile();
            tempDir.mkdir();
            ls.info("Temporary folder for this run: " + tempDir.getAbsolutePath());

            inputPath = new File(tempDir, "input.tif");
            new FileSaver(imp).saveAsTiff(inputPath.getAbsolutePath());

            if (output_directory != null && output_directory.exists()) {
                outputPath = new File(output_directory, imp.getShortTitle() + "_cellpose.tif");
            } else {
                outputPath = new File(tempDir, "output.tif");
            }

            // Calibration-aware diameter
            double pixelWidth = imp.getCalibration().pixelWidth;
            double pixelDepth = imp.getCalibration().pixelDepth;
            diameterInPixels = diameter;
            if (diameter > 0) {
                diameterInPixels = diameter / pixelWidth;
                ls.info("Calibrated diameter: " + diameter + " " + imp.getCalibration().getUnit() + " -> " + String.format("%.2f", diameterInPixels) + " pixels");
            }

            if (imp.getNSlices() > 1) {
                anisotropy = pixelDepth / pixelWidth;
                if (Math.abs(anisotropy - 1.0) > 0.01) {
                    ls.info("Calculated anisotropy (Z/XY): " + String.format("%.3f", anisotropy));
                } else {
                    anisotropy = 1.0;
                }
            }
            logDirectory = output_directory != null ? output_directory.getAbsolutePath() : tempDir.getAbsolutePath();
        }

        // GUI channels are 1-based (1=Channel 1, 0=None).
        // We pass them as-is to the CLI to match Cellpose's internal 1-based channel logic
        // (where 0 means 'none' for the second channel).
        int effectiveCh1 = ch1;
        int effectiveCh2 = ch2 > 0 ? ch2 : -1;

        String intensityString = auto_min_intensity ? "auto" : String.valueOf(min_intensity);

        int effectiveBsize = bsize > 0 ? bsize : -1;

        CellposeDistributedTaskSettings settings = new CellposeDistributedTaskSettings()
                .setEnvPath(env_path.getAbsolutePath())
                .setInputPath(inputPath.getAbsolutePath())
                .setOutputPath(outputPath.getAbsolutePath())
                .setModel(model)
                .setDiameter(diameterInPixels)
                .setChannels(effectiveCh1, effectiveCh2)
                .setBlocksize(effectiveBlocksize)
                .setAnisotropy(anisotropy)
                .setUseGpu(use_gpu)
                .setOptimizeParallel(optimize_parallel)
                .setBatchSize(batch_size)
                .setShowDashboard(show_dashboard)
                .setFlowThreshold(flow_threshold)
                .setCellprobThreshold(cellprob_threshold)
                .setStitchThreshold(stitch_threshold)
                .setGauss(gauss)
                .setMedian(median)
                .setGlobalNorm(global_norm)
                .setBsize(effectiveBsize)
                .setTileOverlap(tile_overlap)
                .setMinSize(min_size)
                .setMinIntensity(intensityString)
                .setNWorkers(n_workers)
                .setLogDirectory(logDirectory)
                .setAdditionalFlags(additional_flags);

        CellposeDistributedTask task = new CellposeDistributedTask();
        task.setSettings(settings);

        try {
            task.run();
            // If output is a folder, do not open as ImagePlus
            if (!inputPath.isDirectory()) {
                cellpose_imp = IJ.openImage(outputPath.getAbsolutePath());
                if (cellpose_imp != null) {
                    cellpose_imp.setTitle(shortTitle + "-cellpose-dist");
                    if (imp != null) {
                        cellpose_imp.setCalibration(imp.getCalibration());
                    }
                    cellpose_imp.show();
                    ls.info("Cellpose Distributed finished. Result opened: " + cellpose_imp.getTitle());
                } else {
                    ls.error("Error: Could not open the output image at " + outputPath.getAbsolutePath());
                }
            } else {
                ls.info("Cellpose Distributed finished in batch mode. Results saved in: " + outputPath.getAbsolutePath());
            }
        } catch (Exception e) {
            ls.error("Cellpose Distributed failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
            ls.error("Cellpose Distributed failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup: delete input file and other temporary artifacts
            try {
                if (inputTif != null && inputTif.exists()) {
                    inputTif.delete();
                }

                // We keep the outputTif because it's the result the user wants to see.
                // If they didn't specify an output_directory, it's in the tempDir.
                // We should only delete the tempDir if it's empty or we are sure it's safe.
                // For now, let's just make sure all other temp files are gone.

                if (tempDir != null && tempDir.exists()) {
                    File[] files = tempDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            // Don't delete the output if it's in the temp dir and we just opened it
                            if (output_directory == null && f.getAbsolutePath().equals(outputTif.getAbsolutePath())) {
                                continue;
                            }
                            // Don't delete log files so the user can debug even after a successful run
                            if (f.getName().endsWith(".log")) {
                                continue;
                            }
                            f.delete();
                        }
                    }
                    // Only delete tempDir if it's now empty
                    tempDir.delete();
                }
            } catch (Exception e) {
                ls.warn("Could not clean up temporary files: " + e.getMessage());
            }
        }
    }
}
