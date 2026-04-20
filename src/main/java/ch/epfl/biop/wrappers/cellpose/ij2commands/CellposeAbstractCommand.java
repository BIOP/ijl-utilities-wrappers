package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.HyperStackConverter;
import ij.plugin.frame.Recorder;
import ij.process.StackConverter;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.widget.Button;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

abstract public class CellposeAbstractCommand implements Command {

    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/opt/conda/envs/cellpose"; // to ease setting on biop-desktop    }
        } else if (IJ.isWindows()) {
            default_conda_env_path = "C:/Users/username/.conda/envs/cellpose";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/cellpose";
        }
    }

    static String default_conda_env_path;

    @Parameter
    LogService ls;

    @Parameter
    ImagePlus imp;

    @Parameter(label = "environment path" ,style="directory", description = "Path to the environment. For Pixi, select either the project root containing pyproject.toml and .pixi, or a specific .pixi/envs/<name> directory.")
    File env_path = new File(default_conda_env_path);

    @Parameter(label= "virtual environment type", choices= {"conda", "venv", "pixi"}, description = "Type of virtual environment. For Pixi, the wrapper accepts a project root, the .pixi/envs directory, or a specific .pixi/envs/<name> directory.")
    String env_type = "conda";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message = "You can use the pretrained model, specify the model name below";

    @Parameter(required = false, label = "--pretrained_model", description = "Cellpose model name (e.g., cyto3, nuclei, etc.).")
    String model = getDefaultModelName();

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message0 ="You can access the list of models by clicking on the button below.";

    @Parameter(label="List of cellpose models", callback="openModelsPage", description = "Opens the Cellpose models documentation page.")
    private Button open_models_page_button;

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message1 = "OR To use your own model, specify the path below AND leave --pretrained_model empty";

    @Parameter(required = false, label = "model_path", description = "Full path to a custom trained model.")
    File model_path = new File("path/to/own_cellpose_model");

    // value defined from https://omnipose.readthedocs.io/en/latest/api.html
    @Parameter(label = "--diameter", style="format:#.##", description = "Approximate diameter of the objects in pixels. 0 for auto.")
    double diameter = 30;

    @Parameter(label = "--flow_threshold", description = "Flow error threshold. Typical values: 0.4 (default). Increase for more masks, decrease for fewer.")
    double flow_threshold = 0.4;

    @Parameter(label = "--cellprob_threshold", description = "Cell probability threshold. Typical values: -6 to 6. Default 0. Decrease to get more masks.")
    double cellprob_threshold = 0.0;

    @Parameter(label = "--batch_size", description = "Number of images per batch. For Cellpose 3, batch_size 1 is recommended to avoid tiling bugs.")
    int batch_size = 1;

    @Parameter(label = "--tile", description = "If true, the image is tiled. For images < 2000px, you can try setting this to false to avoid tiling artifacts.")
    boolean use_tile = true;

    @Parameter(label = "--tile_size", description = "Size of the tiles. If 0, Cellpose defaults (usually 224 or 512) are used.")
    int tile_size = 0;

    @Parameter(visibility= ItemVisibility.MESSAGE)
    String message2 = "Add more parameters here. For flags: --use_gpu, --do_3D, --stitch_threshold 0.5";

    @Parameter(required = false, label = "To add more parameters (use comma separated list of flags", description = "Comma-separated list of additional CLI flags supported by Cellpose (e.g., --use_gpu, --do_3D).")
    String additional_flags = "--use_gpu, --do_3D";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message3 ="You can access the full list of parameters by clicking on the button below.";

    @Parameter( label="List of all parameters", callback="openCliPage")
    private Button cli_page_button;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    // necessary to open the cli page
    @Parameter
    PlatformService ps;

    Boolean verbose = true;


    @Override
    public void run() {

        if ((env_path == null) || (!env_path.exists())) {
            ls.error("Error: the cellpose environment path does not exist: "+env_path);
            return;
        }

        // Prepare cellPose settings
        CellposeTaskSettings settings = new CellposeTaskSettings();
        // and a cellpose task
        DefaultCellposeTask cellposeTask = new DefaultCellposeTask();
        Calibration cal = imp.getCalibration();
        File cellposeTempDir = getTempDir();

        // Add it to the settings
        settings.setEnvPath(env_path.toString());
        settings.setEnvType(env_type);
        settings.setDatasetDir(cellposeTempDir.toString());
        settings.setAdditionalFlags(additional_flags);

        String model_used = model;
        if (model==null || model.trim().isEmpty()){
            ls.info("Using cellpose custom model: "+model_path);
            model_used = model_path.toString();
        }

        settings.setModel(model_used);
        settings.setDiameter(diameter);
        settings.setFlowThreshold(flow_threshold);
        settings.setCellprobThreshold(cellprob_threshold);
        settings.setBatchSize(batch_size);
        settings.setUseTile(use_tile);
        settings.setTileSize(tile_size);

        // Call setSettings - will vary depending on using cellpose 3 or cellpose sam
        setSettings(settings);

        // settings are done , so we can now process the imp with cellpose
        cellposeTask.setSettings(settings);
        try {
            // can't process time-lapse directly so, we'll save one time-point after another
            int impFrames = imp.getNFrames();

            // we'll use list to store paths of saved input, output masks and outlines
            List<File> t_imp_paths = new ArrayList<>();
            List<File> cellpose_masks_paths = new ArrayList<>();
            List<File> cellpose_outlines_paths = new ArrayList<>();

            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                // duplicate all channels and all z-slices for a defined time-point
                boolean tmpRecord = Recorder.record;
                Recorder.record = false;
                ImagePlus t_imp = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_idx, t_idx);
                Recorder.record = tmpRecord;
                // and save the current t_imp into the cellposeTempDir
                File t_imp_path = new File(cellposeTempDir, imp.getShortTitle() + "-t" + t_idx + ".tif");
                FileSaver fs = new FileSaver(t_imp);
                fs.saveAsTiff(t_imp_path.toString());
                if (verbose) System.out.println(t_imp_path);
                // add to list of paths to delete at the end of operations
                t_imp_paths.add(t_imp_path);

                // prepare path of the cellpose mask output
                File cellpose_imp_path = new File(cellposeTempDir, imp.getShortTitle() + "-t" + t_idx + "_cp_masks" + ".tif");
                cellpose_masks_paths.add(cellpose_imp_path);
                // cellpose also creates a txt file (probably to be used with a script to import ROI in imagej), we'll delete it too
                // (to generate ROIs from the label image we can use https://github.com/BIOP/ijp-larome)
                File cellpose_outlines_path = new File(cellposeTempDir, imp.getShortTitle() + "-t" + t_idx + "_cp_outlines" + ".txt");
                cellpose_outlines_paths.add(cellpose_outlines_path);
            }

            // RUN CELLPOSE !
            cellposeTask.run();

            // Open all the cellpose_mask and store each imp within an ArrayList
            ArrayList<ImagePlus> imps = new ArrayList<>(impFrames);
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                ImagePlus cellpose_t_imp = IJ.openImage(cellpose_masks_paths.get(t_idx - 1).toString());
                // make sure to make a 32-bit imp
                // (issue with time-lapse, first frame have less than 254 objects and latest have more)
                if (cellpose_t_imp.getBitDepth() != 32 ) {
                    if (cellpose_t_imp.getNSlices() > 1) {
                        new StackConverter(cellpose_t_imp).convertToGray32();
                    } else {
                        cellpose_t_imp.setProcessor(cellpose_t_imp.getProcessor().convertToFloat());
                    }
                }

                imps.add(cellpose_t_imp.duplicate());
            }
            // Convert the ArrayList to an imp
            // https://stackoverflow.com/questions/9572795/convert-list-to-array-in-java
            ImagePlus[] impsArray = imps.toArray(new ImagePlus[0]);
            boolean tmpScriptMode = Recorder.scriptMode();
            Field scriptModeField = Recorder.class.getDeclaredField("scriptMode");
            scriptModeField.setAccessible(true);
            scriptModeField.set(null, false);
            cellpose_imp = Concatenator.run(impsArray);
            if (imp.getNFrames() > 1 || imp.getNSlices() > 1) {
                cellpose_imp = HyperStackConverter.toHyperStack(cellpose_imp, 1, imp.getNSlices(), imp.getNFrames());
            }
            scriptModeField.set(null, tmpScriptMode);
            cellpose_imp.setCalibration(cal);
            cellpose_imp.setTitle(imp.getShortTitle() + "-cellpose");

            //add a LUT
            boolean tmpRecord = Recorder.record;
            Recorder.record = false;
            IJ.run(cellpose_imp, "3-3-2 RGB", "");
            Recorder.record = tmpRecord;

            // Delete the created files and folder
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                t_imp_paths.get(t_idx - 1).delete();
                cellpose_masks_paths.get(t_idx - 1).delete();
                cellpose_outlines_paths.get(t_idx - 1).delete();
            }
            cellposeTempDir.delete();

        } catch (Exception e) {
            System.out.println("An error occured, the temporary Cellpose Directory is located at "+cellposeTempDir.getAbsolutePath());
            e.printStackTrace();
        }

    }

    File getTempDir() {
        // We'll have the current time-point of the imp in a temp folder
        // create tempdir
        File cellposeTempDir = new TempDirectory("cellpose").getPath().toFile();
        System.out.println(cellposeTempDir);
        cellposeTempDir.mkdir();

        // when plugin crashes, image file can pile up in the folder, so we make sure to clear everything
        File[] contents = cellposeTempDir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                f.delete();
            }
        }
        return cellposeTempDir;
    }

    abstract void setSettings(CellposeTaskSettings settings);

    abstract void openModelsPage();

    abstract void openCliPage();

    abstract String getDefaultModelName();

}
