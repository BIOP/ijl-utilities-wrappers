package ch.epfl.biop.fiji.imageplusutils;

import ij.IJ;
import ij.ImagePlus;
import ij.process.LUT;

import java.util.function.Consumer;

/**
 * Utility methods for ImagePlus manipulation
 */

public class ImagePlusFunctions {


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
