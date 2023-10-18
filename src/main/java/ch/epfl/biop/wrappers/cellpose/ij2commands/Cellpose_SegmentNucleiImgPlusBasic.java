package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ij.IJ;
import ij.ImagePlus;

import net.imagej.ImageJ;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;


@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Segment Nuclei")
public class Cellpose_SegmentNucleiImgPlusBasic implements Command {

    @Parameter
    ImagePlus imp;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    public void run() {

        Cellpose_SegmentNucleiImgPlusAdvanced nucSeg = new Cellpose_SegmentNucleiImgPlusAdvanced();
        nucSeg.imp = imp;
        nucSeg.nuclei_channel = 1;
        nucSeg.diameter = 17;
        nucSeg.cellprob_threshold = 0.0;
        nucSeg.flow_threshold = 0.4;
        nucSeg.dimensionMode = "3D";
        nucSeg.run();

        cellpose_imp = nucSeg.cellpose_imp;
    }


    public static void main(final String... args) {

        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ImagePlus imp = IJ.openImage("https://imagej.net/images/blobs.gif");
        imp.show();
        IJ.run(imp, "Invert LUT", "");
        // will run on the current image
        ij.command().run(Cellpose_SegmentNucleiImgPlusBasic.class, true);

    }


}