package ch.epfl.biop.wrappers.cellpose;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import ij.Prefs;

public class Cellpose {

    public static String keyPrefix = Cellpose.class.getName()+".";

    //static String defaultExePath = "C:/Users/username/.conda/envs/cellpose";
    static String defaultEnvDirPath = "E:/conda-envs/CellPoseGPU3";
    static String defaultEnvType    = "conda";
    static boolean defaultUseGpu    = true;
    static boolean defaultUseMxnet  = false;
    static boolean defaultUseFastMode = false;
    static boolean defaultUseResample = false;

    public static String envDirPath = Prefs.get(keyPrefix+"envDirPath", defaultEnvDirPath);
    public static String envType = Prefs.get(keyPrefix+"envType", defaultEnvType);
    public static boolean useGpu = Prefs.get(keyPrefix+"useGpu", defaultUseGpu);
    public static boolean useMxnet = Prefs.get(keyPrefix+"useMxnet", defaultUseMxnet);
    public static boolean useFastMode = Prefs.get(keyPrefix+"useFastMode", defaultUseFastMode);
    public static boolean useResample = Prefs.get(keyPrefix+"useFastMode", defaultUseResample);

    public static void setEnvDirPath(File f) {
        envDirPath = f.getAbsolutePath();
        Prefs.set(keyPrefix + "envDirPath", envDirPath);
    }
    public static void setEnvType(String envType){ Prefs.set(keyPrefix + "envType"    , envType); }
    public static void setUseGpu(boolean useGpu){  Prefs.set(keyPrefix + "useGpu"     , useGpu); }
    public static void setUseMxnet(Boolean useMxnet){ Prefs.set(keyPrefix + "useMxnet" , useMxnet);}
    public static void setUseFastMode(Boolean useFastMode){Prefs.set(keyPrefix + "useFastMode", useFastMode);}
    public static void setUseResample(Boolean useResample) {Prefs.set(keyPrefix + "useFastMode", useResample);}

    private static final File NULL_FILE = new File((System.getProperty("os.name") .startsWith("Windows") ? "NUL" : "/dev/null"));

    static void execute(List<String> options, Consumer<InputStream> outputHandler)  throws IOException, InterruptedException {
            List<String> cmd = new ArrayList<>();

            String envType = Prefs.get(keyPrefix+"envType", Cellpose.envType);
            if ( envType.equals("conda") ) {
                List<String> conda_activate_cmd = Arrays.asList("cmd.exe", "/C", "conda", "activate", envDirPath);
                cmd.addAll(conda_activate_cmd);
            } else if  ( envType.equals("venv") ){ // venv
                List<String> venv_activate_cmd = Arrays.asList("cmd.exe", "/C", new File(envDirPath,"Scripts/activate").toString() );
                cmd.addAll(venv_activate_cmd);
            } else{
                System.out.println("Virtual env type unrecognized!");
            }

            cmd.add("&");// to have a second line
            List<String> cellpose_args_cmd = Arrays.asList("python", "-m", "cellpose");
            cmd.addAll(cellpose_args_cmd);

            if ( !options.isEmpty() ) {
                cmd.addAll(options);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            //pb.redirectOutput(NULL_FILE);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            if (outputHandler!=null) {outputHandler.accept(p.getInputStream());}
            p.waitFor();
    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
            ArrayList<String> cmdList = new ArrayList<>();
            cmdList.add(singleCommand);
            execute(cmdList, null);
    }

}
