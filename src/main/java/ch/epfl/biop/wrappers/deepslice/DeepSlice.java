package ch.epfl.biop.wrappers.deepslice;

import ch.epfl.biop.wrappers.Conda;
import ij.IJ;
import ij.Prefs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.io.File.separatorChar;

public class DeepSlice {

    public static String keyPrefix = DeepSlice.class.getName() + ".";

    static String defaultEnvDirPath = "C:/Users/username/.conda/envs/deepslice";//"E:/conda-envs/CellPoseGPU3";
    static String defaultVersion = "1.1.5.1";

    public static String envDirPath = Prefs.get(keyPrefix + "envDirPath", defaultEnvDirPath);

    public static String version = Prefs.get(keyPrefix + "version", defaultVersion);

    public static void setEnvDirPath(File f) {
        DeepSlice.envDirPath = f.getAbsolutePath();
        Prefs.set(keyPrefix + "envDirPath", envDirPath);
    }

    public static void setVersion(String version) {
        DeepSlice.version = version; // RAAAH!
        Prefs.set(keyPrefix + "version", version);
    }

    //private static final File NULL_FILE = new File((System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));

    static String getDeepSliceCLIScriptPath() {
        return envDirPath+File.separator+getDeepSliceCLIScriptName();
    }
    static String getDeepSliceCLIScriptName() {
        return "deepslice_cli_v"+version+".py";
    }

    static boolean ensureScriptIsCopied() {
        File f = new File(getDeepSliceCLIScriptPath());
        boolean fileExist = f.exists();
        if (fileExist) return true;
        // The script is not there, let's copy it
        // Specify the name of the resource file in the JAR
        String resourceFileName = getDeepSliceCLIScriptName();

        // Specify the destination folder where you want to copy the resource
        String destinationFolderPath = envDirPath;

        try {
            // Get the input stream of the resource file from the JAR
            InputStream inputStream = DeepSlice.class.getResourceAsStream( "/"+resourceFileName);
            if(inputStream == null) {
                throw new Exception("Cannot get resource \"" + resourceFileName + "\" from Jar file.");
            }

            // Create a Path object for the destination file
            Path destinationPath = Paths.get(destinationFolderPath, resourceFileName);

            // Copy the resource file to the destination folder
            Files.copy(inputStream, destinationPath, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("Resource file copied to: " + destinationPath);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not copy CLI script: "+e.getMessage());
            System.err.println("Please try to add the script file manually");
            return false;
        }
        return f.exists();
    }



    static void execute(List<String> options, Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
        if (!ensureScriptIsCopied()) {
            System.err.println("The CLI script to DeepSlice could not be copied to the env folder ("+envDirPath+")");
            System.err.println("You can try to copy manually "+getDeepSliceCLIScriptName()+" to this folder");
            return;
        }

        List<String> cmd = new ArrayList<>();
        List<String> start_cmd;

        // start terminal
        if (IJ.isWindows()) {
            start_cmd=  Arrays.asList("cmd.exe", "/C");
        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            start_cmd = Arrays.asList("bash", "-c");
        } else {
            IJ.error("OS unrecognized!!");
            start_cmd = Collections.singletonList("");
        }
        cmd.addAll( start_cmd );

        if (IJ.isWindows()) {
            // I have an issue with PyImageJ and process builder. I need to make a temp file instead of the above code.
            cmd.clear();
            File batchFile = File.createTempFile("tempDeepSlice", ".bat");
            BufferedWriter writer = new BufferedWriter(new FileWriter(batchFile));
            writer.write(Conda.getWindowsCondaCommand());
            writer.write(" activate \""+envDirPath+"\" ");
            writer.write("& ");
            writer.write("python -Xutf8 \""+getDeepSliceCLIScriptPath()+"\" ");
            for (String arg: options) {
                writer.write(arg+" ");
            }
            writer.close();
            cmd.add(batchFile.getAbsolutePath());
            batchFile.deleteOnExit();

        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            // instead of conda activate (so much headache!!!) specify the python to use
            String python_path = envDirPath+separatorChar+"bin"+separatorChar+"python";
            List<String> cellpose_args_cmd = new ArrayList<>(Arrays.asList( python_path , getDeepSliceCLIScriptPath()));
            cellpose_args_cmd.addAll(options);

            // convert to a string
            cellpose_args_cmd = cellpose_args_cmd.stream().map(s -> {
                if (s.trim().contains(" "))
                    return "\"" + s.trim() + "\"";
                return s;
            }).collect(Collectors.toList());
            // The last part needs to be sent as a single string, otherwise it does not run
            String cmdString = cellpose_args_cmd.toString().replace(",","");

            // finally add to cmd
            cmd.add(cmdString.substring(1, cmdString.length()-1));
        }

        System.out.println(cmd.toString().replace(",", ""));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);

        Process p = pb.start();
        Thread t = new Thread(Thread.currentThread().getName() + "-" + p.hashCode()) {
            @Override
            public void run() {
                BufferedReader stdIn = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    for (String line = stdIn.readLine(); line != null; ) {
                        System.out.println(line);
                        line = stdIn.readLine();// you don't want to remove or comment that line! no you don't :P
                    }
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            }
        };
        t.setDaemon(true);
        t.start();

        p.waitFor();
        int exitValue = p.exitValue();

        if (exitValue != 0) {
            System.out.println("Runner " + envDirPath + " exited with value " + exitValue + ". Please check output above for indications of the problem.");
        } else {
            System.out.println("conda , " + envDirPath + " run finished");
        }

    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
        ArrayList<String> cmdList = new ArrayList<>();
        cmdList.add(singleCommand);
        execute(cmdList, null);
    }

}
