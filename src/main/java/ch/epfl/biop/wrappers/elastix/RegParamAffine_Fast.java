package ch.epfl.biop.wrappers.elastix;

public class RegParamAffine_Fast extends RegParamAffine_Default {
	public RegParamAffine_Fast() {
		super();
		NumberOfResolutions=2;
		ImagePyramidSchedule = new Integer[]{8,8,4,4};
		MaximumNumberOfIterations=1000;
	}

}
