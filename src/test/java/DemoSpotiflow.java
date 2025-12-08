import ch.epfl.biop.wrappers.spotiflow.ij2commands.Spotiflow;
import ij.IJ;
import ij.plugin.frame.RoiManager;
import net.imagej.ImageJ;

import ij.ImagePlus;

import net.imagej.patcher.LegacyInjector;

import java.util.concurrent.ExecutionException;

public class DemoSpotiflow {

    static {
        LegacyInjector.preinit();
    }

    // Spotiflow conda environment directory should be setup for this demo to work

    public static void main(String... args) throws Exception {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();

        // Close all open images and reset ROI Manager
        RoiManager rm = RoiManager.getRoiManager();
        rm.reset();

        // Open M51 Galaxy sample image
        IJ.run("M51 Galaxy (16-bits)");

        // Run Spotiflow
        runSpotiflow(ij);
        rm.runCommand("Show All");
    }

    public static void runSpotiflow(ImageJ ij) throws ExecutionException, InterruptedException {

        ImagePlus imp = IJ.getImage();
        ij.command().run(Spotiflow.class, false,
                "env_path", "C:\\Users\\chiarutt\\AppData\\Local\\miniforge3\\envs\\spotiflow", //"C:\\Users\\YourUsername\\miniconda3\\envs\\spotiflow", // Update with your conda environment path
                "imp", imp,
                "env_type", "conda",
                "rm", RoiManager.getRoiManager(),
                "additional_flags", "").get();
    }
}