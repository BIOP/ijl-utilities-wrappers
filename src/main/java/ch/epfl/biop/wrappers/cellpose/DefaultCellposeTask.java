package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.ExecutePythonInConda;
import java.util.ArrayList;

public class DefaultCellposeTask extends CellposeTask {

    public void run() throws Exception {

        String conda_env_path = settings.conda_env_path;

        ArrayList<String> arguments = new ArrayList<>();

        arguments.add("-m");
        arguments.add("cellpose");

        arguments.add("--dir");
        arguments.add(settings.datasetDir);

        arguments.add("--pretrained_model");
        arguments.add(settings.model);

        arguments.add("--chan");
        arguments.add("" + settings.ch1);

        if (settings.ch2 != -1) {
            arguments.add("--chan2");
            arguments.add("" + settings.ch2);
        }

        arguments.add("--diameter");
        arguments.add("" + settings.diameter);

        arguments.add("--verbose");//we default the verbose now that logger is working
        arguments.add("--save_tif");
        arguments.add("--no_npy");

        if (settings.additional_flags != "") {
            String[] flagsList = settings.additional_flags.split(",");

            if (flagsList.length > 1) {
                for (String s : flagsList) {
                    arguments.add(s.trim());
                }
            } else {
                if (settings.additional_flags.length() > 1) {
                    arguments.add(settings.additional_flags.trim());
                }
            }
        }
        ExecutePythonInConda.execute(conda_env_path , arguments, null);
    }
}
