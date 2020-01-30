package ch.epfl.biop.wrappers.ilastik;

import ch.epfl.biop.wrappers.BiopWrappersCheck;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

/**
 * Checks whether the executable being wrapped are accessible
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Ilastik>Set Ilastik Location")
public class BiopIlastikWrapperSetter implements Command {

    @Parameter
    LogService ls;

    @Parameter(visibility = ItemVisibility.MESSAGE)
    String message = "Select your ilastix executable file location";

    @Parameter()
    File ilastikExecutable = new File(Ilastik.exePath);

    @Override
    public void run() {

        Ilastik.setExePath(ilastikExecutable);

        if (ls!=null) {
            if (BiopWrappersCheck.isIlastikSet()) {
                ls.info("Ilastik was found ;-)");
            } else {
                ls.error("Ilastik was not found.");
            }
        }
    }

}
