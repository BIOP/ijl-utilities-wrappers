package ch.epfl.biop.wrappers.elastix;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;

import ch.epfl.biop.java.utilities.Converter;
import ch.epfl.biop.java.utilities.ConvertibleObject;

/**
 * Java class representation of Elastix registration parameters
 * Can be stored in a File according to Elastix specifications
 */

public class RegistrationParameters extends ConvertibleObject {
	public RegistrationParameters() {
		super();
	}
	
	// Q : and translation ?
	
	// Example parameter file for rotation registration
	// C-style comments: //

	// The internal pixel type, used for internal computations
	// Leave to float in general. 
	// NB: this is not the type of the input images! The pixel 
	// type of the input images is automatically read from the 
	// images themselves.
	// This setting can be changed to "short" to save some memory
	// in case of very large 3D images.
	@RegisterParam
	public String FixedInternalImagePixelType;//="float";
	
	@RegisterParam
	public String MovingInternalImagePixelType;//="float";
	
	// The dimensions of the fixed and moving image
	// NB: This has to be specified by the user. The dimension of
	// the images is currently NOT read from the images.
	// Also note that some other settings may have to specified
	// for each dimension separately
	@RegisterParam
	public Integer FixedImageDimension;// = 2;

    @RegisterParam
	public Integer MovingImageDimension;// = 2;
	
	// Specify whether you want to take into account the so-called
	// direction cosines of the images. Recommended: true.
	// In some cases, the direction cosines of the image are corrupt,
	// due to image format conversions for example. In that case, you 
	// may want to set this option to "false".
	@RegisterParam
	public Boolean UseDirectionCosines;// = true;
	
	// **************** Main Components **************************

	// The following components should usually be left as they are:
	@RegisterParam
	public String Registration;// = "MultiResolutionRegistration";
	@RegisterParam
	public String Interpolator;// = "BSplineInterpolator";
	@RegisterParam
	public String ResampleInterpolator;// = "FinalBSplineInterpolator";
	@RegisterParam
	public String Resampler;// = "DefaultResampler";
	
	// These may be changed to Fixed/MovingSmoothingImagePyramid.
	// See the manual.
	@RegisterParam
	public String FixedImagePyramid;// = "FixedSmoothingImagePyramid";
	@RegisterParam
	public String MovingImagePyramid;// = "MovingSmoothingImagePyramid";
	
	
	// The following components are most important:
	// The optimizer AdaptiveStochasticGradientDescent (ASGD) works
	// quite ok in general. The Transform and Metric are important
	// and need to be chosen careful for each application. See manual.
	@RegisterParam
	public String Optimizer;//  = "AdaptiveStochasticGradientDescent";
	@RegisterParam
	public String Transform;//  = "EulerTransform";
	@RegisterParam
	public String Metric;// = "AdvancedMattesMutualInformation";
	
	// Metric: "NormalizedMutualInformation"
	// Metric: "AdvancedMeanSquares"
	// Metric: "AdvancedMattesMutualInformation"
	
	// ***************** Transformation **************************

	// Scales the rotations compared to the translations, to make
	// sure they are in the same range. In general, it's best to  
	// use automatic scales estimation:
	@RegisterParam
	public Boolean AutomaticScalesEstimation;

	/**
	 * When the initial alignment between two images is very off, you cannot start a nonrigid registration. And
	 * 	sometimes it can be a hassle to get it right. What factors can help to get it right?
	 * 	- Start with a transformation with a low degree of freedom, i.e. the translation, rigid, similarity or affine
	 * 	transform. Sometimes the images are really far off, and have no overlap to begin with (NB: the position
	 * 	of images in physical space is determined by the origin and voxel spacing.
	 * 	 A solution is then to add the following line to your parameter file:
	 * 	 AutomaticTransformInitialization = true
	 * 	This parameter facilitates the automatic estimation of an initial alignment for the aforementioned
	 * 	transformations. Three methods to do so are supported: the default method which aligns the centres
	 * 	of the fixed and moving image, a method that aligns the centres of gravity, and a method that simply
	 * 	aligns the image origins. A method can be selected by adding one of the following lines to the parameter
	 * 	file: "GeometricalCenter", "CenterOfGravity", "Origins"
	 */
	@RegisterParam
	public Boolean AutomaticTransformInitialization=true;

