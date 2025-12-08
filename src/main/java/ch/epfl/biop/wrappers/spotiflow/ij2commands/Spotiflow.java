package ch.epfl.biop.wrappers.spotiflow.ij2commands;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.wrappers.spotiflow.DefaultSpotiflowTask;
import ch.epfl.biop.wrappers.spotiflow.SpotiflowPointsLoader;
import ch.epfl.biop.wrappers.spotiflow.SpotiflowTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Spotiflow>Spotiflow ...")
public class Spotiflow implements  Command {
    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/opt/conda/envs/spotiflow"; // to ease setting on biop-desktop
        }
        } else if (IJ.isWindows()) {
            default_conda_env_path = "D:/conda/conda-envs/spotiflow";//"C:/Users/username/.conda/envs/spotiflow";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/spotiflow";
        }
    }

    static String default_conda_env_path;

    @Parameter
    LogService ls;

    @Parameter
    ImagePlus imp;

    @Parameter(label = "conda environment path" ,style="directory")
    File env_path = new File(default_conda_env_path);

    @Parameter(label= "virtual environment type", choices= {"conda", "venv"})
    String env_type = "conda";

    @Parameter (visibility=ItemVisibility.MESSAGE )
    String message2 = "You can add more flags to the command line by adding them here. For example to process 3D stack: -pm, smfish_3d";

    @Parameter(required = false, label = "To add more parameters (use comma separated list of flags)")
    String additional_flags = "-pm, smfish_3d";

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message3 ="You can access the full list of parameters by clicking on the button below.";

    @Parameter( label="List of all parameters", callback="openCliPage")
    private Button cli_page_button;

    // necessary to open the cli page
    @Parameter
    PlatformService ps;

    @Parameter
    RoiManager rm;

    Boolean verbose = true;

    private void openCliPage() {
        try {
            ps.open(new URL("https://weigertlab.org/spotiflow/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        if ((env_path == null) || (!env_path.exists())) {
            ls.error("Error: the spotiflow environment path does not exist: "+env_path);
            return;
        }
        rm.reset();

        SpotiflowTaskSettings settings = new SpotiflowTaskSettings();
        DefaultSpotiflowTask spotiflowTask = new DefaultSpotiflowTask();

        Calibration cal = imp.getCalibration();

        // We'll have the current time-point of the imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        // create tempdir
        File spotiflowTempDir = getTempDir();
        spotiflowTempDir.mkdir();

        // when plugin crashes, image file can pile up in the folder, so we make sure to clear everything
        File[] contents = spotiflowTempDir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                f.delete();
            }
        }

        // Add it to the settings
        settings.setEnvPath(env_path.toString());
        settings.setEnvType(env_type);
        settings.setDatasetDir(spotiflowTempDir.toString());
        settings.setAdditionalFlags(additional_flags);

        spotiflowTask.setSettings(settings);

        try{
            // can't process time-lapse directly so, we'll save one time-point after another
            int impFrames = imp.getNFrames();

            // we'll use list to store paths of saved input, output masks and outlines
            List<File> t_imp_paths = new ArrayList<>();
            List<File> spotiflow_output_csv_path_list = new ArrayList<>();

            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                // duplicate all channels and all z-slices for a defined time-point
                boolean tmpRecord = Recorder.record;
                Recorder.record = false;
                ImagePlus t_imp = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_idx, t_idx);
                Recorder.record = tmpRecord;
                // and save the current t_imp into the spotiflowTempDir
                File t_imp_path = new File(spotiflowTempDir, imp.getShortTitle() + "-t" + t_idx + ".tif");
                FileSaver fs = new FileSaver(t_imp);
                fs.saveAsTiff(t_imp_path.toString());
                if (verbose) System.out.println(t_imp_path);
                // add to list of paths to delete at the end of operations
                t_imp_paths.add(t_imp_path);

                // prepare the path where spotiflow will save the list of points
                File spotiflow_output_csv_path = new File(spotiflowTempDir, imp.getShortTitle() + "-t" + t_idx + ".csv");
                spotiflow_output_csv_path_list.add(spotiflow_output_csv_path);
            }

            // Run spotiflow
            spotiflowTask.run();

            // Load results csv as roi in the RoiManager
            SpotiflowPointsLoader spl = new SpotiflowPointsLoader(imp);
            spl.loadPointsFromFiles(spotiflow_output_csv_path_list);

            // Delete the created files and folder
            for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
                t_imp_paths.get(t_idx - 1).delete();
                spotiflow_output_csv_path_list.get(t_idx - 1).delete();
            }
            spotiflowTempDir.delete();

        } catch (Exception e) {
            ls.error("Failed to process Spotiflow detection", e);
            e.printStackTrace();
        }
    }

    File getTempDir() {
        // We'll have the current time-point of the imp in a temp folder
        // create tempdir
        File spotiflowTempDir = new TempDirectory("spotiflowTemp").getPath().toFile();
        System.out.println(spotiflowTempDir);
        spotiflowTempDir.mkdir();

        // when plugin crashes, image file can pile up in the folder, so we make sure to clear everything
        File[] contents = spotiflowTempDir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                f.delete();
            }
        }
        return spotiflowTempDir;
    }
}