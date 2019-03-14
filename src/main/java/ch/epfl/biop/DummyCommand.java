package ch.epfl.biop;

import bdv.BigDataViewer;
import ch.epfl.biop.java.utilities.roi.types.IJShapeRoiArray;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.ImageJ;

import net.imglib2.realtransform.RealTransform;
import org.scijava.script.ScriptService;

import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;

public class DummyCommand {

	public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //BiopWrappersCheck.reportAllWrappers();
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(Dimension.class);
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(RealTransform.class);
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(BigDataViewer.class);
        /*ImagePlus imp = new ImagePlus("/home/nico/Dropbox/BIOP/blobs.tif");
        imp.show();
        imp.changes=false;*/
        // /home/nico/Dropbox/BIOP/
        try {
            //ij.get(ScriptService.class).run(new File("C:\\Users\\chiarutt\\Dropbox\\BIOP\\Macro.ijm"), true).get();
            ij.get(ScriptService.class).run(new File("/home/nico/Dropbox/BIOP/Macro.ijm"), true).get();

        } catch (Exception e) {
            e.printStackTrace();
        }

        RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
              roiManager = new RoiManager();
        }

        ArrayList<Roi> rList = new ArrayList<>();
        rList.add(roiManager.getRoi(0));
        IJShapeRoiArray shapeRoiList = new IJShapeRoiArray(rList);
        Roi roi = shapeRoiList.roiscvt.get(0).getRoi();
        if (roi == null ){
            System.out.println("null");

            //roiManager.addRoi(null);
        } else {
            System.out.println("not null"+roi);

            roiManager.addRoi(roi);
        }

        //RoiManager rm = new RoiManager();
        //rm.addRoi(imp.getRoi());
	}

}