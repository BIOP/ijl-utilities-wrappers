package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import ch.epfl.biop.wrappers.cellpose.DefaultCellposeTask;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose>Segment Nuclei")
public class Cellpose_SegmentNucleiImgPlusBasic implements Command{

    @Parameter
    ImagePlus imp;

    // to get
    Boolean verbose=true ;

    @Override
    public void run() {
        // save the current imp in a temp folder
        String tempDir = IJ.getDirectory("Temp");
        if (verbose ) System.out.println(tempDir);
        // create Tempdir
        File cellposeTempDir = new File( tempDir , "cellposeTemp");
        cellposeTempDir.mkdir();
        if (verbose ) System.out.println(cellposeTempDir);

        File imp_path = new File(cellposeTempDir, imp.getShortTitle()+".tif") ;
        if (verbose ) System.out.println(imp_path.toString());

        FileSaver fs = new FileSaver(imp);
        fs.saveAsTiff(imp_path.toString() );

        // Prepare cellPose settings
        CellposeTaskSettings settings = new CellposeTaskSettings();
        settings.initialize();
        settings.setDatasetDir( cellposeTempDir.toString() );
        settings.setModelNuclei();
        settings.setChannel1(1);

        if (imp.getNSlices() > 1 ) settings.setDo3D();

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

            ImagePlus cellpose_imp = IJ.openImage(cellpose_imp_path.toString());
            cellpose_imp.show();

            // delete the created image
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
            ij.command().run(Cellpose_SegmentNucleiImgPlusBasic.class, true);

    }
}