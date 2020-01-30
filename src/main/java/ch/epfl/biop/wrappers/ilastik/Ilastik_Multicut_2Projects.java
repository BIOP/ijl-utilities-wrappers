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
import java.util.function.Consumer;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Ilastik>Ilastik Multicut (2 projects)")
public class Ilastik_Multicut_2Projects implements Command {

    Consumer<String> errlog = (text) -> System.err.println(text);

    Consumer<String> log = (text) -> System.out.println(text);

    @Parameter(label = "Ilastik project file: Edge pixel classifier")
    File ilastikProjectFileProba;

    @Parameter(label = "Ilastik project file: Multicut")
    File ilastikProjectFileMulticut;

    @Parameter(label="The pixel type to convert your results to", choices={"uint8", "uint16", "float32"}) // uint32, int32, int8, int16, float64 and int32 unsupported by IJ1
    String export_dtype="uint8";

    @Parameter
    ImagePlus image_in_rawdata;

    @Parameter(type = ItemIO.OUTPUT)
    ImagePlus image_out;

    @Parameter
    IOService io;

    @Parameter
    ConvertService cs;

    @Parameter(label="Intermediate probability format ", choices={"tif", "tiff", "h5"}) // uint32, int32, int8, int16, float64 and int32 unsupported by IJ1
    String intermediateProbabilityFormat="tiff";

    @Override
    public void run() {
        ConvertibleImage ci_raw = new ConvertibleImage();
        ci_raw.set(image_in_rawdata);
        String fNameIn_raw = ((File) ci_raw.to(File.class)).getAbsolutePath();

        log.accept("------------------- Proba");

        IlastikTask.IlastixTaskBuilder itBuilder = new IlastikTask.IlastixTaskBuilder()
                .project(() -> ilastikProjectFileProba.getAbsolutePath())
                .image(() -> fNameIn_raw)
                .export_source("probabilities")
                .export_dtype("float32")
                .output_filename_format("{dataset_dir}/{nickname}_results."+intermediateProbabilityFormat);

        IlastikTask it = itBuilder.build();

        it.run();

        String fileNameWithOutExtProba = FilenameUtils.removeExtension(fNameIn_raw);
        String outputFileNameProba = fileNameWithOutExtProba+"_results."+intermediateProbabilityFormat;
        log.accept(outputFileNameProba);

        log.accept("------------------- Multicut");

        itBuilder = new IlastikTask.IlastixTaskBuilder()
                .project(() -> ilastikProjectFileMulticut.getAbsolutePath())
                .raw_data(() -> fNameIn_raw)
                .probabilities(() -> outputFileNameProba)
                .export_source("Multicut Segmentation")
                .export_dtype(export_dtype)
                .output_filename_format("{dataset_dir}/{nickname}_results.tiff");

        it = itBuilder.build();

        it.run();

        String fileNameWithOutExt = FilenameUtils.removeExtension(fNameIn_raw);
        String outputFileName = fileNameWithOutExt+"_results.tiff";
        log.accept(outputFileName);

        try {
            String titleImage = FilenameUtils.removeExtension(image_in_rawdata.getTitle())+"_"+FilenameUtils.removeExtension(FilenameUtils.getName(ilastikProjectFileMulticut.getAbsolutePath()))+"_"+it.export_source;
            image_out = cs.convert(io.open(outputFileName), ImagePlus.class);
            image_out.setTitle(titleImage);

            if ((new File(outputFileName)).delete()) {
                // Temp file correctly deleted
            } else {
                errlog.accept("Error, couldn't delete temp file"+outputFileName);
            }
            if ((new File(outputFileNameProba)).delete()) {
                // Temp file correctly deleted
            } else {
                errlog.accept("Error, couldn't delete temp file"+outputFileNameProba);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
