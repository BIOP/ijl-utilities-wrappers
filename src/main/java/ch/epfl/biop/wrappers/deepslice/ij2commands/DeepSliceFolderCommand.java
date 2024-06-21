package ch.epfl.biop.wrappers.deepslice.ij2commands;

import ch.epfl.biop.wrappers.deepslice.DeepSliceTaskSettings;
import ch.epfl.biop.wrappers.deepslice.DefaultDeepSliceTask;
import ij.IJ;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>DeepSlice>DeepSlice (folder)",
description = "Runs DeepSlice locally on a folder of your choice.")
public class DeepSliceFolderCommand implements Command {
    @Parameter(style = "directory")
    File input_folder;

    @Parameter(choices = {"mouse", "rat"})
    String model;

    @Parameter(required = false, description = "path of the output folder (input used by default)")
    File output_folder;

    @Parameter(description = "try with and without ensemble to find the model which best works for you")
    boolean ensemble = false;

    @Parameter(description = "if you have section numbers included in the filename as _sXXX set this to true")
    boolean section_number = false;

    @Parameter(description = "if you would like to normalise the angles")
    boolean propagate_angle = false;

    @Parameter(description = "order your sections according to the section number (section number should be present)")
    boolean enforce_index_order = false;

    @Parameter(description = "use enforce index spacing")
    boolean use_enforce_index_spacing = false;

    @Parameter(description = "alternative to enforce_index_order: if you know the\n" +
            " precise spacing (ie; 1, 2, 4, indicates that section 3\n" +
            " has been left out of the series), then you can set the\n" +
            " section thickness in microns with this parameter, or type 'None' " +
            " if you want DeepSlice to guess the spacing")
    String enforce_index_spacing = "None";

    @Override
    public void run() {
        DeepSliceTaskSettings settings = new DeepSliceTaskSettings();
        settings.model = model;
        settings.input_folder = input_folder.getAbsolutePath();
        if (output_folder!=null) settings.output_folder = output_folder.getAbsolutePath();
        settings.propagate_angles = propagate_angle;
        settings.section_numbers = section_number;
        settings.use_enforce_index_spacing = use_enforce_index_spacing;
        settings.enforce_index_spacing = enforce_index_spacing;
        settings.enforce_index_order = enforce_index_order;
        settings.ensemble = ensemble;
        DefaultDeepSliceTask task = new DefaultDeepSliceTask();
        task.setSettings(settings);
        try {
            task.run();
        } catch (Exception e) {
            IJ.log("Could not run DeepSlice: "+e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
