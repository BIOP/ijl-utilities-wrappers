package ch.epfl.biop.wrappers.cellpose.ij2commands;

import ch.epfl.biop.wrappers.cellpose.CellposeTaskSettings;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import java.io.IOException;
import java.net.URL;

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Cellpose/Omnipose>Cellpose SAM...")
public class CellposeSAM extends CellposeAbstractCommand implements Command {

    public void openModelsPage() {
        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/latest/models.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void openCliPage() {
        try {
            ps.open(new URL("https://cellpose.readthedocs.io/en/latest/cli.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    String getDefaultModelName() {
        return "cpsam";
    }

    @Override
    void setSettings(CellposeTaskSettings settings) {
        settings.setChannel1(-1);
        settings.setChannel2(-1);
    }
}