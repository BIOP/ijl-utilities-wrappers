package ch.epfl.biop.wrappers.cellpose;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import ij.IJ;
import ij.Prefs;

public class Cellpose {

    public static String keyPrefix = Cellpose.class.getName() + ".";

    static String defaultEnvDirPath = "C:/Users/username/.conda/envs/cellpose";//"E:/conda-envs/CellPoseGPU3";
    static String defaultEnvType = "conda";
    static boolean defaultUseGpu = true;
    static boolean defaultUseMxnet = false;
    static boolean defaultUseFastMode = false;
    static boolean defaultUseResample = false;
    static String defaultVersion = "1.0";

    public static String envDirPath = Prefs.get(keyPrefix + "envDirPath", defaultEnvDirPath);
    public static String envType = Prefs.get(keyPrefix + "envType", defaultEnvType);
    public static boolean useGpu = Prefs.get(keyPrefix + "useGpu", defaultUseGpu);
    public static boolean useMxnet = Prefs.get(keyPrefix + "useMxnet", defaultUseMxnet);
    public static boolean useFastMode = Prefs.get(keyPrefix + "useFastMode", defaultUseFastMode);
    public static boolean useResample = Prefs.get(keyPrefix + "useResample", defaultUseResample);
    public static String version = Prefs.get(keyPrefix + "version", defaultVersion);

    public static void setEnvDirPath(File f) {
        envDirPath = f.getAbsolutePath();
        Prefs.set(keyPrefix + "envDirPath", envDirPath);
    }

    public static void setEnvType(String envType) {
        Prefs.set(keyPrefix + "envType", envType);
    }

    public static void setUseGpu(boolean useGpu) {
        Prefs.set(keyPrefix + "useGpu", useGpu);
    }

    public static void setUseMxnet(boolean useMxnet) {
        Prefs.set(keyPrefix + "useMxnet", useMxnet);
    }

    public static void setUseFastMode(boolean useFastMode) {
        Prefs.set(keyPrefix + "useFastMode", useFastMode);
    }

    public static void setUseResample(boolean useResample) {
        Prefs.set(keyPrefix + "useResample", useResample);
    }

    public static void setVersion(String version) {
        Prefs.set(keyPrefix + "version", version);
    }

    private static final File NULL_FILE = new File((System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));

    static void execute(List<String> options, Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        List<String> start_cmd = null ;

        // Get the prefs about the env type
        String envType = Prefs.get(keyPrefix + "envType", Cellpose.envType);

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
        if (envType.equals("conda")) {
            List<String> conda_activate_cmd = null;

            if (IJ.isWindows()) {
                // Activate the conda env
                conda_activate_cmd = Arrays.asList("CALL", "conda.bat", "activate", envDirPath);
                cmd.addAll(conda_activate_cmd);
                // After starting the env we can now use cellpose
                cmd.add("&");// to have a second line
                List<String> cellpose_args_cmd = Arrays.asList("python", "-Xutf8", "-m", "cellpose");
                cmd.addAll(cellpose_args_cmd);
                // input options
                cmd.addAll(options);

            } else if ( IJ.isMacOSX()) {
                // Everything need to be in one big string

                //Activate the conda env
                conda_activate_cmd = Arrays.asList("conda", "activate", envDirPath );
                conda_activate_cmd.add("&&");// to have a second line
                // cellpose and params
                List<String> cellpose_args_cmd = Arrays.asList("python", "-m", "cellpose");
                conda_activate_cmd.addAll(cellpose_args_cmd);
                conda_activate_cmd.addAll(options);
                // convert to a string
                conda_activate_cmd = conda_activate_cmd.stream().map(s -> {
                    if (s.trim().contains(" "))
                        return "\"" + s.trim() + "\"";
                    return s;
                }).collect(Collectors.toList());

                // The last part needs to be sent as a single string, otherwise it does not run
                String cmdString = conda_activate_cmd.toString().replace(",","");

                cmd.add(cmdString.substring(1, cmdString.length()-1));

            }

        } else if (envType.equals("venv")) { // venv
            List<String> venv_activate_cmd = Arrays.asList("cmd.exe", "/C", new File(envDirPath, "Scripts/activate").toString());
            cmd.addAll(venv_activate_cmd);

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
            System.out.println("Runner " + envDirPath + " exited with value " + exitValue + ". Please check output above for indications of the problem.");
        } else {
            System.out.println(envType + " , " + envDirPath + " run finished");
        }

    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
        ArrayList<String> cmdList = new ArrayList<>();
        cmdList.add(singleCommand);
        execute(cmdList, null);
    }

}
