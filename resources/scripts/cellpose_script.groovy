#@ImagePlus imp
#@CommandService command
#@Output labels
#@RoiManager rm
#@ResultsTable rt

rt.reset()
rm.reset()
IJ.runMacro("close('\\\\Others');")

// cellpose parameters
def cellCellpose = new Cellpose_SegmentImgPlusOwnModelAdvanced();
cellCellpose.imp = imp;
cellCellpose.diameter = 50;
//cellCellpose.cellprob_threshold = 0;
//cellCellpose.flow_threshold = 0.4;
cellCellpose.model = "cyto2";
cellCellpose.nuclei_channel = 2 ;
cellCellpose.cyto_channel = 1 ;
cellCellpose.dimensionMode  = "2D";
// cellpose run
cellCellpose.run();
// get the output labels image
cell_cellpose_imp = cellCellpose.cellpose_imp ;
cell_cellpose_imp.show()

IJ.run(cell_cellpose_imp, "Label image to ROIs", "");
IJ.run(cell_cellpose_imp, "glasbey_inverted", "");
cell_cellpose_imp.setDisplayRange(0, 12);

return

import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose_SegmentImgPlusOwnModelAdvanced
import ij.IJ;