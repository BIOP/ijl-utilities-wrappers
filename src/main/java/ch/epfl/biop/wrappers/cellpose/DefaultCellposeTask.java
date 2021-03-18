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

        if (settings.ch2 > 0){
            options.add("--chan2");
            options.add(""+settings.ch2);
        }

        options.add("--diameter");
        options.add(""+settings.diameter);

        options.add("--flow_threshold");
        options.add(""+settings.flow_threshold);

        options.add("--cellprob_threshold");
        options.add(""+settings.cellprob_threshold);

        if (settings.use3D) options.add("--do_3D");

        options.add("--save_tif");

        options.add("--no_npy");

        if (settings.useGpu) options.add("--use_gpu");
        if (settings.useMxnet) options.add("--mxnet");
        if (settings.useFastMode ) options.add("--fast_mode");
        if (settings.useResample ) options.add("--resample");

        Cellpose.execute(options, null);
    }
}
