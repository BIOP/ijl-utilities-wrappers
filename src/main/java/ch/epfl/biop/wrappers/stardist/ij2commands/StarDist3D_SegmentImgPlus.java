package ch.epfl.biop.wrappers.stardist.ij2commands;


import ij.ImagePlus;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist3D...")
public class StarDist3D_SegmentImgPlus implements Command {

    @Parameter
    ImagePlus imp;

    @Parameter(style = "directory")
    File model_path;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus stardist_imp;

    Boolean verbose = true;

    @Override
    public void run() {
        StarDist3D_SegmentImgPlus_Advanced stardist3D = new StarDist3D_SegmentImgPlus_Advanced();
        stardist3D.imp = imp;
        stardist3D.model_path = model_path;
        stardist3D.run();
        stardist_imp = stardist3D.stardist_imp;
    }
}
