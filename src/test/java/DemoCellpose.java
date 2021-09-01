import ch.epfl.biop.wrappers.cellpose.ij2commands.CellposePrefsSet;
import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose_SegmentImgPlusAdvanced;
import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose_SegmentNucleiImgPlusAdvanced;
import ch.epfl.biop.wrappers.cellpose.ij2commands.Cellpose_SegmentNucleiImgPlusBasic;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.Duplicator;
import net.imagej.ImageJ;
import net.imagej.patcher.LegacyInjector;


import java.util.concurrent.ExecutionException;

public class DemoCellpose {
    static ImageJ ij = new ImageJ();

    static {
        LegacyInjector.preinit();
    }
    // Cellpose environment directory should be setup for this demo to work

    final public static void main(String... args) throws Exception {

        ij.ui().showUI();

        ij.command().run(CellposePrefsSet.class, true).get();

        //ImagePlus imp = new ImagePlus("src/test/resources/20191004_R03-C05-F03-crop.tif");
        ImagePlus imp = IJ.openImage("src/test/resources/20191004_R03-C05-F03-crop.tif");
        imp.show();

        // Test 2D NucleiBasic
        //IJ.selectWindow( imp.getTitle() );
        nucleiBasic2D() ;

        // Test 3D NucleiAdvanced
        IJ.selectWindow( imp.getTitle() );
        nucleiAdv3D() ;

        // Test 3D Cyto
        IJ.selectWindow( imp.getTitle() );
        cytoAdv3D_1ch();

        // Test 3D Cyto, 2chs
        //IJ.selectWindow( imp.getTitle() );
        cytoAdv3D_2ch();

    }

    public static void nucleiBasic2D() throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus nuc2D_imp = new Duplicator().run(imp , 1,1,5,5,1,1);
        nuc2D_imp.setTitle( imp.getShortTitle()+"_2D_nuc");
        nuc2D_imp.show();
        ImagePlus nucLabel_imp = (ImagePlus) ij.command().run(  Cellpose_SegmentNucleiImgPlusBasic.class, true ,
                "imp",nuc2D_imp).get().getOutput("cellpose_imp");
        nucLabel_imp.show();
        IJ.run("Tile", "");
    }

    public static void nucleiAdv3D() throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus nuc3D_imp = new Duplicator().run(imp , 1,1,1,imp.getNSlices(),1,1);
        nuc3D_imp.setTitle( imp.getShortTitle()+"_3D_nuc_adv");
        nuc3D_imp.show();

        ImagePlus nucLabel3D_imp = (ImagePlus) ij.command().run(Cellpose_SegmentNucleiImgPlusAdvanced.class, true,
                "imp",nuc3D_imp,
                "nuclei_channel",1,
                "diameter" , 30,
                "cellproba_threshold", 0.0,
                "flow_threshold" , 0.4,
                "dimensionMode","3D").get().getOutput("cellpose_imp");
        nucLabel3D_imp.show();
        IJ.run("Tile", "");
    }

    public static void cytoAdv3D_1ch() throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus cyto_3D_imp = new Duplicator().run(imp , 2,2,1,imp.getNSlices(),1,1);
        cyto_3D_imp.setTitle( imp.getShortTitle()+"_3D_cyto_adv");
        cyto_3D_imp.show();

        ImagePlus cytoLabel_imp = (ImagePlus) ij.command().run(Cellpose_SegmentImgPlusAdvanced.class, true,
                "imp",cyto_3D_imp,
                "diameter" , 55,
                "cellproba_threshold", 0.0,
                "flow_threshold" , 0.4,
                "model" , "cyto (no nuclei)",
                "nuclei_channel",-1,
                "cyto_channel",1,
                "dimensionMode","3D").get().getOutput("cellpose_imp");
        cytoLabel_imp.show();

    }

    public static void cytoAdv3D_2ch() throws ExecutionException, InterruptedException {
        ImagePlus imp = IJ.getImage();
        ImagePlus cytoLabel2chs_imp = (ImagePlus) ij.command().run(Cellpose_SegmentImgPlusAdvanced.class, true,
                "imp",imp,
                "diameter" , 55,
                "cellproba_threshold", 0.0,
                "flow_threshold" , 0.4,
                "model" , "cyto",
                "nuclei_channel",1,
                "cyto_channel",2,
                "dimensionMode","3D").get().getOutput("cellpose_imp");
        cytoLabel2chs_imp.show();

        IJ.run("Tile", "");
    }

}
