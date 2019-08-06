package ch.epfl.biop;

import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
import ch.epfl.biop.java.utilities.roi.types.ImageJRoisFile;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.frame.RoiManager;
import net.imagej.ImageJ;

import java.io.File;

public class DummyCommand {

	public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        //ImagePlus imp = IJ.openImage("/home/nico/Dropbox/BIOP/2019-02 Laura Ca/Label.tif");
        //    imp = IJ.openImage("/home/nico/Desktop/Label.tif");
        //imp.show();
        //IJ.run(imp, "Vectorize Label Image", "putinroimanager=true");

        //ImagePlus imp = IJ.openImage("C:\\Users\\chiarutt\\Dropbox\\LabelPb.tif");
        //imp.show();



        /*RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
            roiManager = new RoiManager();
        }
        roiManager.reset();*/

        /*ConvertibleRois cr = new ConvertibleRois();
        cr.set(new ImageJRoisFile(new File("C:\\Users\\chiarutt\\Dropbox\\BIOP\\2019-02 Laura Ca\\RoiSetPb.zip")));

        cr.to(RoiManager.class);*/



        /*rm.select(83);
        rm.select(82);*/
    }

}