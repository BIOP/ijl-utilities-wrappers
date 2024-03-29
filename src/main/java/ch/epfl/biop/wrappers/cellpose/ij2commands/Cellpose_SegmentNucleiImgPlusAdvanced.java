package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import net.imagej.ImageJ;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Segment Nuclei Adv.")
public class Cellpose_SegmentNucleiImgPlusAdvanced implements Command {

    @Parameter
    ImagePlus imp;

    @Parameter
    int nuclei_channel = 1;

    @Parameter
    int diameter = 17;

    @Parameter(label = "cellproba_threshold / mask_threshold (v0.6 / v0.7)")
    double cellproba_threshold = 0.0;

    @Parameter
    double flow_threshold = 0.4;

    @Parameter(choices = {"2D", "3D"})
    String dimensionMode;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    Boolean verbose = true;

    @Override
    public void run() {

        Cellpose_SegmentImgPlusAdvanced nucleiCellpose = new Cellpose_SegmentImgPlusAdvanced();
        nucleiCellpose.imp = imp;
        nucleiCellpose.diameter = diameter;
        nucleiCellpose.cellproba_threshold = cellproba_threshold;
        nucleiCellpose.flow_threshold = flow_threshold;

        nucleiCellpose.model = "nuclei";
        nucleiCellpose.nuclei_channel = nuclei_channel;
        nucleiCellpose.cyto_channel = -1;

        nucleiCellpose.dimensionMode = dimensionMode;

        nucleiCellpose.run();

        cellpose_imp = nucleiCellpose.cellpose_imp;
    }


    public static void main(final String... args) {

        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ImagePlus imp = IJ.openImage("https://imagej.net/images/blobs.gif");
        imp.show();
        IJ.run(imp, "Invert LUT", "");
        // will run on the current image
        ij.command().run(Cellpose_SegmentNucleiImgPlusAdvanced.class, true);
    }
}