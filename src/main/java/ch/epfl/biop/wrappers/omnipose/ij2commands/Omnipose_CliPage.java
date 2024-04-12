package ch.epfl.biop.wrappers.omnipose.ij2commands;

import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.Parameter;

import java.io.IOException;
import java.net.URL;
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Omnipose> Open Omnipose CLI page")
public class Omnipose_CliPage implements Command    {

    @Parameter
    PlatformService ps;

    @Override
    public void run() {

        try {
            ps.open(new URL("https://omnipose.readthedocs.io/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
