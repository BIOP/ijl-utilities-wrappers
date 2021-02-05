package ch.epfl.biop.wrappers.elastix.ij2commands;

import ch.epfl.biop.wrappers.elastix.DefaultElastixTask;
import ch.epfl.biop.wrappers.elastix.*;
import ij.ImagePlus;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Inverse registration")
public class Elastix_InverseTransform implements Command {

    @Parameter
    public ImagePlus image;

    @Parameter(type = ItemIO.INPUT)
    public RegisterHelper rh_toinvert;

    @Parameter(type = ItemIO.OUTPUT)
    public RegisterHelper rh_inverted;

    @Parameter
    public boolean rigid;

    @Parameter
    public boolean fast_affine;

    @Parameter
    public boolean affine;

    @Parameter
    public boolean spline;

    @Override
    public void run() {
        RegisterHelper rh = new RegisterHelper();
        rh.setMovingImage(image);
        rh.setFixedImage(image);

        RegistrationParameters rp = null;
        if (rigid) {
            rp = new RegParamRigid_Default();
        }
        if (fast_affine) {
            rp = new RegParamAffine_Fast();
        }
        if (affine) {
            rp = new RegParamAffine_Default();
        }
        if (spline) {
            rp = new RegParamBSpline_Default();
        }


        rp.Metric = "DisplacementMagnitudePenalty";

        // Adds all the original transformations

        rh.addInitialTransformFromFilePath(rh_toinvert.getTransformFile(rh_toinvert.getNumberOfTransform()-1));
        rh.addTransform(rp);

        try {
            rh.align(new DefaultElastixTask());
            rh.to(RHZipFile.class);
            rh_inverted = rh;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
