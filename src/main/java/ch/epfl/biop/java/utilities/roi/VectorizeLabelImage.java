package ch.epfl.biop.java.utilities.roi;

import ch.epfl.biop.java.utilities.roi.types.IJShapeRoiArray;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.plugin.frame.RoiManager;

/**
 * Commands which vectorizes Label Image
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Vectorize Label Image")
public class VectorizeLabelImage implements Command {

	@Parameter(type = ItemIO.INPUT)
	ImagePlus img_label;
	
	@Parameter(type = ItemIO.OUTPUT)
	ConvertibleRois cr;
	
	@Parameter
	boolean put_in_roi_manager;
	
	@Override
	public void run() {
		IJShapeRoiArray out = ConvertibleRois.labelImageToRoiArrayVectorize(img_label);
		cr = new ConvertibleRois();
		cr.set(out);
		if (put_in_roi_manager) cr.to(RoiManager.class);
	}

}
