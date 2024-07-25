package ch.epfl.biop.java.utilities.roi.types;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.apache.commons.io.FileUtils;

/**
 * Wraps a SVG file format
 */

public class SVGRoisFormat {
	public File f;
	public float sampleLength=10f;
	public float downscaleFactor=1f;
	public SVGRoisFormat(File f) {
		this.f=f;
	}
	
	public SVGRoisFormat(String path) {
        this.f = new File(path);
	}
	
	public SVGRoisFormat(URL url, float  sL, float dF) {
		try {
			sampleLength = sL;
			downscaleFactor = dF;
			File temp = File.createTempFile("timg", ".svg");
	        temp.deleteOnExit();
	        FileUtils.copyURLToFile(url, temp, 10000, 10000);
			this.f=temp;
		} catch (IOException e) {
            e.printStackTrace();
        }
	}
	
}
