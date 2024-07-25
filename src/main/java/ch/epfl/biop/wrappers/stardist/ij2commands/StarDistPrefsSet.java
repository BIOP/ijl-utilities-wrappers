package ch.epfl.biop.wrappers.stardist.ij2commands;

import ch.epfl.biop.wrappers.stardist.Stardist;

import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist3D setup...")
public class StarDistPrefsSet implements Command {
    @Parameter
    LogService ls;

    @Parameter(style = "directory", persist = false)
    File stardistEnvDirectory = new File(Stardist.stardistEnvDirectory);

    @Parameter(choices = {"conda", "venv"}, persist = false)
    String envType = Stardist.stardistEnvType;

    @Override
    public void run() {

        Stardist.setStardistEnvDirPath(stardistEnvDirectory);
        Stardist.setStardistEnvType(envType);

    }
}
