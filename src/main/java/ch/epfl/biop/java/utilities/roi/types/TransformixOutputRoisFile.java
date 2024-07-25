package ch.epfl.biop.java.utilities.roi.types;

import java.io.File;

public class TransformixOutputRoisFile {

	public final IJShapeRoiArray shapeRoiList;
	public final File f;

	public TransformixOutputRoisFile(File f, TransformixInputRoisFile tirf) {
		this.f=f;
		this.shapeRoiList = tirf.shapeRoiList; // pass the connectivity data
	}
}
