package ch.epfl.biop.wrappers.spotiflow;

import ch.epfl.biop.wrappers.ExecutePythonInConda;
import java.util.ArrayList;

public class DefaultSpotiflowTask extends SpotiflowTask {

    public void run() throws Exception {

        String envPath = settings.envPath;
        String envType = settings.envType;

        ArrayList<String> arguments = new ArrayList<>();

        arguments.add("spotiflow-predict");
        arguments.add(settings.datasetDir);

        arguments.add("-o");
        arguments.add(settings.datasetDir);

        //arguments.add("--verbose");//we default the verbose now that logger is working

        if (!settings.additional_flags.trim().isEmpty()) {
            String[] flagsList = settings.additional_flags.split(",");
            for (String s : flagsList) {
                if (!s.trim().isEmpty()) {
                    arguments.add(s.trim());
                }
            }
        }

        ExecutePythonInConda.execute(envPath, envType , arguments, null);
    }
}
