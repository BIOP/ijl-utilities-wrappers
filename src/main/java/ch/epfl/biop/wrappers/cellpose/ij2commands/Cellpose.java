package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import java.io.IOException;
import java.net.URL;

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose/Omnipose>Cellpose ...")
public class Cellpose extends CellposeAbstractCommand implements Command {

    @Parameter (visibility=ItemVisibility.MESSAGE)
    String message4 ="Specify channels to be used for the prediction.";

    @Parameter(label = "--chan")
    int ch1 = 0;

    @Parameter(label = "--chan2")
    int ch2 = -1;

    public void openModelsPage() {
        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/v3.1.1.1/models.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void openCliPage() {
        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/v3.1.1.1/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    void setSettings(CellposeTaskSettings settings) {
        settings.setChannel1(ch1);
        if (ch2 > -1) {
            settings.setChannel2(ch2);
        }
    }
}