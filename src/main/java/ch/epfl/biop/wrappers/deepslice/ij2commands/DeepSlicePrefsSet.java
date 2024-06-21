package ch.epfl.biop.wrappers.deepslice.ij2commands;

import ch.epfl.biop.wrappers.BiopWrappersCheck;
import ch.epfl.biop.wrappers.deepslice.DeepSlice;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

/**
 * Checks whether the executable being wrapped are accessible
 */

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>DeepSlice>DeepSlice setup...",
description = "Set the conda environment that contains a functional DeepSlice module")
public class DeepSlicePrefsSet implements Command {

    @Parameter
    LogService ls;

    @Parameter(style = "directory", persist = false)
    File deepSliceEnvDirectory = new File(DeepSlice.envDirPath);

    @Parameter(choices = {"1.1.5.1"}, persist = false)
    String version = DeepSlice.version;

    @Override
    public void run() {

        DeepSlice.setEnvDirPath(deepSliceEnvDirectory);
        DeepSlice.setVersion(version);

        if (ls != null) {
            ls.info(BiopWrappersCheck.isDeepSliceSet());
        }
    }
}
