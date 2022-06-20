package ch.epfl.biop.wrappers.stardist.ij2commands;


import ch.epfl.biop.wrappers.stardist.DefaultStardistTask;
import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist3D... (from file)")
public class StarDist3D_SegmentImgPath implements Command {

    @Parameter
    File image_path;

    @Parameter(style = "directory")
    File model_path;

    @Parameter(style = "directory")
    File output_path;

    @Override
    public void run() {

        StarDist3D_SegmentImgPath_Advanced stardist3d = new StarDist3D_SegmentImgPath_Advanced();

        stardist3d.image_path = image_path;
        stardist3d.model_path = model_path;
        stardist3d.output_path = output_path;

        stardist3d.run();

    }
}
