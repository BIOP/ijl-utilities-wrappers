package ch.epfl.biop.wrappers.transformix.ij2commands;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import ch.epfl.biop.wrappers.transformix.DefaultTransformixTask;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.object.ObjectService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
import ch.epfl.biop.wrappers.transformix.TransformHelper;
import ij.plugin.frame.RoiManager;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Transformix>Transform Rois")
public class Transformix_TransformROIs implements Command {
	
	@Parameter(required=false)
	public ConvertibleRois cr_in;
	
	@Parameter(type = ItemIO.OUTPUT)
	public ConvertibleRois cr_out;
	
	@Parameter
	public RegisterHelper rh;

	@Parameter
	public boolean roisFromRoiManager;
	
	@Parameter
	public boolean outputToRoiManager;
	
	@Parameter
	public ObjectService os;
	
	@Override
	public void run() {
		
		if (roisFromRoiManager) {
			//System.out.println("Fetching ROIs from Roi Manager");
			cr_in = new ConvertibleRois();
			cr_in.set(RoiManager.getRoiManager());
		}
		
		TransformHelper th = new TransformHelper();
		th.setTransformFile(rh);
		th.setRois(cr_in);
		th.transform(new DefaultTransformixTask());
		cr_out = th.getTransformedRois();

		if (os!=null) {
			os.addObject(cr_out);
		}

		if (outputToRoiManager) {
			cr_out.to(RoiManager.class);
		}

	}
	
	
	
}
