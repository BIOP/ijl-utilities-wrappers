package ch.epfl.biop.wrappers.stardist.ij2commands;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.wrappers.stardist.DefaultStardistTask;
import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import org.scijava.ItemIO;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.command.Command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

abstract public class StarDistAbstractCommand implements Command {

    static {
        if (IJ.isLinux()) {
            default_conda_env_path = "/opt/conda/envs/stardist"; // to ease setting on biop-desktop    }
        } else if (IJ.isWindows()) {
            default_conda_env_path = "D:/conda/conda-envs/stardist";//"C:/Users/username/.conda/envs/spotiflow";
        } else if (IJ.isMacOSX()) {
            default_conda_env_path = "/Users/username/.conda/envs/stardist";
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

    @Parameter(style = "directory")
    File model_path;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus stardist_imp;

    Boolean verbose = true;

    @Override
    public void run() {
        if ((env_path == null) || (!env_path.exists())) {
            ls.error("Error: the cellpose environment path does not exist: " + env_path);
            return;
        }

        Calibration cal = imp.getCalibration();

        // Prepare StarDist settings
        StardistTaskSettings settings = new StardistTaskSettings();
        // to handle Advanced
        setSettings(settings);
        // and a StarDist task
        DefaultStardistTask stardistTask = new DefaultStardistTask();
        // create tempdir
        File stardistTempDir = getTempDir();
        stardistTempDir.mkdir();

        // System.out.println( model_path.toString() );
        settings.setEnvPath(env_path.toString());
        settings.setEnvType(env_type);
        settings.setModelPath( model_path.toString() );
        settings.setOutputPath( stardistTempDir.toString() );

        // can't process time-lapse directly so, we'll save one time-point after another
        int impFrames = imp.getNFrames();

        // we'll use list to store paths of saved input, output masks and outlines
        List<File> t_imp_paths = new ArrayList<>();
        List<File> stardist_masks_paths = new ArrayList<>();

        for (int t_idx = 1; t_idx <= impFrames; t_idx++) {

            // duplicate all channels and all z-slices for a defined time-point
            ImagePlus t_imp = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_idx, t_idx);
            // and save the current t_imp into the cellposeTempDir
            File t_imp_path = new File(stardistTempDir, imp.getShortTitle() + "-t" + t_idx + ".tif");
            FileSaver fs = new FileSaver(t_imp);
            fs.saveAsTiff(t_imp_path.toString());
            if (verbose) System.out.println(t_imp_path);
            // add to list of paths to delete at the end of operations
            t_imp_paths.add(t_imp_path);

            // prepare path of the stardist mask output (suffix from stardist-predict3d)
            File stardist_imp_path = new File(stardistTempDir, imp.getShortTitle() + "-t" + t_idx + ".stardist.tif");
            stardist_masks_paths.add(stardist_imp_path);

            settings.setImagePath(t_imp_path.toString());
            stardistTask.setSettings(settings);

            try {
                stardistTask.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Open all the stardist_mask and store each imp within an ArrayList
        ArrayList<ImagePlus> imps = new ArrayList<>(impFrames);
        for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
            ImagePlus stardist_t_imp = IJ.openImage(stardist_masks_paths.get(t_idx - 1).toString());
            imps.add(stardist_t_imp.duplicate());
        }
        // Convert the ArrayList to an imp
        // https://stackoverflow.com/questions/9572795/convert-list-to-array-in-java
        ImagePlus[] impsArray = imps.toArray(new ImagePlus[0]);
        stardist_imp = Concatenator.run(impsArray);
        stardist_imp.setCalibration(cal);
        stardist_imp.setTitle(imp.getShortTitle() + "-stardist");

        // Delete the created files and folder
        for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
            t_imp_paths.get(t_idx - 1).delete();
            stardist_masks_paths.get(t_idx - 1).delete();
        }
        stardistTempDir.delete();

    }


    File getTempDir() {
        // We'll have the current time-point of the imp in a temp folder
        // create tempdir
        File spotiflowTempDir = new TempDirectory("StarDistTemp").getPath().toFile();
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

    abstract void setSettings(StardistTaskSettings settings);
}
