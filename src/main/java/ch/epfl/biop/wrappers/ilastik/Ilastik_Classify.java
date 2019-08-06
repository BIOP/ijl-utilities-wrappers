package ch.epfl.biop.wrappers.ilastik;

import ch.epfl.biop.java.utilities.image.ConvertibleImage;
import ij.ImagePlus;
import net.imagej.Dataset;
import net.imagej.DefaultDataset;
import net.imglib2.img.display.imagej.ImageJFunctions;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.io.IOService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Ilastik>Classify")
public class Ilastik_Classify implements Command {

    @Parameter(label = "Ilastik project file")
    File ilastikProjectFile;

    @Parameter(label = "Classifier output", choices={"Simple Segmentation","Probabilities","Uncertainty","Labels","Features"})
    String export_source="Simple Segmentation";

    @Parameter(label="The pixel type to convert your results to", choices={"uint8", "uint16", "float32"}) // uint32, int32, int8, int16, float64 and int32 unsupported by IJ1
    String export_dtype="uint8";

    @Parameter
    ImagePlus image_in;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus image_out;

    @Parameter
    IOService io;

    @Parameter
    ConvertService cs;

    @Override
    public void run() {
        ConvertibleImage ci = new ConvertibleImage();
        ci.set(image_in);
        String fNameIn = ((File) ci.to(File.class)).getAbsolutePath();
        IlastikTask.IlastixTaskBuilder itBuilder = new IlastikTask.IlastixTaskBuilder()
                .project(() -> ilastikProjectFile.getAbsolutePath())
                .image(() -> fNameIn)
                .export_source(export_source)
                .export_dtype(export_dtype)
                .output_filename_format("{dataset_dir}/{nickname}_results.tiff");

        IlastikTask it = itBuilder.build();

        it.run();

        String fileNameWithOutExt = FilenameUtils.removeExtension(fNameIn);
        String outputFileName = fileNameWithOutExt+"_results.tiff";
        System.out.println(outputFileName);

        try {

            String titleImage = FilenameUtils.removeExtension(image_in.getTitle())+"_"+FilenameUtils.removeExtension(FilenameUtils.getName(ilastikProjectFile.getAbsolutePath()))+"_"+it.export_source;
            image_out = cs.convert(io.open(outputFileName), ImagePlus.class);
            image_out.setTitle(titleImage);

            if ((new File(outputFileName)).delete()) {
                // Temp file correctly deleted
            } else {
                System.err.println("Error, couldn't delete temp file"+outputFileName);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
