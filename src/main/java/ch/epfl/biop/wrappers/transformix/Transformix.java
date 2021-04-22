package ch.epfl.biop.wrappers.transformix;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import ij.IJ;
import ij.Prefs;

public class Transformix {	
	
	public static String keyPrefix = Transformix.class.getName()+".";
	
	static String defaultExePath = "transformix";
	public static String exePath = Prefs.get(keyPrefix+"exePath",defaultExePath);//"/home/nico/Dropbox/BIOP/ABA/BrainServerTest/export.xml");

    public static void setExePath(File f) {
        exePath = f.getAbsolutePath().toString();
        Prefs.set(keyPrefix + "exePath", exePath);
    }
    
    public static void notifyIsInClassPath() {
    	exePath="transformix";
        Prefs.set(keyPrefix + "exePath", exePath);
    }

    private static File NULL_FILE = new File(
            (System.getProperty("os.name")
                    .startsWith("Windows") ? "NUL" : "/dev/null")
    );

    static void execute(List<String> options, boolean verbose)  throws IOException, InterruptedException {
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
            String transformix_folder_path= new File(new File(exePath).getParent()).getParent();
            String transformationCommand = "";
            for (String command: options) {
                transformationCommand+=" "+command;
            }

            cmd.add("bash");
            cmd.add("-c");

            String exportPATH = "export PATH="+transformix_folder_path+"/bin/:$PATH";

            String exportLIB="";
            if ( IJ.isMacOSX() )
            {
                exportLIB = "export DYLD_LIBRARY_PATH="+transformix_folder_path+"/lib/:$DYLD_LIBRARY_PATH";

            }
            else if ( IJ.isLinux() )
            {
                // Not tested for linux
                exportLIB = "export LD_LIBRARY_PATH="+transformix_folder_path+"/lib/:$LD_LIBRARY_PATH";
            }

            cmd.add(
                    exportLIB+" ; "+
                    exportPATH+" ; "+
                    "transformix"+transformationCommand
            );

        } else if (IJ.isWindows()) {
            cmd.add(exePath);
            cmd.addAll(options);
        } else {
            System.err.println("Transformix error: type of OS not found.");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (verbose) {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else {
            pb.redirectOutput(NULL_FILE);
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        p.waitFor();
    }
    
    public static void execute(String singleCommand) throws IOException, InterruptedException {
    	ArrayList<String> cmdList = new ArrayList<>();
    	cmdList.add(singleCommand);
    	execute(cmdList, false);
    }

}