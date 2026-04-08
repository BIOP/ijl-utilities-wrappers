package ch.epfl.biop.wrappers;
import ch.epfl.biop.wrappers.Conda;

import ij.IJ;

import java.io.BufferedReader;
import java.io.File;
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

    private static boolean looksLikePixiEnvironmentDirectory(File path) {
        if (path == null || !path.isDirectory()) {
            return false;
        }
        File parent = path.getParentFile();
        File grandParent = parent != null ? parent.getParentFile() : null;
        return parent != null
                && grandParent != null
                && "envs".equals(parent.getName())
                && ".pixi".equals(grandParent.getName());
    }

    private static File resolvePixiEnvironmentFromEnvDirectory(File envsDirectory) {
        File defaultEnvironment = new File(envsDirectory, "default");
        if (defaultEnvironment.isDirectory()) {
            return defaultEnvironment;
        }

        File[] candidates = envsDirectory.listFiles(File::isDirectory);
        if (candidates != null && candidates.length == 1) {
            return candidates[0];
        }

        throw new IllegalArgumentException(
                "Could not determine Pixi environment inside " + envsDirectory.getAbsolutePath()
                        + ". Expected .pixi/envs/default, a single environment directory, or a specific .pixi/envs/<name> path.");
    }

    public static File resolveEnvironmentRoot(String envDirPath, String envType) {
        File configuredPath = new File(envDirPath);
        if (!"pixi".equals(envType)) {
            return configuredPath;
        }

        if (looksLikePixiEnvironmentDirectory(configuredPath)) {
            return configuredPath;
        }

        if (configuredPath.isDirectory() && "envs".equals(configuredPath.getName())) {
            File parent = configuredPath.getParentFile();
            if (parent != null && ".pixi".equals(parent.getName())) {
                return resolvePixiEnvironmentFromEnvDirectory(configuredPath);
            }
        }

        File envsDirectory = new File(new File(configuredPath, ".pixi"), "envs");
        if (!envsDirectory.isDirectory()) {
            throw new IllegalArgumentException(
                    "Pixi path must be either the project root containing .pixi/envs, the .pixi/envs directory itself, or a specific .pixi/envs/<name> directory: " + configuredPath.getAbsolutePath());
        }
        return resolvePixiEnvironmentFromEnvDirectory(envsDirectory);
    }

    private static String resolvePythonExecutable(File envRoot, String envType) {
        if (IJ.isWindows()) {
            if ("venv".equals(envType)) {
                return new File(envRoot, "Scripts/python.exe").getAbsolutePath();
            }
            return new File(envRoot, "python.exe").getAbsolutePath();
        }
        return new File(envRoot, "bin/python").getAbsolutePath();
    }

    private static String resolveEntryPointExecutable(File envRoot, String entryPoint) {
        File entryFile = new File(entryPoint);
        if (entryFile.isAbsolute() || entryPoint.contains("/") || entryPoint.contains("\\")) {
            return entryPoint;
        }

        if (IJ.isWindows()) {
            File scriptsDirectory = new File(envRoot, "Scripts");
            String[] suffixes = {"", ".exe", ".bat", ".cmd"};
            for (String suffix : suffixes) {
                File candidate = new File(scriptsDirectory, entryPoint + suffix);
                if (candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }
        } else {
            File candidate = new File(new File(envRoot, "bin"), entryPoint);
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return entryPoint;
    }

    private static String quoteShellArgument(String argument) {
        if (argument == null) return "";
        if (argument.contains(" ") || argument.contains("(") || argument.contains(")") || argument.contains(",")) {
            return "\"" + argument + "\"";
        }
        return argument;
    }

    private static String joinShellArguments(List<String> arguments) {
        return arguments.stream().map(ExecutePythonInConda::quoteShellArgument).collect(Collectors.joining(" "));
    }

    public static void execute(String envDirPath, String envType, List<String> arguments , Consumer<InputStream> outputHandler) throws IOException, InterruptedException {
        execute ( envDirPath,  envType, false , arguments ,  outputHandler);
    }

    public static void execute(String envDirPath, String envType, Boolean add_python , List<String> arguments , Consumer<InputStream> outputHandler) throws IOException, InterruptedException {

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

        File runtimeEnvRoot = resolveEnvironmentRoot(envDirPath, envType);
        String runtimeEnvPath = runtimeEnvRoot.getAbsolutePath();

        List<String> conda_activate_cmd = null;

        // Depending of the env type
        if (envType.equals("conda")) {

            if (IJ.isWindows()) {
                // Activate the conda env
                conda_activate_cmd = Arrays.asList("CALL", Conda.getWindowsCondaCommand(), "activate", runtimeEnvPath);
                cmd.addAll(conda_activate_cmd);
                // After starting the env we can now use the module
                cmd.add("&");// to have a second command
                // because :
                //  - cellpose cli starts with python -Xutf8 -m cellpose ... , while spotiflow and startidst don't!
                //  - and MacOS/Linux and Windows have different ways to call the python module !
                if (add_python){
                    List<String> module_args_cmd = Arrays.asList("python", "-Xutf8");
                    cmd.addAll(module_args_cmd);
                }

                cmd.addAll(arguments);
                // input options

            } else if ( IJ.isMacOSX() || IJ.isLinux()) {
                // instead of conda activate (so much headache!!!) specify the python to use
                // because cellpose and stardist/spotiflow don't work the same way
                String python_path = null;
                List<String> module_args_cmd = new ArrayList<>();
                if (add_python){ // cellpose case we start python can then add -m cellpose ..
                    python_path = runtimeEnvPath+separatorChar+"bin"+separatorChar+"python";
                    module_args_cmd = new ArrayList<>(Collections.singletonList(python_path));
                    module_args_cmd.addAll(arguments);
                } else { // for stardist/spotiflow we need to merge the conda path with th first argument to start the module
                    python_path = runtimeEnvPath+separatorChar+"bin"+separatorChar;
                    module_args_cmd = new ArrayList<>(Collections.singletonList(python_path));
                    module_args_cmd.addAll(arguments);
                    // merge first 2 arguments ! :face_palm_emoji:
                    module_args_cmd.set(1, module_args_cmd.get(0)+module_args_cmd.get(1));
                    module_args_cmd.remove(0);
                }

                // convert to a string with proper quoting
                StringBuilder cmdBuilder = new StringBuilder();
                for (String arg : module_args_cmd) {
                    if (arg.contains(" ") || arg.contains("(")|| arg.contains(")")|| arg.contains(",")) {
                        cmdBuilder.append("\"").append(arg).append("\" ");
                    } else {
                        cmdBuilder.append(arg).append(" ");
                    }
                }
                String cmdString = cmdBuilder.toString().trim();

                // finally add to cmd
                cmd.add(cmdString);

            }

        } else if (envType.equals("venv")) { // venv

            if (IJ.isWindows()) {
                List<String> venv_activate_cmd = Arrays.asList(new File(runtimeEnvPath, "Scripts/activate").toString());
                cmd.addAll(venv_activate_cmd);
                cmd.add("&");// to have a second command
                if (add_python){
                    List<String> module_args_cmd = Arrays.asList("python", "-Xutf8");
                    cmd.addAll(module_args_cmd);
                }
                cmd.addAll(arguments);
            } else if (IJ.isMacOSX() || IJ.isLinux()) {
                throw new UnsupportedOperationException("Mac/Unix not supported yet with virtual environment. Please try conda instead.");
            }

        } else if (envType.equals("pixi")) {

            if (IJ.isWindows()) {
                List<String> resolvedArguments = new ArrayList<>(arguments);
                if (add_python) {
                    cmd.add(resolvePythonExecutable(runtimeEnvRoot, envType));
                    cmd.add("-Xutf8");
                } else if (!resolvedArguments.isEmpty()) {
                    resolvedArguments.set(0, resolveEntryPointExecutable(runtimeEnvRoot, resolvedArguments.get(0)));
                }
                cmd.addAll(resolvedArguments);
            } else if (IJ.isMacOSX() || IJ.isLinux()) {
                List<String> resolvedArguments = new ArrayList<>(arguments);
                if (add_python) {
                    resolvedArguments.add(0, "-Xutf8");
                    resolvedArguments.add(0, resolvePythonExecutable(runtimeEnvRoot, envType));
                } else if (!resolvedArguments.isEmpty()) {
                    resolvedArguments.set(0, resolveEntryPointExecutable(runtimeEnvRoot, resolvedArguments.get(0)));
                }
                cmd.add(joinShellArguments(resolvedArguments));
            }

        } else {
            throw new UnsupportedOperationException("Virtual env type unrecognized!");
        }


        System.out.println( "Running "+arguments+" with the command in the line below: ");
        System.out.println(String.join(" ", cmd));
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
            String message = "Runner " + envDirPath + " exited with value " + exitValue + ". Please check output above for indications of the problem.";
            System.out.println(message);
            throw new IOException(message);
        } else {
            System.out.println( envDirPath + " run finished");
        }

    }
}
