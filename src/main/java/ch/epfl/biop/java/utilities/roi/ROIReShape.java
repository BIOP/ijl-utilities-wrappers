package ch.epfl.biop.java.utilities.roi;

import java.util.ArrayList;
import java.util.List;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.FloatPolygon;

public class ROIReShape {
	static public Roi reSample(Roi roiIn, float samplingDistance) {
		ArrayList<Float> xout = new ArrayList<>();
		ArrayList<Float> yout = new ArrayList<>();
		FloatPolygon pol = roiIn.getFloatPolygon();
		PathSampler ps = new PathSampler(pol.xpoints,pol.ypoints);
		ps.startSampling();
		while (ps.hasLength()) {
			float[] newPos = ps.getNextPtIncrementLenght(samplingDistance);
			xout.add(newPos[0]);
			yout.add(newPos[1]);
		}		
		PolygonRoi roiOut = new PolygonRoi(
				convertFloatArray(xout),convertFloatArray(yout),
				xout.size(), ij.gui.Roi.POLYGON);
		roiOut.setName(roiIn.getName());
		return roiOut;
	}
	
	static public Roi smoothen(Roi roiIn) {
		ArrayList<Float> xout = new ArrayList<>();
		ArrayList<Float> yout = new ArrayList<>();
		FloatPolygon pol = roiIn.getFloatPolygon();

		xout.add(pol.xpoints[0]);
		yout.add(pol.ypoints[0]);
		for (int i=1;i<pol.npoints-1;i++) {
			float x=pol.xpoints[i];
			float y=pol.ypoints[i];
			float xb = pol.xpoints[i-1];
			float xa = pol.xpoints[i+1];
			float yb = pol.ypoints[i-1];
			float ya = pol.ypoints[i+1];
			float dx=xa-xb;
			float dy=ya-yb;
			float ld = (float) java.lang.Math.sqrt(dx*dx+dy*dy);
			dx/=ld;
			dy/=ld;
			float proj=(x-xb)*dy-(y-yb)*dx;
			x+=-0.5f*proj*dy;
			y+=0.5f*proj*dx;
			xout.add(x);
			yout.add(y);
		}

		xout.add(pol.xpoints[pol.npoints-1]);
		yout.add(pol.ypoints[pol.npoints-1]);
		PolygonRoi roiOut = new PolygonRoi(
				convertFloatArray(xout),convertFloatArray(yout),
				xout.size(), ij.gui.Roi.POLYGON);
		roiOut.setName(roiIn.getName());
		return roiOut;
	}
	
	static public Roi smoothenWithConstrains(Roi roiIn, boolean[][] movablePx) {
		// Todo : better handling of edges
		ArrayList<Float> xout = new ArrayList<>();
		ArrayList<Float> yout = new ArrayList<>();
		FloatPolygon pol = roiIn.getFloatPolygon();

		//xout.add(pol.xpoints[0]);
		//yout.add(pol.ypoints[0]);
		//if (pol.npoints>=3) {
			for (int i=0;i<pol.npoints;i++) {
				int iBefore = i-1;
				if (iBefore<0) iBefore = pol.npoints-1;
				int iAfter = i+1;
				if (iAfter==pol.npoints) iAfter = 0;
				float x=pol.xpoints[i];
				float y=pol.ypoints[i];
				if (movablePx[(int) (x)][(int) (y)]) {
					float xb = pol.xpoints[iBefore];
					float xa = pol.xpoints[iAfter];
					float yb = pol.ypoints[iBefore];
					float ya = pol.ypoints[iAfter];
					float dx=xa-xb;
					float dy=ya-yb;
					float ld = (float) java.lang.Math.sqrt(dx*dx+dy*dy);
					dx/=ld;
					dy/=ld;
					float proj=(x-xb)*dy-(y-yb)*dx;
					x+=-0.5f*proj*dy;
					y+=0.5f*proj*dx;
				}
				xout.add(x);
				yout.add(y);
			}
		PolygonRoi roiOut = new PolygonRoi(
				convertFloatArray(xout),convertFloatArray(yout),
				xout.size(), ij.gui.Roi.POLYGON);
		roiOut.setName(roiIn.getName());
		return roiOut;
	}
	
	public static float[] convertFloatArray(List<Float> floatList) {
		float[] floatArray = new float[floatList.size()];
		int i = 0;
		for (Float f : floatList) {
		    floatArray[i++] = (f != null ? f : Float.NaN); 
		}
		return floatArray;
	}
		
}

class PathSampler {
	float[] xpts,ypts;
	public PathSampler(float[] xpts, float[] ypts) {
		this.xpts=xpts;
		this.ypts=ypts;
	}
	
	int currentPtIndex;
	float currentLengthInSegment;
	float currentSegmentLength;
	boolean done;
	float xi,yi,xc,yc,dxi,dyi;
	
	int previousIndex;
	public void startSampling() {
		loopDone=false;
		currentSegmentLength=0;
		currentLengthInSegment=0;
		previousIndex=0;
		currentPtIndex=0;
		xi=xpts[0];
		yi=ypts[0];
		xc=xi;
		yc=yi;
		
		if (xpts.length==1) {
			done=true;
		} else {
			dxi=xpts[1]-xpts[0];
			dyi=ypts[1]-ypts[0];
			currentPtIndex=1;
			currentSegmentLength= (float) java.lang.Math.sqrt(dxi*dxi+dyi*dyi);
			done = false;
		}
	}
	
	boolean loopDone=false;
	
	public float[] getNextPtIncrementLenght(float lengthIncrement) {
		if (done) {
			return new float[]{xpts[0],ypts[0]};
		} else {
			boolean newSampleFound=false;
			while ((!newSampleFound)&&(!done)) {
				if (currentLengthInSegment+lengthIncrement<currentSegmentLength) {
					// Still in the segment
					currentLengthInSegment+=lengthIncrement;
					float ratio = currentLengthInSegment/currentSegmentLength;
					xc=xi+ratio*dxi;
					yc=yi+ratio*dyi;
					newSampleFound=true;
				} else {
					currentLengthInSegment=
							currentLengthInSegment
							-currentSegmentLength;
					previousIndex=currentPtIndex;
					currentPtIndex++;
					if ((currentPtIndex==1)&&(loopDone==true)) {
						done=true;
						xc=xpts[0];
						yc=ypts[0];
					} else {
						if (currentPtIndex==xpts.length) {
							xi=xpts[previousIndex];
							yi=ypts[previousIndex];
							currentPtIndex=0;
							dxi=xpts[currentPtIndex]-xi;
							dyi=ypts[currentPtIndex]-yi;
							loopDone=true;
						} else {
							xi=xpts[previousIndex];
							yi=ypts[previousIndex];
							dxi=xpts[currentPtIndex]-xi;
							dyi=ypts[currentPtIndex]-yi;
						}
						currentSegmentLength= (float) java.lang.Math.sqrt(dxi*dxi+dyi*dyi);
					}
				}
			}
		}
		return new float[] {xc,yc};
	}
	
	public boolean hasLength() {
		return !done;
	}
	
}
