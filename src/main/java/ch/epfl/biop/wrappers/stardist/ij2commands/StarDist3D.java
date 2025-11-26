package ch.epfl.biop.wrappers.stardist.ij2commands;

import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist3D...")
public class StarDist3D extends StarDistAbstractCommand implements Command{
    @Override
    void setSettings( StardistTaskSettings settings) {
        // No advanced settings for now
    }
}

