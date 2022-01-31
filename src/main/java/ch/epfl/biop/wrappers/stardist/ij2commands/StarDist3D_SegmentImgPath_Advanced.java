package ch.epfl.biop.wrappers.stardist.ij2commands;


import ch.epfl.biop.wrappers.stardist.DefaultStardistTask;
import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import ij.ImagePlus;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist> StarDist3D... (from file - Advanced)")
public class StarDist3D_SegmentImgPath_Advanced implements Command {

    @Parameter
    File image_path;

    @Parameter (style="directory")
    File model_path;

    @Parameter (style="directory")
    File output_path;

    @Parameter
    int n_tiles=-1;

    @Parameter (style="format:#.0")
    float min_norm= (float) 3.0;

    @Parameter (style="format:#.0")
    float max_norm = (float) 99.8;

    @Parameter
    float prob_thresh = -1 ;

    @Parameter
    float nms_thresh = -1;

    Boolean verbose = true;

    @Override
    public void run() {
        // Prepare StarDist settings
        StardistTaskSettings settings = new StardistTaskSettings();
        // and a StarDist task
        DefaultStardistTask stardistTask = new DefaultStardistTask();

        settings.setImagePath( image_path.toString() );
        settings.setModelPath( model_path.toString() );
        settings.setOutputPath( output_path.toString() );
        if (n_tiles >-1) settings.setNTiles(n_tiles);
        settings.setPmin (min_norm);
        settings.setPmax(max_norm);
        if (prob_thresh >-1) settings.setProbThresh(prob_thresh);
        if (nms_thresh >-1) settings.setNmsThresh(nms_thresh);

        stardistTask.setSettings(settings);

        try {
            stardistTask.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