	/**
	 * This parameter facilitates the automatic estimation of an initial alignment for the aforementionedtransformations.
	 * Three methods to do so are supported:
	 * the default method which aligns the centres of the fixed and moving image,
	 * a method that aligns the centres of gravity,
	 * and a method that simply aligns the image origins.
	 * A method can be selected by adding one of the following lines to the parameter file:
	 * ("GeometricalCenter")("Origins")("CenterOfGravity")
	 * Note that “Origins” is currently only available for the affine transformation
	 */
	@RegisterParam
	public String AutomaticTransformInitializationMethod = "CenterOfGravity";
	
	// Whether transforms are combined by composition or by addition.
	// In generally, Compose is the best option in most cases.
	// It does not influence the results very much.
	@RegisterParam
	public String HowToCombineTransforms;// = "Compose";
	
	// ******************* Similarity measure *********************

	// Number of grey level bins in each resolution level,
	// for the mutual information. 16 or 32 usually works fine.
	// You could also employ a hierarchical strategy:
	//(NumberOfHistogramBins 16 32 64)
	@RegisterParam
	public Integer[] NumberOfHistogramBins;//  = new int[] {64};

	// If you use a mask, this option is important. 
	// If the mask serves as region of interest, set it to false.
	// If the mask indicates which pixels are valid, then set it to true.
	// If you do not use a mask, the option doesn't matter.
	@RegisterParam
	public Boolean ErodeMask;// = false;
	
	// ******************** Multiresolution **********************

	// The number of resolutions. 1 Is only enough if the expected
	// deformations are small. 3 or 4 mostly works fine. For large
	// images and large deformations, 5 or 6 may even be useful.
	@RegisterParam
	public Integer NumberOfResolutions;// = 5;

	// The downsampling/blurring factors for the image pyramids.
	// By default, the images are downsampled by a factor of 2
	// compared to the next resolution.
	// So, in 2D, with 4 resolutions, the following schedule is used:
	//(ImagePyramidSchedule 8 8  4 4  2 2  1 1 )
	// And in 3D:
	//(ImagePyramidSchedule 8 8 8  4 4 4  2 2 2  1 1 1 )
	// You can specify any schedule, for example:
	//(ImagePyramidSchedule 4 4  4 3  2 1  1 1 )
	// Make sure that the number of elements equals the number
	// of resolutions times the image dimension.
	@RegisterParam
	public Integer[] ImagePyramidSchedule;//new int[]{4,4,2,2,1,1};
	
	// ******************* Optimizer ****************************

	// Maximum number of iterations in each resolution level:
	// 200-500 works usually fine for rigid registration.
	// For more robustness, you may increase this to 1000-2000.
	@RegisterParam
	public Integer MaximumNumberOfIterations;// = 250;

	// The step size of the optimizer, in mm. By default the voxel size is used.
	// which usually works well. In case of unusual high-resolution images
	// (eg histology) it is necessary to increase this value a bit, to the size
	// of the "smallest visible structure" in the image:
	//(MaximumStepLength 1.0)
	@RegisterParam
	public Float MaximumStepLength;// = 1.0f;
	

	// **************** Image sampling **********************

	// Number of spatial samples used to compute the mutual
	// information (and its derivative) in each iteration.
	// With an AdaptiveStochasticGradientDescent optimizer,
	// in combination with the two options below, around 2000
	// samples may already suffice.
	@RegisterParam
	public Integer NumberOfSpatialSamples;// = 2048*2;

