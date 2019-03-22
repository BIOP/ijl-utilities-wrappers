package ch.epfl.biop.wrappers.transformix.ij2commands;

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

// DOESN T WORK for FLUORESCENT CELL SAMPLE, AND I DONT KNOW WHY
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
				ips.add(channels[0].getStack().getProcessor(i+1));
			}
			for (int i=0;i<channels[1].getStack().getSize();i++) {
				ips.add(channels[1].getStack().getProcessor(i+1));
			}
			for (int i=0;i<channels[2].getStack().getSize();i++) {
				ips.add(channels[2].getStack().getProcessor(i+1));
			}
		} else {
			for (int i=0;i<img_in.getStack().getSize();i++) {
				ips.add(img_in.getStack().getProcessor(i+1));
			}
		}

		List<ImageProcessor> tr_imps = ips.parallelStream().map(ip -> { // cannot be parallel this way ?
			ImagePlus imp = new ImagePlus();
			imp.setProcessor(ip);
			imp.setTitle(ip.toString());
			TransformHelper th = new TransformHelper();
			th.setTransformFile(rh);
			th.setImage(imp);
			th.transform();
			ImagePlus imp_out = ((ImagePlus) (th.getTransformedImage().to(ImagePlus.class)));
			imp_out.show();
			return imp_out.getProcessor();
		}).collect(Collectors.toList());

		int newH = tr_imps.get(0).getHeight();
		int newW = tr_imps.get(0).getWidth();

		if (isRGB) {
			ImagePlus red, green, blue;
			int i_IP = 0;
			red = IJ.createImage("Red", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				red.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			//red.updateAndDraw();
			//red.show();
			green = IJ.createImage("Green", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				green.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			//green.updateAndDraw();
			//green.show();
			blue = IJ.createImage("Blue", "8-bit", newW, newH, nTimepointsRGB);
			for (int i=0;i<nTimepointsRGB;i++) {
				blue.getStack().setProcessor(tr_imps.get(i_IP).convertToByte(false), i+1);
				i_IP++;
			}
			//blue.updateAndDraw();
			//blue.show();
			//IJ.run("Merge Channels...", "c1=[lena-std.tif (red)] c2=[lena-std.tif (green)] c3=[lena-std.tif (blue)]");
			RGBStackMerge rgbsm = new RGBStackMerge();
			img_out = rgbsm.mergeHyperstacks(new ImagePlus[]{red, green, blue}, false);
			if (channels!=null) {
				assert channels.length==3;
				channels[0].close();
				channels[1].close();
				channels[2].close();
			}
		} else {
			IJ.run(img_in, "Scale...","x=- y=- z=- width="+newW+" height="+newH+" interpolation=None average process create");
			img_out = IJ.getImage();
			switch (tr_imps.get(0).getBitDepth()) {
				case 8:
					IJ.run(img_out, "8-bit", "");
					break;
				case 16:
					IJ.run(img_out, "16-bit", "");
					break;
				case 32:
					IJ.run(img_out, "32-bit", "");
					break;
			}

			for (int i = 0; i < img_in.getStack().getSize(); i++) {
				img_out.getStack().setProcessor(tr_imps.get(i), i + 1);
			}
		}
		img_out.setCalibration(new Calibration()); // removes metadata
		img_out.updateAndDraw();
		if (isRGB) {
			new StackConverter(img_out).convertToRGB();
		}
	}
	
}
