#@ImagePlus imp
#@File(style="directory" , label="Select conda environment") conda_env_path
#@RoiManager rm

rm.reset()
IJ.runMacro("close('\\\\Others');")

def spotiflow = new Spotiflow()

spotiflow.imp = imp
spotiflow.env_path = conda_env_path
spotiflow.rm = rm
spotiflow.additional_flags=""

spotiflow.run()

rm.runCommand(imp, "Show All")

return

import ch.epfl.biop.wrappers.spotiflow.ij2commands.Spotiflow

import ij.IJ;