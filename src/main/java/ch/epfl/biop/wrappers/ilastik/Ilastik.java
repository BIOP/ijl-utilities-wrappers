package ch.epfl.biop.wrappers.ilastik;

import ij.Prefs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Ilastik {

    public static String keyPrefix = Ilastik.class.getName()+".";

    static String defaultExePath = "ilastik";
    public static String exePath = Prefs.get(keyPrefix+"exePath",defaultExePath);

    public static void setExePath(File f) {
        exePath = "\""+f.getAbsolutePath()+"\""; //avoids Program Files path problem with space
        Prefs.set(keyPrefix + "exePath", exePath);
    }

    public static void notifyIsInClassPath() {
        exePath="ilastik";
        Prefs.set(keyPrefix + "exePath", exePath);
    }

    public static void execute(List<String> options) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath);
        cmd.addAll(options);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("LC_ALL", "en_US.UTF-8");
        pb.environment().put("LC_CTYPE", "en_US.UTF-8");
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        p.waitFor();
    }

    public static void execute(String singleCommand) throws IOException, InterruptedException {
        ArrayList<String> cmdList = new ArrayList<>();
        cmdList.add(singleCommand);
        execute(cmdList);
    }
}
