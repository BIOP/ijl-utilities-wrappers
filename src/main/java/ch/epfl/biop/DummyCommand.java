package ch.epfl.biop;

import ch.epfl.biop.java.utilities.roi.types.CompositeFloatPoly;
import ch.epfl.biop.java.utilities.roi.types.IJShapeRoiArray;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.ImageJ;

import org.scijava.script.ScriptService;

import java.io.File;
import java.util.ArrayList;

public class DummyCommand {

	public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
	}

}