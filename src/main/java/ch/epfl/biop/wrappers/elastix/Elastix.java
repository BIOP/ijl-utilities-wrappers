package ch.epfl.biop.wrappers.elastix;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import ij.Prefs;

/**
 * Elastix task job launch
 * Stores the executable files
 */

public class Elastix {
	
	public static String keyPrefix = Elastix.class.getName()+".";
	
	static String defaultExePath = "elastix";
	public static String exePath = Prefs.get(keyPrefix+"exePath",defaultExePath);
    
    public static void setExePath(File f) {
        exePath = f.getAbsolutePath();
        Prefs.set(keyPrefix + "exePath", exePath);
    }

    private static final File NULL_FILE = new File(
            (System.getProperty("os.name")
                    .startsWith("Windows") ? "NUL" : "/dev/null")
    );
    
    public static void execute(List<String> options, boolean verbose) throws IOException, InterruptedException {
            //options.forEach(s -> System.out.println(s));
            List<String> cmd = new ArrayList<>();
            cmd.add(exePath);
            cmd.addAll(options);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            if (verbose) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            } else {
                pb.redirectOutput(NULL_FILE);
            }
            Process p = pb.start();
            p.waitFor();
    }
    
    public static void execute(String singleCommand) throws IOException, InterruptedException {
    	ArrayList<String> cmdList = new ArrayList<>();
    	cmdList.add(singleCommand);
    	execute(cmdList, false);
    }

}