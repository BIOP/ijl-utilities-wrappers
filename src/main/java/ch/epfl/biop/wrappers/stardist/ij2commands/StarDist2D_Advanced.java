package ch.epfl.biop.wrappers.stardist.ij2commands;

import ch.epfl.biop.wrappers.stardist.StardistTaskSettings;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>StarDist>StarDist2D Adv. ...")
public class StarDist2D_Advanced extends StarDistAbstractCommand implements Command{
    @Parameter
    int x_tiles=-1;

    @Parameter
    int y_tiles=-1;

    @Parameter(style = "format:#.00")
    float min_norm= (float) 3.0;

    @Parameter(style = "format:#.00")
    float max_norm = (float) 99.8;

    @Parameter(style = "format:#.00")
    float prob_thresh = -1 ;

    @Parameter(style = "format:#.00")
    float nms_thresh = -1;

    @Override
    void setSettings( StardistTaskSettings settings) {
        settings.setMode2D();

        if (x_tiles > -1) settings.setXTiles(x_tiles);
        if (y_tiles > -1) settings.setYTiles(y_tiles);

        settings.setPmin (min_norm);
        settings.setPmax(max_norm);
        if (prob_thresh >-1) settings.setProbThresh(prob_thresh);
        if (nms_thresh >-1) settings.setNmsThresh(nms_thresh);
    }
}