	// Refresh these spatial samples in every iteration, and select
	// them randomly. See the manual for information on other sampling
	// strategies.
	@RegisterParam
	public Boolean NewSamplesEveryIteration;// = true;
	@RegisterParam
	public String ImageSampler;// = "Random";
	
	// ************* Interpolation and Resampling ****************

	// Order of B-Spline interpolation used during registration/optimisation.
	// It may improve accuracy if you set this to 3. Never use 0.
	// An order of 1 gives linear interpolation. This is in most 
	// applications a good choice.
	@RegisterParam
	public Integer BSplineInterpolationOrder;// = 1;

	// Order of B-Spline interpolation used for applying the final
	// deformation.
	// 3 gives good accuracy; recommended in most cases.
	// 1 gives worse accuracy (linear interpolation)
	// 0 gives worst accuracy, but is appropriate for binary images
	// (masks, segmentations); equivalent to nearest neighbor interpolation.
	@RegisterParam
	public Integer FinalBSplineInterpolationOrder;// = 3;

	//Default pixel value for pixels that come from outside the picture:
	@RegisterParam
	public Float DefaultPixelValue;// = 0f;

	// Choose whether to generate the deformed moving image.
	// You can save some time by setting this to false, if you are
	// only interested in the final (nonrigidly) deformed moving image
	// for example.
	@RegisterParam
	public Boolean WriteResultImage;// = false;//true;

	// The pixel type and format of the resulting deformed moving image
	@RegisterParam
	public String ResultImagePixelType = "float";
	//"unsigned short" "short" "float" "double"

	@RegisterParam
	public String ResultImageFormat = "tif";//mhd";
	
	@RegisterParam
	public boolean CompressResultImage = false; // lossless compression
	// ---------------- For BSpline Transform
	// The control point spacing of the bspline transformation in 
	// the finest resolution level. Can be specified for each 
	// dimension differently. Unit: mm.
	// The lower this value, the more flexible the deformation.
	// Low values may improve the accuracy, but may also cause
	// unrealistic deformations. This is a very important setting!
	// We recommend tuning it for every specific application. It is
	// difficult to come up with a good 'default' value.
	@RegisterParam
	public Integer FinalGridSpacingInPhysicalUnits;
		
	// Alternatively, the grid spacing can be specified in voxel units.
	// To do that, uncomment the following line and comment/remove
	// the FinalGridSpacingInPhysicalUnits definition.
	@RegisterParam
	public Integer FinalGridSpacingInVoxels;

	// By default the grid spacing is halved after every resolution,
	// such that the final grid spacing is obtained in the last 
	// resolution level. You can also specify your own schedule,
	// if you uncomment the following line:
	//int[] GridSpacingSchedule = new int[] {4.0,4.0,2.0,1.0}
	// This setting can also be supplied per dimension.
	@RegisterParam
	public Integer[] GridSpacingSchedule;

	@RegisterParam
	public Float Alpha;

	@RegisterParam
	public Float AvoidDivisionBy;

	@RegisterParam
	public String TreeType;

	@RegisterParam
	public Integer BucketSize;

	@RegisterParam
	public String SplittingRule;

	@RegisterParam
	public String ShrinkingRule;

	@RegisterParam
	public String TreeSearchType;

	@RegisterParam
	public Integer KNearestNeighbours;

	@RegisterParam
	public Float ErrorBound;

	static public boolean easyToWrite(Class<?> c) {
		boolean easy=false;
		easy |= c.equals(int.class);
		easy |= c.equals(Integer.class);
		easy |= c.equals(float.class);
		easy |= c.equals(Float.class);
		easy |= c.equals(double.class);
		easy |= c.equals(Double.class);
		easy |= c.equals(boolean.class);
		easy |= c.equals(Boolean.class);
		easy |= c.equals(String.class);
		return easy;
	}
		
