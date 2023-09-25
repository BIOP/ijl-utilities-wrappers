package ch.epfl.biop.wrappers.transformix;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ij.ImagePlus;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.function.Supplier;

import ch.epfl.biop.java.utilities.TempDirectory;
import ch.epfl.biop.java.utilities.image.ConvertibleImage;
import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
import ch.epfl.biop.java.utilities.roi.types.TransformixInputRoisFile;
import ch.epfl.biop.java.utilities.roi.types.TransformixOutputRoisFile;

public class TransformHelper {

    Supplier<String> transformFile;

    ConvertibleImage imageToTransform;
    ConvertibleImage imageTransformed;
    
    ConvertibleRois roisToTransform;
    ConvertibleRois roisTransformed;
    
    //Supplier<String> imgPath;

    int transformType = UNDEFINED;

    final static int UNDEFINED = -1;
    final static int IMAGE_TRANSFORM = 0;
    final static int ROIS_TRANSFORM = 1;

    Supplier<String> ptsPath;

    //TransformixTask transform;

    boolean transformTaskSet = false;
    boolean verbose = false;

    Supplier<String> outputDir;

    public TransformHelper() {
    	imageToTransform = new ConvertibleImage();
    	imageTransformed = new ConvertibleImage();
    	roisToTransform = new ConvertibleRois();
    	roisTransformed = new ConvertibleRois();
    }

    public void setTransformFile(RegisterHelper rh) {
        transformFile = rh::getFinalTransformFile;
        transformTaskSet = false;
    }

    public void verbose() {
        verbose = true;
    }

    public void setTransformFile(Supplier<String> pathToTransformFile) {
        transformFile = pathToTransformFile;
        transformTaskSet = false;
    }

    public void setTransformFile(String pathToTransformFile) {
        transformFile = () -> pathToTransformFile;
        transformTaskSet = false;
    }

    public void setImage(ImagePlus imp) {
    	imageToTransform.set(imp);
        transformTaskSet = false;
        transformType = IMAGE_TRANSFORM;
    }

    public void setImage(URL url) {
    	imageToTransform.set(url);
        transformTaskSet = false;
        transformType = IMAGE_TRANSFORM;
    }
    
    public void setImage(ConvertibleImage img) {
    	imageToTransform = img;
    	transformType = IMAGE_TRANSFORM;
    }
    
    public void setRois(ConvertibleRois rois) {
    	this.roisToTransform=rois;
    	transformType = TransformHelper.ROIS_TRANSFORM;
    }

    public void setImage(String pathToImage) {
        imageToTransform.set(new File(pathToImage));
        transformTaskSet = false;
        transformType = IMAGE_TRANSFORM;
    }

    public String getOutput() {
        if (transformType==IMAGE_TRANSFORM) {
            return outputDir.get()+ File.separator+"result.mhd";
        } else
        if (transformType==ROIS_TRANSFORM) {
            return null;//outputDir.get()+ File.separator+"result.mhd";
        }
        return null;
    }

    
    
    /*public void setImagePlus(ImagePlus imp_in) {
        //return null;
    }

    public void transformPts(String pathToPtsTxtFile) {
        //return null;
    }

    public void transformRoi(Roi roi) {
        //return null;
    }

    public ArrayList<Roi> transformRois(ArrayList<Roi> rois) {
        //return null;
    }*/

    public boolean checkParametersForTransformation() {
        //TODO
        /* if (fixedImage==null) {
            System.err.println("Fixed image not set");
            return false;
        }
        if (movingImage==null) {
            System.err.println("Fixed image not set");
            return false;
        }
        if (transformFilesSupplier.size()==0) {
            System.err.println("No transformation specified");
            return false;
        }*/

        if (transformType==UNDEFINED) {
            System.err.println("Transformation input parameters not set (image or text points).");
            return false;
        }

        if (outputDir==null) {
            setDefaultOutputDir();
            if (outputDir == null) {
                System.err.println("Could not create output directory");
                return false;
            }
        }
        return true;
    }

    public void setDefaultOutputDir() {
        TempDirectory tempDir = new TempDirectory("tr-out");
        tempDir.deleteOnExit();
        Path path = tempDir.getPath();
        outputDir = path::toString;
    }

    public String imageToTransformPathSupplier() {
    	return ((File) imageToTransform.to(File.class)).getAbsolutePath();
    }

    public String roisToTransformPathSupplier() {
    	TransformixInputRoisFile erf = (TransformixInputRoisFile) roisToTransform.to(TransformixInputRoisFile.class);
    	return erf.f.getAbsolutePath();
    }

    String transformInfo = null;

    public void setExtraRegisterInfo(String transformInfo) {
        this.transformInfo = transformInfo;
    }
    
    public void transform(TransformixTask task) {
        TransformixTaskSettings transformSettings = new TransformixTaskSettings().transform(this.transformFile)
                .outFolder(this.outputDir);
        if (!transformTaskSet) {
            if (checkParametersForTransformation()) {


                if (verbose) transformSettings.verbose();

                if (transformInfo!=null) transformSettings.taskInfo = transformInfo;

                if (transformType==IMAGE_TRANSFORM) {
                    transformSettings.image(this::imageToTransformPathSupplier);
                }

                if (transformType==ROIS_TRANSFORM) {
                    transformSettings.pts(this::roisToTransformPathSupplier);
                }
                
                //transform = new TransformixTask(transformSettings);//transformBuilder.build();
                //task.setSettings(transformSettings);
                transformTaskSet = true;
            } else {
                task = null;
                transformTaskSet = false;
            }
        }
        if (transformTaskSet) {
            try {
                task.run(transformSettings);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (transformType==IMAGE_TRANSFORM) {
            	imageTransformed.clear();
            	imageTransformed.set(new File(this.outputDir.get()+File.separator+"result.tif"));
            }
            if (transformType==ROIS_TRANSFORM) {
            	TransformixOutputRoisFile erf =  new TransformixOutputRoisFile(new File(this.outputDir.get()+File.separator+"outputpoints.txt"),
                        (TransformixInputRoisFile) roisToTransform.to(TransformixInputRoisFile.class));// roisToTransform.to(TransformixInputRoisFile.class);
            	roisTransformed.clear();            	
            	roisTransformed.set(erf);
            	//System.out.println("Output rois set!");
            	//roisTransformed.set(roisToTransform.to(ArrayList.class));
            	//roisTransformed.elastixFileFormatToArray(erf);
            	//new File(this.outputDir.get()+File.separator+"outputpoints.txt"));
            }
        }
    }
    
    public ConvertibleImage getTransformedImage() {
    	return imageTransformed;
    }
    
    public ConvertibleRois getTransformedRois() {
    	return roisTransformed;
    }



}