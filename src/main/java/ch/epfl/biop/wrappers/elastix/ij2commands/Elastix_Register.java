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

	@Parameter
	public int splineGridSpacing;

	@Parameter(type = ItemIO.OUTPUT)
	public RegisterHelper rh;

	@Override
	public void run() {
		int nChannels = 1;

		if ((movingImage.getNChannels()>1)||(fixedImage.getNChannels()>1)) {
			if (fixedImage.getNChannels()==movingImage.getNChannels()) {
				nChannels = fixedImage.getNChannels();
			} else {
				System.out.println("Can't perform multichannel registration because the number of channel is not identical between moving and fixed image");
			}
		}


		rh = new RegisterHelper();
		rh.setMovingImage(movingImage);
		rh.setFixedImage(fixedImage);
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
			rp.FinalGridSpacingInVoxels = splineGridSpacing;
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
