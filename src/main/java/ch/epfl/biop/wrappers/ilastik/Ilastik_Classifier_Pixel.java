package ch.epfl.biop.wrappers.ilastik;

import ch.epfl.biop.java.utilities.image.ConvertibleImage;
import ij.ImagePlus;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Ilastik>Ilastik Pixel Classification")
public class Ilastik_Classifier_Pixel implements Command {

    @Parameter
    LogService ls;

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

    @Parameter
    boolean verbose = false;

    @Override
    public void run() {
        if (!ilastikProjectFile.exists()) {
            ls.error("Ilastik Project File : "+ilastikProjectFile.getAbsolutePath()+" does not exist!");
            return;
        }

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
        if (verbose) ls.info("Results file :"+outputFileName);

        try {

            String titleImage = FilenameUtils.removeExtension(image_in.getTitle())+"_"+FilenameUtils.removeExtension(FilenameUtils.getName(ilastikProjectFile.getAbsolutePath()))+"_"+it.export_source;
            image_out = cs.convert(io.open(outputFileName), ImagePlus.class);
            image_out.setTitle(titleImage);

            if ((new File(outputFileName)).delete()) {
                // Temp file correctly deleted
            } else {
                ls.error("Error, couldn't delete temp file"+outputFileName);
            }
        } catch (IOException e) {
            ls.error("Error detected! Have you checked your ilp file ? Are the export settings right ? Check https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/imagej_tools/fiji_ilastik_bridge/ ");
            e.printStackTrace();
        }

    }
}
