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

@SuppressWarnings({"CanBeFinal", "unused"})
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Elastix>Register")
public class Elastix_Register implements Command {

	@Parameter
	public ImagePlus moving_image;
	
	@Parameter
	public ImagePlus fixed_image;
	
	@Parameter
	public boolean rigid;

	@Parameter
	public boolean fast_affine;
	
	@Parameter
	public boolean affine;
	
	@Parameter
	public boolean spline;

	@Parameter
	public int spline_grid_spacing;

	@Parameter(type = ItemIO.OUTPUT)
	public RegisterHelper rh;

	@Override
	public void run() {
		int nChannels = 1;

		if ((moving_image.getNChannels()>1)||(fixed_image.getNChannels()>1)) {
			if (fixed_image.getNChannels()== moving_image.getNChannels()) {
				nChannels = fixed_image.getNChannels();
			} else {
				System.out.println("Can't perform multichannel registration because the number of channel is not identical between moving and fixed image");
			}
		}


		rh = new RegisterHelper();
		rh.setMovingImage(moving_image);
		rh.setFixedImage(fixed_image);
		if (rigid) {
			RegistrationParameters[] rps = new RegistrationParameters[nChannels];
			for (int iCh = 0;iCh<nChannels;iCh++) {
				rps[iCh] = new RegParamRigid_Default();
			}
			//if (multiChannelRegistration) rp = RegistrationParameters.useAlphaMutualInformation(rp,nChannels);
			rh.addTransform(RegistrationParameters.combineRegistrationParameters(rps));
		}
		if (fast_affine) {
			RegistrationParameters[] rps = new RegistrationParameters[nChannels];
			for (int iCh = 0;iCh<nChannels;iCh++) {
				rps[iCh] = new RegParamAffine_Fast();
			}
			//if (multiChannelRegistration) rp = RegistrationParameters.useAlphaMutualInformation(rp,nChannels);
			rh.addTransform(RegistrationParameters.combineRegistrationParameters(rps));
		}
		if (affine) {
			RegistrationParameters[] rps = new RegistrationParameters[nChannels];
			for (int iCh = 0;iCh<nChannels;iCh++) {
				rps[iCh] = new RegParamAffine_Default();
			}
			//if (multiChannelRegistration) rp = RegistrationParameters.useAlphaMutualInformation(rp,nChannels);
			rh.addTransform(RegistrationParameters.combineRegistrationParameters(rps));
		}
		if (spline) {
			RegistrationParameters[] rps = new RegistrationParameters[nChannels];
			for (int iCh = 0;iCh<nChannels;iCh++) {
				rps[iCh] = new RegParamBSpline_Default();
			}
			RegistrationParameters rp = RegistrationParameters.combineRegistrationParameters(rps);
			//if (multiChannelRegistration) rp = RegistrationParameters.useAlphaMutualInformation(rp,nChannels);
			rp.FinalGridSpacingInVoxels = spline_grid_spacing;
			rh.addTransform(rp);
		}
		try {
			rh.align(new DefaultElastixTask());
			rh.to(RHZipFile.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
