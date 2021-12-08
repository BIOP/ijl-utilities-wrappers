package ch.epfl.biop.wrappers.stardist;

import ij.IJ;
import ij.Prefs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class Stardist {

    public static String keyPrefix = Stardist.class.getName()+".";

    static String defaultStardistEnvDirPath = "C:/Users/username/.conda/envs/stardist"; //D:\conda_envs\stardistTF115
    static String defaultStardistEnvType    = "conda";

    public static String stardistEnvDirectory = Prefs.get(keyPrefix+"Stardist_envDirPath", defaultStardistEnvDirPath);
    public static String stardistEnvType = Prefs.get(keyPrefix+"Stardist_envType", defaultStardistEnvType);

    public static void setStardistEnvDirPath(File f) {
        stardistEnvDirectory = f.getAbsolutePath();
        Prefs.set(keyPrefix + "Stardist_envDirPath", stardistEnvDirectory);
    }

    public static void setStardistEnvType(String stardistEnvType){ Prefs.set(keyPrefix + "Stardist_envType"    , stardistEnvType); }

    private static final File NULL_FILE = new File((System.getProperty("os.name") .startsWith("Windows") ? "NUL" : "/dev/null"));

    static void execute(List<String> options, Consumer<InputStream> outputHandler)  throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();

        // Get the prefs about the env type
        String stardistEnvDirectory = Prefs.get(keyPrefix+"Stardist_envDirPath", Stardist.stardistEnvDirectory);
        String stardistEnvType = Prefs.get(keyPrefix+"Stardist_envType", Stardist.stardistEnvType);

        // Depending of the env type
        if (stardistEnvType.equals("conda")) {
            List<String> conda_activate_cmd = null;

            if (  IJ.isWindows()) {
                conda_activate_cmd = Arrays.asList("cmd.exe", "/C", "conda", "activate", stardistEnvDirectory);
            } else if ( IJ.isLinux() || IJ.isMacOSX() ){
                // https://docs.conda.io/projects/conda/en/4.6.1/user-guide/tasks/manage-environments.html#id2
                // conda_activate_cmd = Arrays.asList("bash", "-c", "conda", "source","activate", envDirPath);
                throw new UnsupportedOperationException("Linux and MacOS not supported yet");
            }
            cmd.addAll(conda_activate_cmd);

        } else if (stardistEnvType.equals("venv")) { // venv
            List<String> venv_activate_cmd = Arrays.asList("cmd.exe", "/C", new File(stardistEnvDirectory, "Scripts/activate").toString());
            cmd.addAll(venv_activate_cmd);
        } else {
            System.out.println("Virtual env type unrecognized!");
        }

        // After starting the env we can now use stardist script
        cmd.add("&");// to have a second line
        List<String> stardist_args_cmd = Arrays.asList("stardist-predict3d");
        cmd.addAll( stardist_args_cmd);

        // input options
        cmd.addAll(options);
        //
        System.out.println( cmd );

        // Now the cmd line is ready
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        //pb.redirectOutput(NULL_FILE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        if (outputHandler != null) {
            outputHandler.accept(p.getInputStream());
        }
        p.waitFor();
    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
        ArrayList<String> cmdList = new ArrayList<>();
        cmdList.add(singleCommand);
        execute(cmdList, null);
    }


}
