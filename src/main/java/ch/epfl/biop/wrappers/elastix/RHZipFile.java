package ch.epfl.biop.wrappers.elastix;

import java.io.File;

/**
 * Wraps registration / transformation parameters chains into a zip file
 */

public class RHZipFile {
	public File f;
	public RHZipFile(File f) {
		this.f=f;
	}
}
