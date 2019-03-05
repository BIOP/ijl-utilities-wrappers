package ch.epfl.biop.fiji.objectgui;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ch.epfl.biop.wrappers.elastix.ij2commands.Elastix_Save_Registration;
import net.imagej.ui.swing.viewer.EasySwingDisplayViewer;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.viewer.DisplayViewer;

import javax.swing.*;
import java.awt.*;

@Plugin(type = DisplayViewer.class)
public class SwingTransformixViewer extends
        EasySwingDisplayViewer<RegisterHelper> {


    public SwingTransformixViewer()
    {
        super( RegisterHelper.class );
    }

    @Override
    protected boolean canView(RegisterHelper rh) {
        return true;
    }

    @Override
    protected void redoLayout() {

    }

    @Override
    protected void setLabel(String s) {

    }

    @Override
    protected void redraw() {
        // Needs to update the display
        String str="";
        for (int i=0;i<rh.getNumberOfTransform();i++) {
            str+=rh.getTransformFile(i)+"\n";
        }
        textInfo.setText(str);
    }

    RegisterHelper rh = null;

    @Parameter
    CommandService cs;

    JPanel panelInfo;
    JLabel nameLabel;
    JTextArea textInfo;
    @Override
    protected JPanel createDisplayPanel(RegisterHelper rh) {
        this.rh = rh;
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panelInfo = new JPanel();
        JButton save = new JButton();
        save.addActionListener(event -> cs.run(Elastix_Save_Registration.class, true, "rh",this.rh));
        save.setText("Save");
        panel.add(panelInfo, BorderLayout.CENTER);
        panel.add(save, BorderLayout.SOUTH);
        nameLabel = new JLabel(rh.toString());
        panel.add(nameLabel, BorderLayout.NORTH);
        textInfo = new JTextArea();
        textInfo.setEditable(false);
        panelInfo.add(textInfo);
        this.redraw();
        return panel;
    }
}
