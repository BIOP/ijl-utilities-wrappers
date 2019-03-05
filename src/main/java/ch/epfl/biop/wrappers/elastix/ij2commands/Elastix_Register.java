package ch.epfl.biop.wrappers.elastix.ij2commands;

import ch.epfl.biop.wrappers.elastix.*;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;

/**
 * Elastix registration job launch
 * With standard registration parameters sets (rigid, affine, spline)
 * Works with ImagePlus as Inputs
 *
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Register")
public class Elastix_Register implements Command {

	@Parameter
	public ImagePlus movingImage;
	
	@Parameter
	public ImagePlus fixedImage;
	
	@Parameter
	public boolean rigid;

	@Parameter
	public boolean fast_affine;
	
	@Parameter
	public boolean affine;
	
	@Parameter
	public boolean spline;

	@Parameter(type = ItemIO.OUTPUT)
	public RegisterHelper rh;

	@Override
	public void run() {       
		rh = new RegisterHelper();
		rh.setMovingImage(movingImage);
		rh.setFixedImage(fixedImage);
		if (rigid) {
			RegistrationParameters rp = new RegParamRigid_Default();
			rh.addTransform(rp);
		}
		if (fast_affine) {
			RegistrationParameters rp = new RegParamAffine_Fast();
			rh.addTransform(rp);
		}
		if (affine) {
			RegistrationParameters rp = new RegParamAffine_Default();
			rh.addTransform(rp);
		}
		if (spline) {
			RegistrationParameters rp = new RegParamBSpline_Default();
			rh.addTransform(rp);
		}
		rh.align();
		rh.to(RHZipFile.class);
	}

}
