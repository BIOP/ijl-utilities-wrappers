package ch.epfl.biop.wrappers.stardist;

import ch.epfl.biop.wrappers.ExecutePythonInConda;

import java.util.ArrayList;

import static ch.epfl.biop.wrappers.stardist.StardistTaskSettings.MODE2D;
import static ch.epfl.biop.wrappers.stardist.StardistTaskSettings.MODE3D;

public class DefaultStardistTask extends StardistTask {

    public void run() throws Exception {
        ArrayList<String> arguments = new ArrayList<>();

        String envPath = settings.envPath;
        String envType = settings.envType;

        if(settings.dimension.equals(MODE2D))
            arguments.add("stardist-predict2d");
        else {
            arguments.add("stardist-predict3d");
        }
        arguments.add("-i");
        arguments.add(settings.image_path);

        arguments.add("-m");
        arguments.add(settings.model_path);

        arguments.add("-o");
        arguments.add(settings.output_path);

        if(settings.dimension.equals(MODE3D)){
            if ((settings.x_tiles > -1) && (settings.y_tiles > -1) && (settings.z_tiles > -1)) {
                arguments.add("--n_tiles");
                arguments.add("" + settings.z_tiles);
                arguments.add("" + settings.y_tiles);
                arguments.add("" + settings.x_tiles);
            } else {
                System.out.println("Please specify all dimensions");
            }
        }else if (settings.dimension.equals(MODE2D)){
            if ((settings.x_tiles > -1) && (settings.y_tiles > -1) ) {
                arguments.add("--n_tiles");
                arguments.add("" + settings.y_tiles);
                arguments.add("" + settings.x_tiles);
            }
        }

        if ((settings.pmin != (float) 3.0) || (settings.pmax != (float) 99.8)) {
            arguments.add("--pnorm");
            arguments.add("" + settings.pmin);
            arguments.add("" + settings.pmax);
        }

        if (settings.prob_thresh > -1) {
            arguments.add("--prob_thresh");
            arguments.add("" + settings.prob_thresh);
        }

        if (settings.nms_thresh > -1) {
            arguments.add("--nms_thresh");
            arguments.add("" + settings.nms_thresh);
        }

        ExecutePythonInConda.execute(envPath, envType , arguments, null);
    }
}
