package ch.epfl.biop.fiji.imageplusutils;

import ch.epfl.biop.wrappers.transformix.TransformHelper;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.ChannelSplitter;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.StackConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Utility methods for ImagePlus manipulation
 */

public class ImagePlusFunctions {

    /**
     * Apply a transformation operation correctly on an Image5D based on an operation which would work only for a channel
     * @param ipOperator
     * @return
     */
    static public ImagePlus splitApplyRecompose(Function<ImagePlus,ImagePlus> ipOperator, ImagePlus img_in) {

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
            return ipOperator.apply(imp).getProcessor();
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
                img_out_temp.getStack().setProcessor(tr_imps.get(i).convertToFloat(), i + 1);
            }
            /* Bug : imageprocessor not updated for one slide */
            img_out_temp.getProcessor().setPixels(tr_imps.get(0).convertToFloat().getPixels()); // Dirty fix slice 0 -> keep processor
        }
        if (isRGB) {
            new StackConverter(img_out_temp).convertToRGB();
        }
        img_out_temp.setTitle("Transformed_"+img_in.getTitle());
        ImagePlus img_out = img_out_temp.duplicate(); // only way found to have a correct display for composite images...
        img_out.setTitle(img_out.getTitle().substring(4)); // Removes DUP_ in title
        return img_out;
    }


    public static class ImagePlusBuilder {
        public String title="Undefined";
        public String type; //{"8-bit", "16-bit", "32-bit", "RGB"}
        public int width;
        public int height;
        public int c;
        public int z;
        public int t;
        public LUT[] luts;
        public boolean[] activeChannels;
        public float[] minMax;
        public int display_mode = IJ.GRAYSCALE;

        public ImagePlusBuilder with(Consumer<ImagePlusBuilder> builderFunction) {
            builderFunction.accept(this);
            return this;
        }

        public ImagePlusBuilder width(int w) {
            this.width = w;
            return this;
        }

        public ImagePlusBuilder height(int h) {
            this.height = h;
            return this;
        }

        public ImagePlusBuilder nChannels(int c) {
            this.c = c;
            return this;
        }

        public ImagePlusBuilder nFrames(int f) {
            this.t = f;
            return this;
        }

        public ImagePlusBuilder nSlices(int z) {
            this.z = z;
            return this;
        }

        public ImagePlusBuilder title(String title) {
            this.title = title;
            return this;
        }

        public ImagePlusBuilder type8Bit() {
            this.type="8-bit";
            return this;
        }

        public ImagePlusBuilder type16Bit() {
            this.type="16-bit";
            return this;
        }

        public ImagePlusBuilder type32Bit() {
            this.type="32-bit";
            return this;
        }

        public ImagePlusBuilder typeRGB() {
            this.type="RGB";
            return this;
        }

        public ImagePlusBuilder allAs(ImagePlus imp) {
            return this.dimensionsAs(imp).typeAs(imp).lutsAs(imp).minMaxAs(imp);
        }

        public ImagePlusBuilder dimensionsAs(ImagePlus imp) {
            this.width=imp.getWidth();
            this.height=imp.getHeight();
            this.c=imp.getNChannels();
            this.t=imp.getNFrames();
            this.z=imp.getNSlices();
            return this;
        }

        public ImagePlusBuilder lutsAs(ImagePlus imp) {
            luts = new LUT[imp.getNChannels()];
            luts = imp.getLuts();
            this.display_mode = imp.getDisplayMode();
            return this;
        }

        public ImagePlusBuilder minMaxAs(ImagePlus imp) {
            minMax = new float[2*imp.getNChannels()];
            for (int i=0;i<imp.getNChannels();i++) {
                imp.setC(i+1);
                minMax[2*i]=(float) imp.getDisplayRangeMin();
                minMax[2*i+1]=(float) imp.getDisplayRangeMax();
            }
            return this;
        }

        public ImagePlusBuilder typeAs(ImagePlus imp) {
            switch(imp.getType()) {
                case ImagePlus.GRAY8: this.type="8-bit"; break;
                case ImagePlus.COLOR_256: System.err.println("Unsupported operation : 8 bit color."); this.type="8-bit"; break;
                case ImagePlus.GRAY16: this.type="16-bit"; break;
                case ImagePlus.GRAY32: this.type="32-bit"; break;
                case ImagePlus.COLOR_RGB: this.type="RGB"; break;
            }
            return this;
        }


        public ImagePlus createImagePlus() {
            if (display_mode==IJ.COMPOSITE) type+=" composite";
            ImagePlus imp = IJ.createImage(title, type, width, height, c, z, t);
            if (luts!=null) {
                for (int ic=0;ic<c;ic++) {
                    // ic+1 is the channel index
                    if (luts.length>ic) {
                        imp.setC(ic+1);
                        imp.getProcessor().setLut(luts[ic]);
                    }
                }
            }
            if (minMax!=null) {
                for (int ic=0;ic<c;ic++) {
                    // ic+1 is the channel index
                    if (minMax.length>2*ic) {
                        imp.setC(ic+1);
                        imp.setDisplayRange(minMax[2*ic], minMax[2*ic+1]);
                    }
                }
            }

            imp.setDisplayMode(this.display_mode); //Causes many bugs! do not update with composite mode.... Rah!

            return imp;
        }

    }



}
