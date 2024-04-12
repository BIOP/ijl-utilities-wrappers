package ch.epfl.biop.wrappers.omnipose;


import java.util.ArrayList;

public class DefaultOmniposeTask extends OmniposeTask{


    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        String conda_env_path = settings.conda_env_path;

        options.add("--dir");
        options.add("" + settings.datasetDir);

        options.add("--pretrained_model");
        options.add("" + settings.model);

        options.add("--diameter");
        options.add("" + settings.diameter);

        options.add("--verbose");//we default the verbose now that logger is working
        options.add("--save_tif");
        options.add("--no_npy");

        if (settings.additional_flags != "") {
            String[] flagsList = settings.additional_flags.split(",");

            if (flagsList.length > 1) {
                for (int i = 0; i < flagsList.length; i++) {
                    options.add(flagsList[i].toString().trim());
                }
            } else {
                if (settings.additional_flags.length() > 1) {
                    options.add(settings.additional_flags.trim());
                }
            }
        }
        Omnipose.execute(conda_env_path , options, null);
    }

}
