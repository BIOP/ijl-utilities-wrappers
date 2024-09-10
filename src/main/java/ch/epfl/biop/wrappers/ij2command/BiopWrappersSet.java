package ch.epfl.biop.wrappers.ij2command;

import java.io.File;

import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ch.epfl.biop.wrappers.BiopWrappersCheck;
import ch.epfl.biop.wrappers.elastix.Elastix;
import ch.epfl.biop.wrappers.transformix.Transformix;

/**
 * Checks whether the executable being wrapped are accessible
 */

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Set and Check Wrappers")
public class BiopWrappersSet implements Command {

	@Parameter
	LogService ls;
	
	@Parameter(required=false, persist = false)
	File elastix_executable = new File(Elastix.exePath);
	
	@Parameter(required=false, persist = false)
	File transformix_executable = new File(Transformix.exePath);
	
	@Override
	public void run() {
		Transformix.setExePath(transformix_executable);

		Elastix.setExePath(elastix_executable);

		if (ls!=null) {
			ls.info(BiopWrappersCheck.reportAllWrappers());
		}
	}

}
