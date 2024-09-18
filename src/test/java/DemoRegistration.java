import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ch.epfl.biop.wrappers.elastix.ij2commands.Elastix_Register;
import ch.epfl.biop.wrappers.transformix.ij2commands.Transformix_TransformImgPlus;
import ij.ImagePlus;
import net.imagej.ImageJ;

public class DemoRegistration {

    // Elastix and Transformix executable should be setup for this demo to work

    public static void main(String... args) throws Exception {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();

        new ImagePlus("src/test/resources/blobs.tif").show();
        new ImagePlus("src/test/resources/blobs-rot15deg.tif").show();

        RegisterHelper rh = (RegisterHelper) ij.command().run(Elastix_Register.class, true,
                "fixed_image", "blobs-rot15deg.tif",
                       "moving_image", "blobs.tif",
                       "rigid", true,
                       "fast_affine", false,
                       "affine", true,
                       "spline", false,
                       "spline_grid_spacing", 40
                ).get().getOutput("rh");

        ij.command().run(Transformix_TransformImgPlus.class, true,
         "rh", rh,
                 "img_in", "blobs.tif"
                );

    }
}
