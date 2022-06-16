package ch.epfl.biop.java.utilities.roi.types;

import java.io.File;

public class TransformixOutputRoisFile {

	//Point	0	; InputIndex = [ 6335 7530 ]	; InputPoint = [ 6335.000000 7530.000000 ]	; OutputIndexFixed = [ 6270 7520 ]	; OutputPoint = [ 6270.019712 7520.199309 ]	; Deformation = [ -64.980286 -9.800692 ]

	public IJShapeRoiArray shapeRoiList;
	public File f;
	public TransformixOutputRoisFile(File f, TransformixInputRoisFile tirf) {

		this.f=f;
		if (tirf.shapeRoiList==null) {
			//System.out.println("shapeRoiList null in constructor");
		}
		this.shapeRoiList = tirf.shapeRoiList; // pass the connectivity data
	}
}
