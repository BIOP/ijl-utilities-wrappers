#@ImagePlus imp
#@File(style="directory" , label="Select conda environment") conda_env_path
#@CommandService command
#@Output labels
#@RoiManager rm
#@ResultsTable rt

rt.reset()
rm.reset()
IJ.runMacro("close('\\\\Others');")

// cellpose parameters
def cp = new Cellpose();
cp.conda_env_path = conda_env_path
// Or comment the line avove and set the path manually
// to locate your conda env from a terminal type "conda env list", it will return the path you can paste below
//cp.conda_env_path = new File ( "D:/conda/conda_envs/cellpose/" ) ;
cp.imp = imp;
cp.diameter = 50;
cp.model = "cyto3";
cp.ch1 = 1 ;
cp.ch2 = 2 ;
cp.additional_flags= "--use_gpu, --do_3D, --anisotropy, 4 , --restore_type , denoise_cyto3";
// cellpose run
cp.run();
// get the output labels image
cp_imp = cp.cellpose_imp ;
cp_imp.show()

IJ.run(cp_imp, "Label image to ROIs", "");
IJ.run(cp_imp, "glasbey_inverted", "");

cp_imp.setDisplayRange(0, 12);

return

import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose
import ij.IJ;