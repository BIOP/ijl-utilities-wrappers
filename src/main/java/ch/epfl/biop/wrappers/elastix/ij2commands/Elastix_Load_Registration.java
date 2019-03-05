package ch.epfl.biop.wrappers.elastix.ij2commands;

import java.io.File;

import ch.epfl.biop.wrappers.elastix.RHZipFile;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Loads zip registration file for transformix
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Load Registration")
public class Elastix_Load_Registration implements Command {
    
	@Parameter
	File zipRegistrationFile;
	
	@Parameter(type = ItemIO.OUTPUT)
    RegisterHelper rh;
	
	@Override
    public void run() {
		rh = new RegisterHelper();
		rh.set(new RHZipFile(zipRegistrationFile));
		rh.clear(RegisterHelper.class);
		rh = (RegisterHelper) rh.to(RegisterHelper.class);
    }
}
