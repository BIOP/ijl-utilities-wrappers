package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import net.imagej.ImageJ;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Cellpose Adv.")
public class Cellpose_SegmentImgPlusAdvanced implements Command{

    @Parameter
    ImagePlus imp;

    @Parameter
    int diameter = 30 ;

    @Parameter
    double cellproba_threshold = 0.0 ;

    @Parameter
    double flow_threshold = 0.0 ;

    @Parameter(choices = {"cyto","nuclei"} , callback = "modelchanged")
    String model ;

    @Parameter
    int ch1 ;

    @Parameter
    int ch2;

    @Parameter(choices = {"2D","3D"})
    String dimensionMode ;

    @Parameter (type= ItemIO.OUTPUT)
    ImagePlus cellpose_imp ;

    Boolean verbose=true ;

    public void modelchanged(){
        if (model.equals("nuclei")){
            ch2 = -1 ;
        }
    }

    @Override
    public void run() {
        // save the current imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        // create Tempdir
        File cellposeTempDir = new File( tempDir , "cellposeTemp");
        cellposeTempDir.mkdir();
        // and save the current imp into the Tempdir
        File imp_path = new File(cellposeTempDir, imp.getShortTitle()+".tif") ;
        FileSaver fs = new FileSaver(imp);
        fs.saveAsTiff( imp_path.toString() );
        if (verbose ) System.out.println(imp_path.toString());

        // Prepare cellPose settings
        CellposeTaskSettings settings = new CellposeTaskSettings();
        settings.setDatasetDir( cellposeTempDir.toString() );

        settings.setModel(model);
        settings.setChannel1(ch1);

        if (model.equals("cyto")) settings.setChannel2(ch2);
        else settings.setChannel2(ch1);

        settings.setDiameter( diameter );
        settings.setCellProbTh( cellproba_threshold );
        settings.setFlowTH( flow_threshold );

        if (dimensionMode.equals("3D")){
            if (imp.getNSlices() > 1 ) {
                settings.setDo3D();
            } else {
                System.out.println("NOTE : Can't use 3D mode, on 2D image");
            }
        }

        // and a cellpose task
        DefaultCellposeTask cellposeTask = new DefaultCellposeTask();
        try {
            //process imp with cellpose
            cellposeTask.setSettings(settings);
            cellposeTask.run();

            // open generated tif
            File cellpose_imp_path = new File(cellposeTempDir, imp.getShortTitle()+"_cp_masks"+".tif");
            // cellpose also creates a txt file (probably to be used with script to import ROI in imagej)
            File cellpose_outlines_path = new File(cellposeTempDir, imp.getShortTitle()+"_cp_outlines"+".txt");

            cellpose_imp = IJ.openImage(cellpose_imp_path.toString());

            // delete the created files and folder
            imp_path.delete();
            cellpose_imp_path.delete();
            cellpose_outlines_path.delete();
            cellposeTempDir.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    public static void main(final String... args) {

            // create the ImageJ application context with all available services
            final ImageJ ij = new ImageJ();
            ij.ui().showUI();
            ImagePlus imp = IJ.openImage("https://imagej.net/images/blobs.gif");
            imp.show();
            IJ.run(imp, "Invert LUT", "");
            // will run on the current image
            ij.command().run(Cellpose_SegmentImgPlusAdvanced.class, true);

    }
}