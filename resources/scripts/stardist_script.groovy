#@ImagePlus imp
#@File(style="directory") path_to_model
#@CommandService command
#@Output labels
#@RoiManager rm
#@ResultsTable rt

rt.reset()
rm.reset()
IJ.runMacro("close('\\\\Others');")

// stardist 
// 3D
def stardist = new StarDist3D_SegmentImgPlus_Advanced();
// or 2D 
//def stardist = new StarDist2D_SegmentImgPlus_Advanced();

stardist.imp = imp;
stardist.model_path = path_to_model
//stardist.x_tiles = -1
//stardist.y_tiles = -1
//stardist.z_tiles = -1
//stardist.min_norm = 3.0
//stardist.max_norm = 99.8
//stardist.prob_thresh = -1
//stardist.nms_thresh = -1

// stardist run
stardist.run();

// get the output labels image
nuclei_imp = stardist.stardist_imp ;
nuclei_imp.show()

return

import ch.epfl.biop.wrappers.stardist.ij2commands.StarDist3D_SegmentImgPlus_Advanced
import ch.epfl.biop.wrappers.stardist.ij2commands.StarDist2D_SegmentImgPlus_Advanced
import ij.IJ;