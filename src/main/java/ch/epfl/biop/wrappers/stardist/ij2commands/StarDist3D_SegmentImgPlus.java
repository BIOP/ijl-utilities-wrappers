package ch.epfl.biop.wrappers.stardist.ij2commands;


import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;
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
