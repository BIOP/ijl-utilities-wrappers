package ch.epfl.biop.wrappers.elastix;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import ij.IJ;
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
        exePath = f.getAbsolutePath().toString();
        Prefs.set(keyPrefix + "exePath", exePath);
    }
    
    public static void notifyIsInClassPath() {
    	exePath="elastix";
        Prefs.set(keyPrefix + "exePath", exePath);
    }

    private static File NULL_FILE = new File(
            (System.getProperty("os.name")
                    .startsWith("Windows") ? "NUL" : "/dev/null")
    );
    
    public static void execute(List<String> options, Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
            //options.forEach(s -> System.out.println(s));
            List<String> cmd = new ArrayList<>();
            cmd.add(exePath);
            cmd.addAll(options);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            //pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);//.Redirect.INHERIT);
            pb.redirectOutput(NULL_FILE);
            //pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            // any output?
            if (outputHandler!=null) {outputHandler.accept(p.getInputStream());}
            p.waitFor();
    }
    
    public static void execute(String singleCommand) throws IOException, InterruptedException {
    	ArrayList<String> cmdList = new ArrayList<>();
    	cmdList.add(singleCommand);
    	execute(cmdList, null);
    }

}