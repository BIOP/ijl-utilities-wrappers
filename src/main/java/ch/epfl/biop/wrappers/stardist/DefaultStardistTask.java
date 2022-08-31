package ch.epfl.biop.wrappers.stardist;

import java.util.ArrayList;
import static ch.epfl.biop.wrappers.stardist.StardistTaskSettings.MODE2D;
import static ch.epfl.biop.wrappers.stardist.StardistTaskSettings.MODE3D;

public class DefaultStardistTask extends StardistTask {

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        if(settings.dimension.equals(MODE2D))
            options.add("stardist-predict2D");
        else
            options.add("stardist-predict3D");

        options.add("-i");
        options.add("" + settings.image_path);

        options.add("-m");
        options.add("" + settings.model_path);

        options.add("-o");
        options.add("" + settings.output_path);

        if(settings.dimension.equals(MODE3D)){
            if ((settings.x_tiles > -1) && (settings.y_tiles > -1) && (settings.z_tiles > -1)) {
                options.add("--n_tiles");
                options.add("" + settings.z_tiles);
                options.add("" + settings.y_tiles);
                options.add("" + settings.x_tiles);
            }
        }else if (settings.dimension.equals(MODE2D)){
            if ((settings.x_tiles > -1) && (settings.y_tiles > -1) ) {
                options.add("--n_tiles");
                options.add("" + settings.y_tiles);
                options.add("" + settings.x_tiles);
            }
        }

        if ((settings.pmin != (float) 3.0) || (settings.pmax != (float) 99.8)) {
            options.add("--pnorm");
            options.add("" + settings.pmin);
            options.add("" + settings.pmax);
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
