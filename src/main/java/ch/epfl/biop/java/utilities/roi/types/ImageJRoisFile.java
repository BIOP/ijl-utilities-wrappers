package ch.epfl.biop.java.utilities.roi.types;

import java.io.File;

/**
 * Wraps a ROIs ImageJ1 zip File
 */

public class ImageJRoisFile {
	public File f;
	public ImageJRoisFile(File f) {
		this.f=f;
	}
}
