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

    public static final String nuclei_model = "nuclei";
    public static final String cyto_model = "cyto";
    public static final String cyto2_model = "cyto2";
    public static final String cyto2_omni_model = "cyto2_omni";
    public static final String bact_omni_model = "bact_omni";

    @Parameter
    ImagePlus imp;

    // value defined from https://cellpose.readthedocs.io/en/latest/api.html
    @Parameter(label = "Diameter (default 17 for nuclei, 30 for cyto,0 for automatic detection)")
    int diameter = 30;

    @Parameter(label = "cellprob_threshold / mask_threshold (v0.6 / v0.7)")
    double cellprob_threshold = 0.0;

    @Parameter(label = "flow_threshold (default 0.4)")
    double flow_threshold = 0.4;

    @Parameter(label = "Anisotropy between xy and z (1 means none)")
    double anisotropy = 1.0;

    @Parameter(label = "Diameter threshold (default 12)")
    double diam_threshold = 12.0;

    @Parameter(choices = {"nuclei",
            "cyto",
            "cyto2",
            "cyto2_omni",
            "bact_omni",
    }, callback = "modelchanged")
    String model;

    @Parameter(label = "nuclei_channel (set to 0 if not necessary)")
    int nuclei_channel;

    @Parameter(label = "cyto_channel (set to 0 if not necessary)")
    int cyto_channel;

    @Parameter(choices = {"2D", "3D"})
    String dimensionMode;

    @Parameter(label = "stitch_threshold (between 0 and 1, default -1)")
    double stitch_threshold = -1;

    @Parameter(label = "use omnipose mask reconstruction features")
    boolean omni;

    @Parameter(label = "use DBSCAN clustering")
    boolean cluster;

    @Parameter(required = false, label = "add more parameters")
    String additional_flags = "";

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus cellpose_imp;

    Boolean verbose = true;

    // propose some default value when a model is selected
    public void modelchanged() {

        if (model.equals(nuclei_model)) {
            nuclei_channel = 1;
            cyto_channel = -1;
        } else if ((model.equals(bact_omni_model))) {
            cyto_channel = 1;
            nuclei_channel = -1;
        } else if ((model.equals(cyto_model)) || (model.equals(cyto2_model)) || (model.equals(cyto2_omni_model))) {
            cyto_channel = 1;
            nuclei_channel = 2;
        }
    }

    @Override
    public void run() {
        Cellpose_SegmentImgPlusOwnModelAdvanced cellpose = new Cellpose_SegmentImgPlusOwnModelAdvanced();
        cellpose.imp = imp;
        cellpose.diameter = diameter;
        cellpose.cellprob_threshold = cellprob_threshold;
        cellpose.flow_threshold = flow_threshold;
        cellpose.anisotropy = anisotropy;
        cellpose.diam_threshold = diam_threshold;
        cellpose.model_path = new File("cellpose");
        cellpose.model = model;
        cellpose.nuclei_channel = nuclei_channel;
        cellpose.cyto_channel = cyto_channel;
        cellpose.dimensionMode = dimensionMode;
        cellpose.stitch_threshold = stitch_threshold;
        cellpose.omni = omni;
        cellpose.cluster = cluster;
        cellpose.additional_flags = additional_flags;

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