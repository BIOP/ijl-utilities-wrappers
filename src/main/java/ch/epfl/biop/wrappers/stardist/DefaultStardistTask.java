package ch.epfl.biop.wrappers.stardist;

import ch.epfl.biop.wrappers.cellpose.Cellpose;

import java.util.ArrayList;

public class DefaultStardistTask extends StardistTask {

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        options.add("-i");
        options.add("" + settings.image_path);

        options.add("-m");
        options.add("" + settings.model_path);

        options.add("-o");
        options.add("" + settings.output_path);

        if (settings.n_tiles > -1) {
            options.add("--n_tiles");
            options.add("" + settings.n_tiles);
            options.add("" + settings.n_tiles);
        }

        if ((settings.pmin != (float) 3.0) || (settings.pmax != (float) 99.8)) {
            options.add("--pnorm");
            options.add("" + settings.pmin );
            options.add("" + settings.pmax );
        }

        if (settings.prob_thresh > -1) {
            options.add("--prob_thresh");
            options.add("" + settings.prob_thresh);
        }

        if (settings.nms_thresh > -1) {
            options.add("--nms_thresh");
            options.add("" + settings.nms_thresh);
        }

        Stardist.execute(options, null);
    }
}
