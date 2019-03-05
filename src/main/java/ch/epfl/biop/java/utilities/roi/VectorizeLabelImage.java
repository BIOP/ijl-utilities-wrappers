package ch.epfl.biop.java.utilities.roi;

import java.util.ArrayList;

import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

/**
 * Commands which vectorizes Label Image
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Vectorize Label Image")
public class VectorizeLabelImage implements Command {

	@Parameter(type = ItemIO.INPUT)
	ImagePlus imgLabel;
	
	@Parameter(type = ItemIO.OUTPUT)
	ConvertibleRois cr;
	
	@Parameter
	boolean putInRoiManager;
	
	@Override
	public void run() {
		ArrayList<Roi> out = ConvertibleRois.labelImageToRoiArrayVectorize(imgLabel);
		cr = new ConvertibleRois();
		cr.set(out);
		if (putInRoiManager) cr.to(RoiManager.class);
	}

}