	@Converter
	public static String toString(RegistrationParameters rp) {
		String output="";
		try {
			for (Field f:RegistrationParameters.class.getFields()) {
				if ((f.isAnnotationPresent(RegisterParam.class))&&(f.get(rp)!=null)) {
					output+="("+f.getName()+" ";
					if (easyToWrite(f.getType())) {
						output += f.get(rp);
					}
					if (f.getType()== float[].class) {
						float[] arrayParam = (float[]) f.get(rp);
                        for (float v : arrayParam) {
                            output += v + " ";
                        }
					}
					if (f.getType()== int[].class) {
						int[] arrayParam = (int[]) f.get(rp);
                        for (int j : arrayParam) {
                            output += j + " ";
                        }
					}
					if (f.getType()== Float[].class) {
						Float[] arrayParam = (Float[]) f.get(rp);
                        for (Float aFloat : arrayParam) {
                            output += aFloat + " ";
                        }
					}
					if (f.getType()== Integer[].class) {
						Integer[] arrayParam = (Integer[]) f.get(rp);
                        for (Integer integer : arrayParam) {
                            output += integer + " ";
                        }
					}
					output+=")\n";
				}
			}
		} catch (IllegalArgumentException | IllegalAccessException e) {
			e.printStackTrace();
		}
		return output;
	}
	
	@Converter
	public File toFile(RegistrationParameters rp) {
		BufferedWriter writer = null;
		try {
			File temp = File.createTempFile("rpa", ".txt");
			temp.deleteOnExit();
			writer = new BufferedWriter(new FileWriter(temp));
			writer.write(RegistrationParameters.toString(rp));
			writer.close();
			return temp;
		} catch (IOException e) {
	        e.printStackTrace();
	        return null;
	    } finally {
            try {
                // Close the writer regardless of what happens...
                writer.close();
            } catch (Exception e) {
				e.printStackTrace();
            }
        }
	}
	
	@Converter 
	public static RegistrationParameters downCast(RegParamRigid_Default rp) {
		return rp;
	}
	
	@Converter 
	public static RegistrationParameters downCast(RegParamAffine_Default rp) {
		return rp;
	}
	
	@Converter 
	public static RegistrationParameters downCast(RegParamTranslation_Default rp) {
		return rp;
	}
	
	@Converter 
	public static RegistrationParameters downCast(RegParamBSpline_Default rp) {
		return rp;
	}	
	
	@Converter 
	public static RegistrationParameters downCast(RegParamAffine_Fast rp) {
		return rp;
	}

	/**
	 * For multi channel images!!
	 * The α-mutual information metric computes true multi-channel α-mutual information. It does not use high-
		dimensional joint histograms, but instead relies on k-nearest neighbour graphs to estimate α-MI. Details can
		be found in Staring et al. [2009]. It is specified in the parameter file with:
		(Registration "MultiResolutionRegistrationWithFeatures")
		(FixedImagePyramid "FixedSmoothingImagePyramid" "FixedSmoothingImagePyramid")
		(MovingImagePyramid "MovingSmoothingImagePyramid" "MovingSmoothingImagePyramid")
		(Interpolator "BSplineInterpolator" "BSplineInterpolator")
		(Metric "KNNGraphAlphaMutualInformation")
		(ImageSampler "MultiInputRandomCoordinate")
		// KNN specific
		(Alpha 0.99)
		(AvoidDivisionBy 0.0000000001)
		(TreeType "KDTree")
		(BucketSize 50)
		(SplittingRule "ANN_KD_STD")
		(ShrinkingRule "ANN_BD_SIMPLE")
		(TreeSearchType "Standard")
		(KNearestNeighbours 20)
		(ErrorBound 10.0)
		A complete list of the available parameters can be found in the doxygen documentation →
		elx::KNNGraphAlphaMutualInformationMetric.
	 * @param nChannels number of channels in the image
	 * @return the edited registration parameters that supports multichannel registration
	 */
	public static RegistrationParameters useAlphaMutualInformation(RegistrationParameters rp, int nChannels) {
		rp.Registration = "MultiResolutionRegistrationWithFeatures";
		String fip = rp.FixedImagePyramid;
		for (int iCh=0;iCh<nChannels-1;iCh++) {
			rp.FixedImagePyramid = rp.FixedImagePyramid+" "+fip;
		}
		String mip = rp.MovingImagePyramid;
		for (int iCh=0;iCh<nChannels-1;iCh++) {
			rp.MovingImagePyramid = rp.MovingImagePyramid+" "+mip;
		}
		String interp = rp.Interpolator;
		for (int iCh=0;iCh<nChannels-1;iCh++) {
			rp.Interpolator = rp.Interpolator+" "+interp;
		}
		rp.Metric = "KNNGraphAlphaMutualInformation";
		rp.ImageSampler = "MultiInputRandomCoordinate";
		rp.Alpha = 0.99f;
		rp.AvoidDivisionBy=0.0000000001f;
		rp.TreeType="KDTree";
		rp.BucketSize=50;
		rp.SplittingRule="ANN_KD_STD";
		rp.ShrinkingRule="ANN_BD_SIMPLE";
		rp.TreeSearchType="Standard";
		rp.KNearestNeighbours=20;
		rp.ErrorBound=10.0f;
		return rp;
	}

