package ch.epfl.biop.java.utilities.roi;

import java.io.File;

/**
 * Wraps a transformix transform setup
 * Which is a zip file containing the transformation sequence
 */

public class TransformixInputRoisFile {
	public File f;
	public TransformixInputRoisFile(File f) {
		this.f=f;
	}
}
