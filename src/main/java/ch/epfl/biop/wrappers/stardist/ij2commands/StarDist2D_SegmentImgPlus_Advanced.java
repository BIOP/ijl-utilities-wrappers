package ch.epfl.biop.wrappers.stardist.ij2commands;


import ch.epfl.biop.wrappers.stardist.DefaultStardistTask;
import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist2D... (Advanced) ")
public class StarDist2D_SegmentImgPlus_Advanced implements Command {

    @Parameter
    ImagePlus imp;

    @Parameter(style = "directory")
    File model_path;

    @Parameter
    int x_tiles=-1;

    @Parameter
    int y_tiles=-1;

    @Parameter (style = "format:#.00")
    float min_norm= (float) 3.0;

    @Parameter (style = "format:#.00")
    float max_norm = (float) 99.8;

    @Parameter(style = "format:#.00")
    float prob_thresh = -1 ;

    @Parameter(style = "format:#.00")
    float nms_thresh = -1;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus stardist_imp;

    Boolean verbose = true;

    @Override
    public void run() {
        Calibration cal = imp.getCalibration();

        // Prepare StarDist settings
        StardistTaskSettings settings = new StardistTaskSettings();
        // and a StarDist task
        DefaultStardistTask stardistTask = new DefaultStardistTask();

       // We'll ave the current time-point of the imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        // create tempdir
        File stardistTempDir = new File(tempDir, "StarDistTemp");
        stardistTempDir.mkdir();

        settings.setMode2D();

        // System.out.println( model_path.toString() );
        settings.setModelPath( model_path.toString() );
        settings.setOutputPath( stardistTempDir.toString() );
        if (x_tiles > -1) settings.setXTiles(x_tiles);
        if (y_tiles > -1) settings.setYTiles(y_tiles);

        settings.setPmin (min_norm);
        settings.setPmax(max_norm);
        if (prob_thresh >-1) settings.setProbThresh(prob_thresh);
        if (nms_thresh >-1) settings.setNmsThresh(nms_thresh);

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
            if (verbose) System.out.println(t_imp_path.toString());
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
        stardist_imp.setTitle(imp.getShortTitle() + "-stardist2D");

        // Delete the created files and folder
        for (int t_idx = 1; t_idx <= impFrames; t_idx++) {
            t_imp_paths.get(t_idx - 1).delete();
            stardist_masks_paths.get(t_idx - 1).delete();
        }
       stardistTempDir.delete();
    }
}
