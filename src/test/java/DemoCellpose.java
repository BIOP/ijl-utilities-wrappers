import ij.IJ;
import ij.ImagePlus;

import net.imagej.ImageJ;
import net.imagej.patcher.LegacyInjector;

import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose;

import java.util.concurrent.ExecutionException;

public class DemoCellpose {
    static ImageJ ij = new ImageJ();

    static {
        LegacyInjector.preinit();
    }
    // Cellpose environment directory should be setup for this demo to work

    final public static void main(String... args) throws Exception {

        ij.ui().showUI();

        //ImagePlus imp = new ImagePlus("src/test/resources/20191004_R03-C05-F03-crop.tif");
        ImagePlus imp = IJ.openImage("src/test/resources/20191004_R03-C05-F03-crop.tif");
        imp.show();

        // Test 3D Cyto, 2chs
        IJ.selectWindow( imp.getTitle() );
        cyto3D_2ch();

    }

    public static void cyto3D_2ch() throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus cytoLabel2chs_imp = (ImagePlus) ij.command().run(Cellpose.class, false,
                "conda_env_path", "C:\\Users\\chiarutt\\.conda\\envs\\cellpose",//""D:/conda/conda-envs/cellpose-307-gpu/",
                "imp", imp,
                "diameter" , 45,
                "model" , "cyto3",
                "model_path","", // empty
                "ch1",2,
                "ch2",1,
                "additional_flags", "--use_gpu, --do_3D").get().getOutput("cellpose_imp");

        cytoLabel2chs_imp.show();

        IJ.run("Tile", "");
    }

}
