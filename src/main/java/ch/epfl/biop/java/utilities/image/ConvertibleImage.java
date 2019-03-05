package ch.epfl.biop.java.utilities.image;
import java.io.File;
import java.io.IOException;
import java.net.URL;

import ij.CompositeImage;
import org.apache.commons.io.FileUtils;

import ch.epfl.biop.java.utilities.Converter;
import ch.epfl.biop.java.utilities.ConvertibleObject;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.Dataset;
import net.imagej.ImageJ;

/**
 * Converters for Images
 * ImagePlus -> File
 * File -> ImagePlus
 * url -> File
 * downcasting
 */

public class ConvertibleImage extends ConvertibleObject {
	
	@Converter
	public static ImagePlus fileToImagePlus(File f) {
		return IJ.openImage(f.getAbsolutePath());
	}

	@Converter
	public static File imagePlusToFile(ImagePlus imp) {
        try {
			File temp = File.createTempFile("timg", ".tif");
	        temp.deleteOnExit();
	        IJ.save(imp,temp.getAbsolutePath());
	        return temp;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
	}
	
	@Converter
	public static Dataset fileToDataSet(File f) {
		try {
			return (Dataset) ((new ImageJ()).io().open(f.getAbsolutePath()));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}
	
	@Converter
	public static File urlToFile(URL url) {
		try {
			File temp = File.createTempFile("timg", ".jpg");
	        temp.deleteOnExit();
	        FileUtils.copyURLToFile(url, temp, 10000, 10000);
	        return temp;
		} catch (IOException e) {
            e.printStackTrace();
            return null;
        }
	}

	@Converter
	public static ImagePlus downcastCompositeImageToImagePlus(CompositeImage ci) {
		return (ImagePlus) ci;
	}
	
	
}
