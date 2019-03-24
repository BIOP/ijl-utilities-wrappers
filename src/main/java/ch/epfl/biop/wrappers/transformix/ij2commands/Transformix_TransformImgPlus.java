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
		boolean isRGB = false;
		ImagePlus[] channels=null;
		int nTimepointsRGB=0;
		ArrayList<ImageProcessor> ips = new ArrayList<>();
		if (img_in.getType()==ImagePlus.COLOR_RGB) {
			isRGB = true;
			channels = ChannelSplitter.split(img_in);
			nTimepointsRGB = channels[0].getStack().getSize();
			for (int i=0;i<channels[0].getStack().getSize();i++) {
				ips.add(channels[0].getStack().getProcessor(i+1).convertToFloat());
			}
			for (int i=0;i<channels[1].getStack().getSize();i++) {
				ips.add(channels[1].getStack().getProcessor(i+1).convertToFloat());
			}
			for (int i=0;i<channels[2].getStack().getSize();i++) {
				ips.add(channels[2].getStack().getProcessor(i+1).convertToFloat());
			}
		} else {
			for (int i=0;i<img_in.getStack().getSize();i++) {
				ips.add(img_in.getStack().getProcessor(i+1).convertToFloat());
			}
		}
		List<ImageProcessor> tr_imps = ips.parallelStream().map(ip -> {
			ImagePlus imp = new ImagePlus();
			imp.setProcessor(ip);
			imp.setTitle(ip.toString());
			TransformHelper th = new TransformHelper();
			th.setTransformFile(rh);
			th.setImage(imp);
			th.transform();
			ImagePlus imp_out = ((ImagePlus) (th.getTransformedImage().to(ImagePlus.class)));
			return imp_out.getProcessor();
		}).collect(Collectors.toList());

		int newH = tr_imps.get(0).getHeight();
		int newW = tr_imps.get(0).getWidth();


		ImagePlus img_out_temp;

		if (isRGB) {
			ImagePlus red, green, blue;
			int i_IP = 0;
			red = IJ.createImage("Red", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				red.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			green = IJ.createImage("Green", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				green.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			blue = IJ.createImage("Blue", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				blue.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			RGBStackMerge rgbsm = new RGBStackMerge();
			img_out_temp = rgbsm.mergeHyperstacks(new ImagePlus[]{red, green, blue}, false);
			if (channels!=null) {
				assert channels.length==3;
				channels[0].close();
				channels[1].close();
				channels[2].close();
			}
		} else {
			img_out_temp = new ImagePlusFunctions.ImagePlusBuilder().allAs(img_in).type32Bit().height(newH).width(newW).createImagePlus();
			img_out_temp.setSlice(0);
			for (int i = 0; i < img_in.getStack().getSize(); i++) {
				img_out_temp.getStack().setProcessor(tr_imps.get(i), i + 1);
			}
			/* Bug : imageprocessor not updated for one slide */
			img_out_temp.getProcessor().setPixels(tr_imps.get(0).getPixels()); // Dirty fix slice 0 -> keep processor
		}
		if (isRGB) {
			new StackConverter(img_out_temp).convertToRGB();
		}
		img_out_temp.setTitle("Transformed_"+img_in.getTitle());
		img_out = img_out_temp.duplicate(); // only way found to have a correct display for composite images...
		img_out.setTitle(img_out.getTitle().substring(4)); // Removes DUP_ in title
	}
	
}
