package ch.epfl.biop.wrappers.elastix.ij2commands;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ch.epfl.biop.wrappers.transformix.ij2commands.Transformix_TransformImgPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.WaitForUserDialog;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * BIOP, EPFL, Nicolas Chiaruttini, 18th April 2021
 * Simple script for a simple registration test
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Test Elastix")
public class Elastix_Test implements Command {

    @Parameter
    CommandService cs;

    @Override
    public void run() {

        try {

            (new WaitForUserDialog(
                    "The PTBIOP update site should be enabled.\n" +
                            "Elastix and Transformix should be installed and work in a command line interface.")).show();
            (new WaitForUserDialog("Set the location of elastix and transformix executable file")).show();
            IJ.run("Set and Check Wrappers", "");

            // Test images : blobs and rotated blobs
            (new WaitForUserDialog("Click 'OK' to start a registration test, which should take about 10 seconds ...")).show();
            IJ.run("Blobs (25K)");
            IJ.run("Duplicate...", "title=blobs-rot15.gif");
            IJ.run("Rotate... ", "angle=8 grid=1 interpolation=Bilinear");

            // Get transformation
            RegisterHelper rh = (RegisterHelper) cs.run(Elastix_Register.class, true,
                    "moving_image", "blobs.gif",
                    "fixed_image", "blobs-rot15.gif",
                    "rigid", false,
                    "fast_affine", true,
                    "affine", false,
                    "spline", false,
                    "spline_grid_spacing", 40
            ).get().getOutput("rh");

            // Transform image
            ImagePlus transformed_image = (ImagePlus) cs.run(Transformix_TransformImgPlus.class, true,
                    "img_in", "blobs.gif",
                    "rh", rh
            ).get().getOutput("img_out");

            transformed_image.show();

            IJ.run("Tile");
            (new WaitForUserDialog("The test is successful if the last and the next-to.last images look identical.")).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
