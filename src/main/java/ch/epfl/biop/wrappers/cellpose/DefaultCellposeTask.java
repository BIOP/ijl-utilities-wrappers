package ch.epfl.biop.wrappers.cellpose;



import java.util.ArrayList;

public class DefaultCellposeTask extends CellposeTask {

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        options.add("--dir");
        options.add(""+settings.datasetDir);

        options.add("--pretrained_model");
        options.add(""+settings.model);

        options.add("--chan");
        options.add(""+settings.ch1);

        options.add("--diameter");
        options.add(""+settings.diameter);

        options.add("--flow_threshold");
        options.add(""+settings.flow_threshold);

        options.add("--cellprob_threshold");
        options.add(""+settings.cellprob_threshold);

        if (settings.use3D) options.add("--do_3D");

        // Some option set from preferences

        //options.add("--chan2");
        //options.add(""+settings.ch2.get());

        options.add("--save_tif");

        options.add("--no_npy");

        Cellpose.execute(options, null);
    }
}
