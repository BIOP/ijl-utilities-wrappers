package ch.epfl.biop.wrappers.transformix.ij2commands;

import ch.epfl.biop.fiji.imageplusutils.ImagePlusFunctions;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;
import ij.process.StackConverter;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import ij.IJ;

import ch.epfl.biop.wrappers.transformix.TransformHelper;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Some images are not updated...
// TODO : improve hyperstack creation by looking at https://github.com/imagej/imagej1/blob/a750ce0ed717ce2cccb7a07cbc96b1a2394a68ff/ij/plugin/Scaler.java
// Needs to check this : https://github.com/imagej/imagej1/blob/master/ij/plugin/HyperStackConverter.java

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Transformix>Transform Image")
public class Transformix_TransformImgPlus implements Command  {
	@Parameter
    RegisterHelper rh;
	
	@Parameter
	ImagePlus img_in;
	
	@Parameter(type=ItemIO.OUTPUT)
	ImagePlus img_out;
	
	@Override
	public void run() {

		img_out = ImagePlusFunctions.splitApplyRecompose(
				imp -> {
					TransformHelper th = new TransformHelper();
					th.setTransformFile(rh);
					th.setImage(imp);
					th.transform();
					return ((ImagePlus) (th.getTransformedImage().to(ImagePlus.class)));
				}
				,img_in);
	}
	
}
