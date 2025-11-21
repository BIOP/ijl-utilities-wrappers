package ch.epfl.biop.wrappers.stardist.ij2commands;

import ch.epfl.biop.wrappers.stardist.Stardist;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist3D setup...")
public class StarDistPrefsSet implements Command {
    static {
        if (IJ.isLinux()) {
            stardistEnvDirectory = "/opt/conda/envs/stardist"; // to ease setting on biop-desktop    }
        } else if (IJ.isWindows()) {
            stardistEnvDirectory = "C:/Users/username/.conda/envs/stardist";
        } else if (IJ.isMacOSX()) {
            stardistEnvDirectory = "/Users/username/.conda/envs/stardist";
        }
    }
    static String stardistEnvDirectory;

    @Parameter
    LogService ls;

    @Parameter(style = "directory", persist = false)
    File stardist_env_dir = new File( stardistEnvDirectory);

    @Parameter(choices = {"conda", "venv"}, persist = false)
    String env_type = Stardist.stardistEnvType;

    @Override
    public void run() {

        Stardist.setStardistEnvDirPath(stardist_env_dir);
        Stardist.setStardistEnvType(env_type);

    }
}
