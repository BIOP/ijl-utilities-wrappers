package ch.epfl.biop;

import ch.epfl.biop.fiji.imageplusutils.ImagePlusFunctions;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImageJ;

public class DummyCommand {

	public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

       /* ImagePlus imp = IJ.createImage("HyperStack", "16-bit composite-mode label", 400, 300, 3, 4, 5);
        imp.show();
        imp.setC(3);
        imp.setDisplayRange(100,250);
        ImagePlus test = new ImagePlusFunctions.ImagePlusBuilder().allAs(imp).type32Bit().height(100).width(200).createImagePlus();
        test.show();


        imp = IJ.createImage("Untitled", "8-bit black", 512, 512, 1);
        IJ.run(imp, "Fire", "");
        imp.setRoi(142,95,153,203);
        IJ.run(imp, "Add...", "value=200");
        imp.show();

        test = new ImagePlusFunctions.ImagePlusBuilder().allAs(imp).type32Bit().height(100).width(200).createImagePlus();
        test.show();*/


    }

}