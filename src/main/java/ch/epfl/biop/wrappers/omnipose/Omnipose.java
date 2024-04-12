package ch.epfl.biop.wrappers.omnipose;

import ch.epfl.biop.wrappers.Conda;
import ij.IJ;

import java.io.BufferedReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.io.File.separatorChar;

//TODO : make a single class to start conda env and execute module

public class Omnipose {

    static void execute(String envDirPath, List<String> options, Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        List<String> start_cmd = null ;

        // start terminal
        if (IJ.isWindows()) {
            start_cmd=  Arrays.asList("cmd.exe", "/C");
        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            start_cmd = Arrays.asList("bash", "-c");
        }
        cmd.addAll( start_cmd );


        List<String> conda_activate_cmd = null;

        if (IJ.isWindows()) {
            // Activate the conda env
            conda_activate_cmd = Arrays.asList("CALL", Conda.getWindowsCondaCommand(), "activate", envDirPath);
            cmd.addAll(conda_activate_cmd);
            // After starting the env we can now use omnipose
            cmd.add("&");// to have a second command
            List<String> omnipose_args_cmd = Arrays.asList("python", "-Xutf8", "-m", "omnipose");
            cmd.addAll(omnipose_args_cmd);
            // input options
            cmd.addAll(options);

        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            // instead of conda activate (so much headache!!!) specify the python to use
            String python_path = envDirPath+separatorChar+"bin"+separatorChar+"python";
            List<String> omnipose_args_cmd = new ArrayList<>(Arrays.asList( python_path , "-m","omnipose"));
            omnipose_args_cmd.addAll(options);

            // convert to a string
            omnipose_args_cmd = omnipose_args_cmd.stream().map(s -> {
                if (s.trim().contains(" "))
                    return "\"" + s.trim() + "\"";
                return s;
            }).collect(Collectors.toList());
            // The last part needs to be sent as a single string, otherwise it does not run
            String cmdString = omnipose_args_cmd.toString().replace(",","");

            // finally add to cmd
            cmd.add(cmdString.substring(1, cmdString.length()-1));
        }

        System.out.println( "Running Omnipose with the command in the line below: ");
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
            System.out.println( envDirPath + " run finished");
        }

    }


}
