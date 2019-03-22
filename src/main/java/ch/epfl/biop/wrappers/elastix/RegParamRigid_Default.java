package ch.epfl.biop.wrappers.elastix;

public class RegParamRigid_Default extends RegistrationParameters {
	public RegParamRigid_Default() {
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
		FixedImagePyramid="FixedRecursiveImagePyramid";
		MovingImagePyramid="MovingRecursiveImagePyramid";
		Optimizer="AdaptiveStochasticGradientDescent";
		Transform="EulerTransform";
		Metric="AdvancedMattesMutualInformation";
		AutomaticScalesEstimation=true;
		AutomaticTransformInitialization=true;
		HowToCombineTransforms="Compose";
		NumberOfHistogramBins= new Integer[] {32};
		ErodeMask=false;
		NumberOfResolutions=4;
		MaximumNumberOfIterations=250;
		NumberOfSpatialSamples=2048;
		NewSamplesEveryIteration=true;
		ImageSampler="Random";
		BSplineInterpolationOrder=1;
		FinalBSplineInterpolationOrder=1;
		DefaultPixelValue=0f;
		WriteResultImage=false;
		//ResultImagePixelType="float";
		//ResultImageFormat="tif";
	}
	
}
