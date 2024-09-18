package ch.epfl.biop.wrappers.stardist.ij2commands;


import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

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
