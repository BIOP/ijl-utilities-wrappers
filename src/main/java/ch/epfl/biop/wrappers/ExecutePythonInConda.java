package ch.epfl.biop.wrappers;
import ch.epfl.biop.wrappers.Conda;

import ij.IJ;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.io.File.separatorChar;

public class ExecutePythonInConda {

    public static void execute(String envDirPath, String envType, List<String> arguments , Consumer<InputStream> outputHandler) throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        List<String> start_cmd = null ;

        // start terminal
        if (IJ.isWindows()) {
            start_cmd =  Arrays.asList("cmd.exe", "/C");
        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            start_cmd = Arrays.asList("bash", "-c");
        } else {
            throw new RuntimeException("Unknown Operating System");
        }
        cmd.addAll( start_cmd );


        List<String> conda_activate_cmd = null;

        // Depending of the env type
        if (envType.equals("conda")) {

            if (IJ.isWindows()) {
                // Activate the conda env
                conda_activate_cmd = Arrays.asList("CALL", Conda.getWindowsCondaCommand(), "activate", envDirPath);
                cmd.addAll(conda_activate_cmd);
                // After starting the env we can now use the module
                cmd.add("&");// to have a second command
                List<String> module_args_cmd = Arrays.asList("python", "-Xutf8");
                cmd.addAll(module_args_cmd);
                cmd.addAll(arguments);
                // input options

        } else if ( IJ.isMacOSX() || IJ.isLinux()) {
            // instead of conda activate (so much headache!!!) specify the python to use
            String python_path = envDirPath+separatorChar+"bin"+separatorChar+"python";
            List<String> module_args_cmd = new ArrayList<>(Collections.singletonList(python_path));
            module_args_cmd.addAll(arguments);

                // convert to a string
                module_args_cmd = module_args_cmd.stream().map(s -> {
                    if (s.trim().contains(" "))
                        return "\"" + s.trim() + "\"";
                    return s;
                }).collect(Collectors.toList());
                // The last part needs to be sent as a single string, otherwise it does not run
                String cmdString = module_args_cmd.toString().replace(",","");

                // finally add to cmd
                cmd.add(cmdString.substring(1, cmdString.length()-1));
            }

        } else if (envType.equals("venv")) { // venv

            if (IJ.isWindows()) {
                List<String> venv_activate_cmd = Arrays.asList("cmd.exe", "/C", new File(envDirPath, "Scripts/activate").toString());
                cmd.addAll(venv_activate_cmd);
            } else if (IJ.isMacOSX() || IJ.isLinux()) {
                throw new UnsupportedOperationException("Mac/Unix not supported yet with virtual environment. Please try conda instead.");
            }

        } else {
            throw new UnsupportedOperationException("Virtual env type unrecognized!");
        }


        System.out.println( "Running "+arguments+" with the command in the line below: ");
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