	@RegisterParam
	Float Metric0Weight;

	@RegisterParam
	Float Metric1Weight;

	@RegisterParam
	Float Metric2Weight;

	@RegisterParam
	Float Metric3Weight;

	@RegisterParam
	Float Metric4Weight;

	@RegisterParam
	Float Metric5Weight;

	@RegisterParam
	Float Metric6Weight;

	/**
	 * (Registration "MultiMetricMultiResolutionRegistration")
	 * Other parts of the parameter file should look like:
	 * (FixedImagePyramid "FixedSmoothingImagePyramid" "FixedSmoothingImagePyramid" ...)
	 * (MovingImagePyramid "MovingSmoothingImagePyramid" "MovingSmoothingImagePyramid" ... )
	 * (Interpolator "BSplineInterpolator" "BSplineInterpolator" ...)
	 * (Metric "AdvancedMattesMutualInformation" "AdvancedMeanSquareDifference" ...)
	 * (ImageSampler "RandomCoordinate" "RandomCoordinate" ...)
	 * (Metric0Weight 0.125)
	 * (Metric1Weight 0.125)
	 * (Metric2Weight 0.125
	 * @param rps registration parameters to combine in a multichannel registration
	 * @return the combined registration
	 */
	public static RegistrationParameters combineRegistrationParameters(RegistrationParameters... rps) {
		int nChannels = rps.length;
		RegistrationParameters model = rps[0];
		model.Registration = "MultiMetricMultiResolutionRegistration";
		//double weight = 1.0/((double)nChannels);
		for (int iCh=1;iCh<nChannels;iCh++) {
			model.FixedImagePyramid = model.FixedImagePyramid+" "+rps[iCh].FixedImagePyramid;
			model.MovingImagePyramid = model.MovingImagePyramid+" "+rps[iCh].MovingImagePyramid;
			model.Interpolator = model.Interpolator+" "+rps[iCh].Interpolator;
			model.Metric = model.Metric+" "+rps[iCh].Metric;
			model.ImageSampler = model.ImageSampler+" "+rps[iCh].ImageSampler;
			// RAAAAAAAAAAAAAAAAAAAAAAAAHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH!!!!!
			switch (iCh) {
				case 0: model.Metric0Weight = 1f;break;
				case 1: model.Metric1Weight = 1f;break;
				case 2: model.Metric2Weight = 1f;break;
				case 3: model.Metric3Weight = 1f;break;
				case 4: model.Metric4Weight = 1f;break;
				case 5: model.Metric5Weight = 1f;break;
				case 6: model.Metric6Weight = 1f;break;
			}
		}

		return model;
	}
}
