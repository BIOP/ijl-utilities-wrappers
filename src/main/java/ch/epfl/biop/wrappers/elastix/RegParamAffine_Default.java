package ch.epfl.biop.wrappers.elastix;

public class RegParamAffine_Default extends RegistrationParameters {
	/**
	 * Default values for an affine registration
	 */
	public RegParamAffine_Default() {
		super();
		FixedInternalImagePixelType="float";
		MovingInternalImagePixelType="float";
		FixedImageDimension=2;
		MovingImageDimension=2;
		UseDirectionCosines=true;
		Registration="MultiResolutionRegistration";
		Interpolator="BSplineInterpolator";
		ResampleInterpolator="FinalBSplineInterpolator";
		Resampler="DefaultResampler";
		
		FixedImagePyramid="FixedSmoothingImagePyramid";
		MovingImagePyramid="MovingRecursiveImagePyramid"; // try smoothing
		
		Optimizer="AdaptiveStochasticGradientDescent";
		Transform="AffineTransform";
		Metric="AdvancedMattesMutualInformation";
		
		AutomaticScalesEstimation=true;
		AutomaticTransformInitialization=true;
		HowToCombineTransforms="Compose";
		
		NumberOfHistogramBins= new Integer[] {32};
		ErodeMask=false;
		NumberOfResolutions=6;
		MaximumNumberOfIterations=500;
		NumberOfSpatialSamples=2048;
		NewSamplesEveryIteration=true;
		ImageSampler="Random";
		
		BSplineInterpolationOrder=1;
		FinalBSplineInterpolationOrder=3;
		DefaultPixelValue=0f;
		WriteResultImage=false;
		ResultImagePixelType="short";
		ResultImageFormat="tif";
	}
}
