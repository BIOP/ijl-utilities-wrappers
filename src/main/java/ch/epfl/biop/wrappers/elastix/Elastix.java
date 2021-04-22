package ch.epfl.biop.wrappers.elastix;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

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

            if (IJ.isMacOSX() || IJ.isLinux())
            {
                // Modified from Christian Tischer:
                // https://github.com/tischi/DEPRECATED-fiji-plugin-elastixWrapper/blob/d02bb91820d5912c68cc31b6e562f7d6e5d14145/src/main/java/de/embl/cba/elastixwrapper/elastix/ElastixWrapper.java#L563
                // We assume a folder hierarchy
                // elastix
                //  |
                //  |-bin
                //  |   |-elastix
                //  |   \-transformix
                //  \-lib
                //      |-libANNlib
                // And defaultExePath points to the 'elastix' file
                //
                String elastix_folder_path= new File(new File(exePath).getParent()).getParent();
                String registrationCommand = "";
                for (String command: options) {
                    registrationCommand+=" "+command;
                }

                cmd.add("bash");
                cmd.add("-c");

                String exportPATH = "export PATH="+elastix_folder_path+"/bin/:$PATH";

                String exportLIB="";
                if ( IJ.isMacOSX() )
                {
                    exportLIB = "export DYLD_LIBRARY_PATH="+elastix_folder_path+"/lib/:$DYLD_LIBRARY_PATH";

                }
                else if ( IJ.isLinux() )
                {
                    // Not tested for linux
                    exportLIB = "export LD_LIBRARY_PATH="+elastix_folder_path+"/lib/:$LD_LIBRARY_PATH";
                }

                cmd.add(
                        exportLIB+" ; "+
                        exportPATH+" ; "+
                        "elastix"+registrationCommand
                );

            } else if (IJ.isWindows()) {
                cmd.add(exePath);
                cmd.addAll(options);
            } else {
                System.err.println("Elastix error: type of OS not found.");
            }

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