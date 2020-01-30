package ch.epfl.biop.wrappers.elastix;

public class RegParamBSpline_Default extends RegistrationParameters {
	
	public RegParamBSpline_Default() {
		super();
		this.AutomaticTransformInitialization=false;
		this.AutomaticScalesEstimation=false;
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
		MovingImagePyramid="MovingSmoothingImagePyramid"; // try smoothing
		
		Optimizer="AdaptiveStochasticGradientDescent";
		Transform="BSplineTransform";//"RecursiveBSplineTransform";
		//Metric="NormalizedMutualInformation";
		Metric="AdvancedMattesMutualInformation";



		FinalGridSpacingInVoxels=2;//20;
		//AutomaticScalesEstimation=true;
		//AutomaticTransformInitialization=true;
		HowToCombineTransforms="Compose";
		
		NumberOfHistogramBins= new Integer[] {128};
		ErodeMask=false;
		NumberOfResolutions=6;
		MaximumNumberOfIterations=1000;
		
		//MaximumStepLength=6.0f;
		
		NumberOfSpatialSamples=4096;
		NewSamplesEveryIteration=true;
		ImageSampler="Random";
		
		BSplineInterpolationOrder=1;
		FinalBSplineInterpolationOrder=3;
		DefaultPixelValue=0f;
		WriteResultImage=false;
		//ResultImagePixelType="short";
		//ResultImageFormat="tif";
	}

}
