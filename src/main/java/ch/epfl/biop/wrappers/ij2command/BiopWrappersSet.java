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

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Set and Check Wrappers")
public class BiopWrappersSet implements Command {

	@Parameter
	LogService ls;
	
	@Parameter(required=false, persist = false)
	File elastixExecutable = new File(Elastix.exePath);
	
	@Parameter(required=false, persist = false)
	File transformixExecutable = new File(Transformix.exePath);
	
	@Override
	public void run() {
		Transformix.setExePath(transformixExecutable);

		Elastix.setExePath(elastixExecutable);

		if (ls!=null) {
			ls.info(BiopWrappersCheck.reportAllWrappers());
		}
	}

}
