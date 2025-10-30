import ij.IJ;
import ij.ImagePlus;

import net.imagej.ImageJ;
import net.imagej.patcher.LegacyInjector;

import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose;

import java.util.concurrent.ExecutionException;

public class DemoCellpose {

    static {
        LegacyInjector.preinit();
    }
    // Cellpose environment directory should be setup for this demo to work

    public static void main(String... args) throws Exception {

        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        IJ.run("Record...");

        //ImagePlus imp = new ImagePlus("src/test/resources/20191004_R03-C05-F03-crop.tif");
        ImagePlus imp = IJ.openImage("src/test/resources/MAX_20191004_R03-C05-F03-crop.tif");
        imp.show();

        // Test 3D Cyto, 2chs
        IJ.selectWindow( imp.getTitle() );
        cellpose3_2ch(ij);
        cellposeSAM_2ch(ij);

    }

    public static void cellpose3_2ch(ImageJ ij) throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus cytoLabel2chs_imp = (ImagePlus) ij.command().run(Cellpose.class, false,
                "env_path", "C:\\Users\\chiarutt\\AppData\\Local\\miniforge3\\envs\\cellpose-3111", // "C:\\ProgramData\\miniforge3\\envs\\cellpose4",//"C:\\Users\\chiarutt\\.conda\\envs\\cellpose4", // "D:/conda/conda-envs/cellpose-307-gpu/", //
                "imp",              imp,
                "diameter" ,        45.5,
                "model" ,           "cyto3",
                "model_path",       "", // empty
                "ch1",              2,
                "ch2",              1,
                "additional_flags", "--use_gpu").get().getOutput("cellpose_imp"); //"--do_3D"

        cytoLabel2chs_imp.show();

        IJ.run("Tile", "");
    }

    public static void cellposeSAM_2ch(ImageJ ij) throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus cytoLabel2chs_imp = (ImagePlus) ij.command().run(Cellpose.class, false,
                "env_path", "C:\\Users\\chiarutt\\AppData\\Local\\miniforge3\\envs\\cellpose-406", // "C:\\ProgramData\\miniforge3\\envs\\cellpose4",//"C:\\Users\\chiarutt\\.conda\\envs\\cellpose4", // "D:/conda/conda-envs/cellpose-307-gpu/", //
                "imp",              imp,
                "diameter",         30,
                "model",            "cpsam",
                "model_path",       "",
                "additional_flags", "--use_gpu").get()
                .getOutput("cellpose_imp"); //"--do_3D"

        cytoLabel2chs_imp.show();

        IJ.run("Tile", "");
    }

}
