package ch.epfl.biop.wrappers.stardist;

import ij.IJ;
import ij.Prefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.io.File.separatorChar;

public class Stardist {

    public static String keyPrefix = Stardist.class.getName() + ".";

    static String defaultStardistEnvDirPath = "C:/Users/username/.conda/envs/stardist"; //D:\conda_envs\stardistTF115
    static String defaultStardistEnvType = "conda";

    public static String stardistEnvDirectory = Prefs.get(keyPrefix + "Stardist_envDirPath", defaultStardistEnvDirPath);
    public static String stardistEnvType = Prefs.get(keyPrefix + "Stardist_envType", defaultStardistEnvType);

    public static void setStardistEnvDirPath(File f) {
        stardistEnvDirectory = f.getAbsolutePath();
        Prefs.set(keyPrefix + "Stardist_envDirPath", stardistEnvDirectory);
    }

    public static void setStardistEnvType(String stardistEnvType) {
        Prefs.set(keyPrefix + "Stardist_envType", stardistEnvType);
    }

    private static final File NULL_FILE = new File((System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));

    static void execute(List<String> options, Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        List<String> start_cmd = null ;

        // Get the prefs about the env type
        String stardistEnvDirectory = Prefs.get(keyPrefix + "Stardist_envDirPath", Stardist.stardistEnvDirectory);
        String stardistEnvType = Prefs.get(keyPrefix + "Stardist_envType", Stardist.stardistEnvType);
/*
        // Depending of the env type
        if (stardistEnvType.equals("conda")) {
            List<String> conda_activate_cmd = null;

            if (IJ.isWindows()) {
                conda_activate_cmd = Arrays.asList("cmd.exe", "/C", "conda", "activate", stardistEnvDirectory);
            } else if (IJ.isLinux() || IJ.isMacOSX()) {
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
        cmd.addAll(stardist_args_cmd);

        // input options
        // input options
        cmd.addAll(options);

        System.out.println(cmd.toString().replace(",", ""));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);

*/
        // start terminal
        if (IJ.isWindows()) {
            start_cmd=  Arrays.asList("cmd.exe", "/C");
        } else if ( IJ.isMacOSX()) {
            start_cmd = Arrays.asList("bash", "-c");
        }else if (IJ.isLinux()){
            throw new UnsupportedOperationException("Linux not supported yet");
        }
        cmd.addAll( start_cmd );


        // Depending of the env type
        if (stardistEnvType.equals("conda")) {
            List<String> conda_activate_cmd = null;

            if (IJ.isWindows()) {
                // Activate the conda env
                conda_activate_cmd = Arrays.asList("CALL", "conda.bat", "activate", stardistEnvDirectory);
                //conda_activate_cmd = Arrays.asList("conda", "activate", stardistEnvDirectory);
                cmd.addAll(conda_activate_cmd);
                // After starting the env we can now use cellpose
                cmd.add("&");// to have a second command
                List<String> args_cmd = Arrays.asList("stardist-predict3d");
                cmd.addAll(args_cmd);
                // input options
                cmd.addAll(options);

            } else if ( IJ.isMacOSX()) {
                // instead of conda activate (so much headache!!!) specify the python to use
                String python_path = stardistEnvDirectory+separatorChar+"bin"+separatorChar+"python";
                List<String> cellpose_args_cmd = new ArrayList<>(Arrays.asList( python_path , "stardist-predict3d"));
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

        } else if (stardistEnvType.equals("venv")) { // venv

            if (IJ.isWindows()) {
                List<String> venv_activate_cmd = Arrays.asList("cmd.exe", "/C", new File(stardistEnvDirectory, "Scripts/activate").toString());
                cmd.addAll(venv_activate_cmd);
            } else if ( IJ.isMacOSX()) {
                throw new UnsupportedOperationException("Mac not supported yet");
            }

        } else {
            System.out.println("Virtual env type unrecognized!");
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
            System.out.println("Runner " + stardistEnvDirectory + " exited with value " + exitValue + ". Please check output above for indications of the problem.");
        } else {
            System.out.println(stardistEnvType + " , " + stardistEnvDirectory + " run finished");
        }
    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
        ArrayList<String> cmdList = new ArrayList<>();
        cmdList.add(singleCommand);
        execute(cmdList, null);
    }


}
