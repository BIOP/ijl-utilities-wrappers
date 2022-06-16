package ch.epfl.biop.wrappers.cellpose;

import java.util.ArrayList;

public class DefaultCellposeTask extends CellposeTask {

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        options.add("--dir");
        options.add("" + settings.datasetDir);

        options.add("--pretrained_model");
        options.add("" + settings.model);

        options.add("--chan");
        options.add("" + settings.ch1);

        if (settings.ch2 > 0) {
            options.add("--chan2");
            options.add("" + settings.ch2);
        }

        options.add("--diameter");
        options.add("" + settings.diameter);

        options.add("--flow_threshold");
        options.add("" + settings.flow_threshold);

        System.out.println("Cellpose version is set to:" + settings.version);
        if (settings.version.equals("0.6") || settings.version.equals("2.0")) {
            options.add("--cellprob_threshold");

        } else if (settings.version.equals("0.7") || settings.version.equals("1.0")) {
            options.add("--mask_threshold"); // supposed to be new flag name for 0.7 and 1.0 but not anymore in 2.
        }
        options.add("" + settings.cellprob_threshold);

        if (!settings.version.equals("0.6")) {

            if (settings.anisotropy != 1.0) {
                options.add("--anisotropy");
                options.add("" + settings.anisotropy);
            }

            if (settings.stitch_threshold > 0) {
                options.add("--stitch_threshold");
                options.add("" + settings.stitch_threshold);
                settings.do3D(false); // has to be 2D!
            }

            if (settings.omni) {
                options.add("--omni");
            }

            if (settings.cluster) {
                options.add("--cluster");
            }

            if (!settings.version.equals("2.0")) {
                if (settings.diam_threshold != 12) { // 12 is default the value
                    options.add("--diam_threshold");
                    options.add("" + settings.diam_threshold);
                }
            }

            options.add("--verbose");//we default the verbose now that logger is working

        }

        if (settings.use3D) options.add("--do_3D");

        options.add("--save_tif");

        options.add("--no_npy");

        if (settings.useGpu) options.add("--use_gpu");
        if (settings.useMxnet && !settings.version.equals("2.0")) options.add("--mxnet");
        if (settings.useFastMode) options.add("--fast_mode");
        if (settings.useResample && !settings.version.equals("1.0") && !settings.version.equals("2.0"))
            options.add("--resample");

        if (settings.additional_flags != null) {
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
        Cellpose.execute(options, null);
    }
}
