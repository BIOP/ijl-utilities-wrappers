package ch.epfl.biop.wrappers.omnipose.ij2commands;

import ch.epfl.biop.wrappers.omnipose.OmniposeTaskSettings;
import ch.epfl.biop.wrappers.omnipose.DefaultOmniposeTask;

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


@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose/Omnipose> Omnipose ...")
public class Omnipose implements Command {
    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/home/biop/conda/envs/omnipose"; // to ease setting on biop-desktop    }
        } else if (IJ.isWindows()) {
            default_conda_env_path = "C:/Users/username/.conda/envs/omnipose";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/omnipose";
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
    String model = "cyto2_omni" ;

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message0 ="You can access the list of models by clicking on the button below.";

    @Parameter( label="List of omnipose models", callback="openModelsPage")
    private Button openModelsPage;

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message1 = "OR To use your own model, specify the path below AND leave --pretrained_model empty";
    @Parameter(required = false, label = "model_path")
    File model_path = new File("path/to/own_omnipose_model");

    // value defined from https://omnipose.readthedocs.io/en/latest/api.html
    @Parameter(label = "--diameter")
    int diameter = 30;

    @Parameter(label = "--chan")
    int ch1 = 0;

    @Parameter(label = "--chan2")
    int ch2 = -1;

    @Parameter (visibility=ItemVisibility.MESSAGE )
    String message2 = "You can add more flags to the command line by adding them here. For example: --omni, --cluster";

    @Parameter(required = false, label = "To add more parameters (use comma separated list of flags)")
    String additional_flags = "--omni, --cluster";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message3 ="You can access the full list of parameters by clicking on the button below.";

    @Parameter( label="List of all parameters", callback="openCliPage")
    private Button CliPageButton;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus omnipose_imp;

    // necessary to open the cli page
    @Parameter
    PlatformService ps;

    Boolean verbose = true;

    private void openModelsPage() {

        try {
            ps.open(new URL("https://omnipose.readthedocs.io/models.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void openCliPage() {

        try {
            ps.open(new URL("https://omnipose.readthedocs.io/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        // Prepare omnipose settings
        OmniposeTaskSettings settings = new OmniposeTaskSettings();
        // and a omnipose task
        DefaultOmniposeTask omniposeTask = new DefaultOmniposeTask();

        Calibration cal = imp.getCalibration();

        // We'll have the current time-point of the imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        // create tempdir
        File omniposeTempDir = new File(tempDir, "omniposeTemp");
        omniposeTempDir.mkdir();

        // when plugin crashes, image file can pile up in the folder, so we make sure to clear everything
        File[] contents = omniposeTempDir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                f.delete();
            }
        }

        // Add it to the settings
        settings.setEnvPath(envPath.toString());
        settings.setEnvType(envType);
        settings.setDatasetDir(omniposeTempDir.toString());

        if ( model==null || model.trim().equals("") ){
            System.out.println("Using custom model");
            model = model_path.toString();
        }
        settings.setModel(model);
        settings.setDiameter(diameter);
        settings.setChannel1(ch1);
        if (ch2 > -1 ) settings.setChannel2(ch2);
        settings.setAdditionalFlags(additional_flags);

        // settings are done , so we can now process the imp with omnipose
        omniposeTask.setSettings(settings);
        try {
            // can't process time-lapse directly so, we'll save one time-point after another
            int impFrames = imp.getNFrames();

            // we'll use list to store paths of saved input, output masks and outlines
            List<File> t_imp_paths = new ArrayList<>();
            List<File> omnipose_masks_paths = new ArrayList<>();
            List<File> omnipose_outlines_paths = new ArrayList<>();

            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                // duplicate all channels and all z-slices for a defined time-point
                ImagePlus t_imp = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_idx, t_idx);
                // and save the current t_imp into the omniposeTempDir
                File t_imp_path = new File(omniposeTempDir, imp.getShortTitle() + "-t" + t_idx + ".tif");
                FileSaver fs = new FileSaver(t_imp);
                fs.saveAsTiff(t_imp_path.toString());
                if (verbose) System.out.println(t_imp_path.toString());
                // add to list of paths to delete at the end of operations
                t_imp_paths.add(t_imp_path);

                // prepare path of the omnipose mask output
                File omnipose_imp_path = new File(omniposeTempDir, imp.getShortTitle() + "-t" + t_idx + "_cp_masks" + ".tif");
                omnipose_masks_paths.add(omnipose_imp_path);
                // omnipose also creates a txt file (probably to be used with a script to import ROI in imagej), we'll delete it too
                // (to generate ROIs from the label image we can use https://github.com/BIOP/ijp-larome)
                File omnipose_outlines_path = new File(omniposeTempDir, imp.getShortTitle() + "-t" + t_idx + "_cp_outlines" + ".txt");
                omnipose_outlines_paths.add(omnipose_outlines_path);
            }

            // RUN omnipose !
            omniposeTask.run();

            // Open all the omnipose_mask and store each imp within an ArrayList
            ArrayList<ImagePlus> imps = new ArrayList<>(impFrames);
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                ImagePlus omnipose_t_imp = IJ.openImage(omnipose_masks_paths.get(t_idx - 1).toString());
                // make sure to make a 16-bit imp
                // (issue with time-lapse, first frame have less than 254 objects and latest have more)
                if (omnipose_t_imp.getBitDepth() != 32 ) {
                    if (omnipose_t_imp.getNSlices() > 1) {
                        omnipose_t_imp.getStack().setBitDepth(32);
                    } else {
                        omnipose_t_imp.setProcessor(omnipose_t_imp.getProcessor().convertToFloat());
                    }
                }

                imps.add(omnipose_t_imp.duplicate());
            }
            // Convert the ArrayList to an imp
            // https://stackoverflow.com/questions/9572795/convert-list-to-array-in-java
            ImagePlus[] impsArray = imps.toArray(new ImagePlus[0]);
            omnipose_imp = Concatenator.run(impsArray);
            omnipose_imp.setCalibration(cal);
            omnipose_imp.setTitle(imp.getShortTitle() + "-omnipose");

            //add a LUT
            IJ.run(omnipose_imp, "3-3-2 RGB", "");

            // Delete the created files and folder
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                t_imp_paths.get(t_idx - 1).delete();
                omnipose_masks_paths.get(t_idx - 1).delete();
                omnipose_outlines_paths.get(t_idx - 1).delete();
            }
            omniposeTempDir.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    public static void main(final String... args) {

        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        // will run on the current image
        ij.command().run(Omnipose.class, true);

    }
}
