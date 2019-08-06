package ch.epfl.biop.wrappers.ilastik;

import ch.epfl.biop.java.utilities.image.ConvertibleImage;
import ij.ImagePlus;
import org.apache.commons.io.FilenameUtils;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.io.IOService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.io.IOException;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Ilastik>Multicut")
public class Ilastik_Multicut implements Command {

    @Parameter(label = "Ilastik project file")
    File ilastikProjectFile;

    //@Parameter(label = "Classifier output", choices={"Simple Segmentation","Probabilities","Uncertainty","Labels","Features"})
    String export_source="Multicut Segmentation";

    @Parameter(label="The pixel type to convert your results to", choices={"uint8", "uint16", "float32"}) // uint32, int32, int8, int16, float64 and int32 unsupported by IJ1
    String export_dtype="uint8";

    @Parameter
    ImagePlus image_in_rawdata;

    @Parameter
    ImagePlus image_in_probabilities;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus image_out;

    @Parameter
    IOService io;

    @Parameter
    ConvertService cs;

    @Override
    public void run() {
        ConvertibleImage ci_raw = new ConvertibleImage();
        ci_raw.set(image_in_rawdata);
        String fNameIn_raw = ((File) ci_raw.to(File.class)).getAbsolutePath();


        ConvertibleImage ci_prob = new ConvertibleImage();
        ci_prob.set(image_in_probabilities);
        String fNameIn_prob = ((File) ci_prob.to(File.class)).getAbsolutePath();

        IlastikTask.IlastixTaskBuilder itBuilder = new IlastikTask.IlastixTaskBuilder()
                .project(() -> ilastikProjectFile.getAbsolutePath())
                .raw_data(() -> fNameIn_raw)
                .probabilities(() -> fNameIn_prob)
                .export_source(export_source)
                .export_dtype(export_dtype)
                .output_filename_format("{dataset_dir}/{nickname}_results.tiff");

        IlastikTask it = itBuilder.build();

        it.run();

        String fileNameWithOutExt = FilenameUtils.removeExtension(fNameIn_raw);
        String outputFileName = fileNameWithOutExt+"_results.tiff";
        System.out.println(outputFileName);

        try {

            String titleImage = FilenameUtils.removeExtension(image_in_rawdata.getTitle())+"_"+FilenameUtils.removeExtension(FilenameUtils.getName(ilastikProjectFile.getAbsolutePath()))+"_"+it.export_source;
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
