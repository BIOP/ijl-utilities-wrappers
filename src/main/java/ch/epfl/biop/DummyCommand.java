package ch.epfl.biop;

import bdv.BigDataViewer;
import net.imagej.ImageJ;

import net.imglib2.realtransform.RealTransform;

import java.awt.Dimension;

public class DummyCommand {

	public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //BiopWrappersCheck.reportAllWrappers();
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(Dimension.class);
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(RealTransform.class);
        //ScijavaPanelizableProcessorPlugin.keptClasses.add(BigDataViewer.class);
	}

}