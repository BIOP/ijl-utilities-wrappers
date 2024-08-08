package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import net.imagej.ImageJ;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose/Omnipose> Cellpose ...")
public class Cellpose implements Command {
    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/home/biop/conda/envs/cellpose"; // to ease setting on biop-desktop    }
        } else if (IJ.isWindows()) {
            default_conda_env_path = "C:/Users/username/.conda/envs/cellpose";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/cellpose";
        }
    }

    static String default_conda_env_path;

    @Parameter
    ImagePlus imp;

    @Parameter(label = "conda environment path" ,style="directory")
    File envPath = new File(default_conda_env_path);

    @Parameter(label= "virtual environment type", choices= {"conda", "venv"})
    String envType = "conda";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message = "You can use the pretrained model, specify the model name below";
    @Parameter(label = "--pretrained_model" )
    String model = "cyto2" ;

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message0 ="You can access the list of models by clicking on the button below.";

    @Parameter( label="List of cellpose models", callback="openModelsPage")
    private Button openModelsPage;

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message1 = "OR To use your own model, specify the path below AND leave --pretrained_model empty";
    @Parameter(required = false, label = "model_path")
    File model_path = new File("path/to/own_cellpose_model");

    // value defined from https://omnipose.readthedocs.io/en/latest/api.html
    @Parameter(label = "--diameter")
    int diameter = 30;

    @Parameter(label = "--chan")
    int ch1 = 0;

    @Parameter(label = "--chan2")
    int ch2 = -1;

    @Parameter (visibility=ItemVisibility.MESSAGE )
    String message2 = "You can add more flags to the command line by adding them here. For example: --use_gpu, --do_3D";

    @Parameter(required = false, label = "To add more parameters (use comma separated list of flags")
    String additional_flags = "--use_gpu, --do_3D";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message3 ="You can access the full list of parameters by clicking on the button below.";

    @Parameter( label="List of all parameters", callback="openCliPage")
    private Button CliPageButton;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    // necessary to open the cli page
    @Parameter
    PlatformService ps;

    Boolean verbose = true;

    private void openModelsPage() {

        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/latest/models.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void openCliPage() {

        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/latest/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        // Prepare cellPose settings
        CellposeTaskSettings settings = new CellposeTaskSettings();
        // and a cellpose task
        DefaultCellposeTask cellposeTask = new DefaultCellposeTask();

        Calibration cal = imp.getCalibration();

        // We'll ave the current time-point of the imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        // create tempdir
        File cellposeTempDir = new File(tempDir, "cellposeTemp");
        cellposeTempDir.mkdir();

        // when plugin crashes, image file can pile up in the folder, so we make sure to clear everything
        File[] contents = cellposeTempDir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                f.delete();
            }
        }

        // Add it to the settings
        settings.setEnvPath(envPath.toString());
        settings.setEnvType(envType);
        settings.setDatasetDir(cellposeTempDir.toString());

        if ( model==null || model.trim().equals("") ){
            System.out.println("Using custom model");
            model = model_path.toString();
        }
        settings.setModel(model);
        settings.setDiameter(diameter);
        settings.setChannel1(ch1);
        if (ch2 > -1 ) settings.setChannel2(ch2);
        settings.setAdditionalFlags(additional_flags);

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
                ImagePlus t_imp = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_idx, t_idx);
                // and save the current t_imp into the cellposeTempDir
                File t_imp_path = new File(cellposeTempDir, imp.getShortTitle() + "-t" + t_idx + ".tif");
                FileSaver fs = new FileSaver(t_imp);
                fs.saveAsTiff(t_imp_path.toString());
                if (verbose) System.out.println(t_imp_path.toString());
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
                // make sure to make a 16-bit imp
                // (issue with time-lapse, first frame have less than 254 objects and latest have more)
                if (cellpose_t_imp.getBitDepth() != 32 ) {
                    if (cellpose_t_imp.getNSlices() > 1) {
                        cellpose_t_imp.getStack().setBitDepth(32);
                    } else {
                        cellpose_t_imp.setProcessor(cellpose_t_imp.getProcessor().convertToFloat());
                    }
                }

                imps.add(cellpose_t_imp.duplicate());
            }
            // Convert the ArrayList to an imp
            // https://stackoverflow.com/questions/9572795/convert-list-to-array-in-java
            ImagePlus[] impsArray = imps.toArray(new ImagePlus[0]);
            cellpose_imp = Concatenator.run(impsArray);
            cellpose_imp.setCalibration(cal);
            cellpose_imp.setTitle(imp.getShortTitle() + "-cellpose");

            //add a LUT
            IJ.run(cellpose_imp, "3-3-2 RGB", "");

            // Delete the created files and folder
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                t_imp_paths.get(t_idx - 1).delete();
                cellpose_masks_paths.get(t_idx - 1).delete();
                cellpose_outlines_paths.get(t_idx - 1).delete();
            }
            cellposeTempDir.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    public static void main(final String... args) {

        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        // will run on the current image
        ij.command().run(Cellpose.class, true);

    }
}