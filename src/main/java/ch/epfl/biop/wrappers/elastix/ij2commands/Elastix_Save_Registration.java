package ch.epfl.biop.wrappers.elastix.ij2commands;

import java.io.File;
import java.io.IOException;

import ch.epfl.biop.wrappers.elastix.RHZipFile;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import org.apache.commons.io.FileUtils;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Saves zip registration file from Elastix / Transformix
 */

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Save Registration")
public class Elastix_Save_Registration implements Command {

	@Parameter(type = ItemIO.INPUT)
    RegisterHelper rh;
	
	@Parameter(style="save")
	File file;

    @Override
    public void run() {
    	RHZipFile rzf = (RHZipFile) rh.to(RHZipFile.class);
        try {
			FileUtils.copyFile(rzf.f, file);
		} catch (IOException e) {
			e.printStackTrace();
		}
    }
}
