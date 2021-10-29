package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ij.ImagePlus;
import java.io.File;
import net.imagej.ImageJ;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;



@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Cellpose Advanced")
public class Cellpose_SegmentImgPlusAdvanced implements Command {

    @Parameter
    ImagePlus imp;

    // value defined from https://cellpose.readthedocs.io/en/latest/api.html
    @Parameter
    int diameter = 30;

    @Parameter
    double cellproba_threshold = 0.0;

    @Parameter
    double flow_threshold = 0.4;

    @Parameter(choices = {"nuclei", "cyto", "cyto2", "cyto (no nuclei)", "cyto2 (no nuclei)"}, callback = "modelchanged")
    String model;

    @Parameter
    int nuclei_channel;

    @Parameter
    int cyto_channel;

    @Parameter(choices = {"2D", "3D"})
    String dimensionMode;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    // propose some default value when a model is selected
    public void modelchanged() {

        if (model.equals("nuclei")) {
            nuclei_channel = 1;
            cyto_channel = -1;
        } else if (model.equals("cyto") || model.equals("cyto (no nuclei)")) {
            cyto_channel = 1;
            nuclei_channel = 2;
        } else if (model.equals("cyto (no nuclei)") || model.equals("cyto2 (no nuclei)")) {
            cyto_channel = 1;
            nuclei_channel = -1;
        }
    }

    @Override
    public void run() {
        Cellpose_SegmentImgPlusOwnModelAdvanced cellpose = new Cellpose_SegmentImgPlusOwnModelAdvanced();
        cellpose.imp = imp;
        cellpose.diameter = diameter;
        cellpose.cellproba_threshold = cellproba_threshold;
        cellpose.flow_threshold = flow_threshold;
        cellpose.model_path = new File("cellpose");
        cellpose.model = model;
        cellpose.nuclei_channel = nuclei_channel;
        cellpose.cyto_channel = -1;
        cellpose.dimensionMode = dimensionMode;
        cellpose.run();

        cellpose_imp = cellpose.cellpose_imp;
    }


    public static void main(final String... args) {

        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //ImagePlus imp = IJ.openImage("https://zenodo.org/record/4700067/files/DPC_timelapse_05h.tif?download=1");
        //imp.show();
        // will run on the current image
        //ij.command().run(CellposePrefsSet.class, true);
        ij.command().run(Cellpose_SegmentImgPlusAdvanced.class, true);

    }
}