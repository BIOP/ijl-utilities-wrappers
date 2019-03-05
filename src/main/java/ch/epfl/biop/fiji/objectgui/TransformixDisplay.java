package ch.epfl.biop.fiji.objectgui;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import org.scijava.display.AbstractDisplay;
import org.scijava.display.Display;
import org.scijava.plugin.Plugin;

@Plugin(type = Display.class)
public class TransformixDisplay extends AbstractDisplay<RegisterHelper> {
    public TransformixDisplay() {
        super(RegisterHelper.class);
    }
}
