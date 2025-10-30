package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.ExecutePythonInConda;
import java.util.ArrayList;

public class DefaultCellposeTask extends CellposeTask {

    public void run() throws Exception {

        String envPath = settings.envPath;
        String envType = settings.envType;

        ArrayList<String> arguments = new ArrayList<>();

        arguments.add("-m");
        arguments.add("cellpose");

        arguments.add("--dir");
        arguments.add(settings.datasetDir);

        arguments.add("--pretrained_model");
        arguments.add(settings.model);

        if (settings.ch1 != -1) {
            arguments.add("--chan");
            arguments.add("" + settings.ch1);
        }

        if (settings.ch2 != -1) {
            arguments.add("--chan2");
            arguments.add("" + settings.ch2);
        }

        arguments.add("--diameter");
        arguments.add("" + settings.diameter);

        arguments.add("--verbose");//we default the verbose now that logger is working
        arguments.add("--save_tif");
        arguments.add("--no_npy");

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
