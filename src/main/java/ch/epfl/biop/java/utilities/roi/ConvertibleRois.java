package ch.epfl.biop.java.utilities.roi;

import java.awt.geom.Point2D;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ch.epfl.biop.java.utilities.roi.types.*;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ImageProcessor;
import net.imglib2.RealPoint;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.svg.SVGDocument;

import ch.epfl.biop.java.utilities.Converter;
import ch.epfl.biop.java.utilities.ConvertibleObject;
import ij.gui.Roi;
//import ij.gui.ShapeRoi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor;

/**
 * Converters for various ROI classes definition
 * - IJShapeRoiArray (wrapping of a ShapeRoi ArrayList)
 * - RealPointList
 * - ImageJRoisFile (Zip file of rois from IJ1)
 * - SVGFile (SVGRoisFormat)
 * - SVGURL (svg url file or remote...)
 * - TransformixInputRoisFile
 * - TransformixOutputRoisFile
 * - RoiManager
 * - ImagePlus (label image)
 * <p>
 * Some format just contain a list of points :
 * - RealPointList
 * - TransformixInput
 * - TransformixOutput
 * <p>
 * While other contain a lot of information (how points are connected, multiple paths):
 * - SVGURL
 * - IJShapeRoiArray
 * - SVGRoisFormat
 * - ROIManager
 * - SVGURL
 * <p>
 * One is very specfic because it stores a mask image:
 * - ImageLabel
 * <p>
 * How to deal with connectivity loss during conversion ?
 * 	- Every class without connectivity info (RealPointList, TransformixInput, TransformixOutput) holds a reference to an IJShapeRoiArray, which has to be passed
 * 	for every converter
 * 		- This means that the number of points cannot be modified when ROI are under this type of class
 * <p>
 *
 * 	// Unsupported now :
 * 	- Shapes that are not only polygon in svg files are not well handled TODO SVG to ROI Proper CONVERSION
 *
 */

@SuppressWarnings({"unused"})
public class ConvertibleRois extends ConvertibleObject{

	@Converter
	public static ImageJRoisFile arrayToImageJFileFormat(IJShapeRoiArray rois) {
		HashMap<String,Integer> avoidDuplicates = new HashMap<>();
		try {
			// copied from https://github.com/imagej/imagej1/blob/master/ij/plugin/frame/RoiManager.java
			File temp = File.createTempFile("trois", ".zip");
			temp.deleteOnExit();
			DataOutputStream out;
			ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
			out = new DataOutputStream(new BufferedOutputStream(zos));
			RoiEncoder re = new RoiEncoder(out);
			rois.rois.stream().map(CompositeFloatPoly::getRoi).forEach(roi -> {
				if (roi!=null) {
					String label = roi.getName();
					if (avoidDuplicates.containsKey(label)) {
						avoidDuplicates.put(label, avoidDuplicates.get(label) + 1);
						label = label + "-" + avoidDuplicates.get(label);
					} else {
						avoidDuplicates.put(label, 0);
					}
					try {
						//String label = getUniqueName(names, indexes[i]);
						zos.putNextEntry(new ZipEntry(label + ".roi"));
						re.write(roi);
						out.flush();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
			out.close();	
			return new ImageJRoisFile(temp);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Converter
	public static IJShapeRoiArray imageJFileFormatToArray(ImageJRoisFile ijf) {
		ImagePlus imp;
		ArrayList<Roi> list = new ArrayList<>();
		ZipInputStream in = null; 
		ByteArrayOutputStream out = null; 
		try { 
			in = new ZipInputStream(new FileInputStream(ijf.f)); 
			byte[] buf = new byte[1024]; 
			int len; 
			ZipEntry entry = in.getNextEntry(); 
			while (entry!=null) { 
				String name = entry.getName();
				if (name.endsWith(".roi")) { 
					out = new ByteArrayOutputStream(); 
					while ((len = in.read(buf)) > 0) 
						out.write(buf, 0, len); 
					out.close(); 
					byte[] bytes = out.toByteArray(); 
					RoiDecoder rd = new RoiDecoder(bytes, name); 
					Roi roi = rd.getRoi(); 
					if (roi!=null) { 
						list.add(roi);
					} 
				} 
				entry = in.getNextEntry(); 
			} 
			in.close(); 
			return new IJShapeRoiArray(list);
		} catch (IOException e) {
			return null;
		} finally {
			if (in!=null)
				try {in.close();} catch (IOException e) {e.printStackTrace();}
			if (out!=null)
				try {out.close();} catch (IOException e) {e.printStackTrace();}
		}
	}
	
	@Converter
	public static IJShapeRoiArray svgFileFormatToArray(SVGRoisFormat svg) {
		SVGDocument svgDoc = Svg2Roi.getSVGDocumentFromFilePath(svg.f.getAbsolutePath());
		return new IJShapeRoiArray(Svg2Roi.getRoisFromSVGNodeList(svgDoc.getElementsByTagName("path"), svg.sampleLength*svg.downscaleFactor, svg.downscaleFactor));
	}
	
	@Converter
	public static SVGRoisFormat urlToSvgFileFormat(SVGURL svgurl) {
		try {
			File temp = File.createTempFile("timg", ".svg");
	        temp.deleteOnExit();
	        FileUtils.copyURLToFile(svgurl.url, temp, 10000, 10000);
            return new SVGRoisFormat(temp);
		} catch (IOException e) {
            e.printStackTrace();
            return null;
        }
	}
	
	@Converter
	public static TransformixInputRoisFile arrayToElastixFileFormat(IJShapeRoiArray rois) {
		BufferedWriter writer = null;
		try {
			File temp = File.createTempFile("tpts", ".txt");
	        //temp.deleteOnExit();		
			TransformixInputRoisFile erf = new TransformixInputRoisFile(temp);
			erf.shapeRoiList = rois;
			List<Point2D> ctrlPts = rois.getPoints();
			int nPts = ctrlPts.size();
			writer = new BufferedWriter(new FileWriter(temp));
            writer.write("point");
            writer.newLine();
            writer.write(Integer.toString(nPts));
            writer.newLine();
            for (Point2D pt: ctrlPts) {
                writer.write(Double.toString(pt.getX()));
                writer.write("\t");
                writer.write(Double.toString(pt.getY()));
                writer.newLine();
            }
			writer.close();
			return erf;
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
	public static IJShapeRoiArray roiManagerToArray(RoiManager rm) {
		ArrayList<Roi> rois = new ArrayList<>();
		Roi[] roisArray = rm.getRoisAsArray();
        Collections.addAll(rois, roisArray);
        return new IJShapeRoiArray(rois);
	}

	@Converter
	public RealPointList roiArrayToRealPointList(IJShapeRoiArray rois) {
		List<RealPoint> out = new ArrayList<>();

		for (Point2D pt:rois.getPoints()) {
		    out.add(new RealPoint(pt.getX(), pt.getY()));
        }

		RealPointList rpl = new RealPointList(out);
		rpl.shapeRoiList = rois; // keeps connectivity only ! do not use coordinates from this ref!

		return rpl;
	}

	@Converter
	public IJShapeRoiArray realPointListToRoiArray(RealPointList in) {
		IJShapeRoiArray out = new IJShapeRoiArray(in.shapeRoiList);
		out.setPoints(in.ptList.stream().map(rp ->
		    new Point2D.Double(rp.getDoublePosition(0), rp.getDoublePosition(1))
        ).collect(Collectors.toList()));
		return out;
	}
	
	@Converter
	public static RoiManager arrayToRoiManager(IJShapeRoiArray rois) {
		//System.out.println("arrayToRoiManager called");
        RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
            roiManager = new RoiManager();
        }
        roiManager.reset();
        for (int i = 0; i < rois.rois.size(); i++) {
            roiManager.addRoi(rois.rois.get(i).getRoi());
        }
        return roiManager;
	}

	@Converter
	public static IJShapeRoiArray elastixFileFormatToArray(TransformixOutputRoisFile erf) {
		BufferedReader reader = null;
			try {
                IJShapeRoiArray out = new IJShapeRoiArray(erf.shapeRoiList);
				reader = new BufferedReader(new FileReader(erf.f));
				String line;
                String[] parts;
                String[] part;
                ArrayList<Point2D> ptList = new ArrayList<>();
				while ((line  = reader.readLine())!=null) {
                    parts = line.split(";");//\\d\\s+");
                    part = parts[4].split("[\\s]");
                    double x = Double.parseDouble(part[4].trim());
                    double y = Double.parseDouble(part[5].trim());
                    ptList.add(new Point2D.Double(x,y));
				}
				out.setPoints(ptList);
				reader.close();
				return out;
			} catch (IOException e) {
		        e.printStackTrace();
		        return null;
		    } finally {
	            try {
	                // Close the writer regardless of what happens...
	                reader.close();
	            } catch (Exception e) {
					e.printStackTrace();
	            }
	        }
	}

	// Converts to label image
	@Converter
	public ImagePlus roiArrayToLabelImage(IJShapeRoiArray rois) {
		// Looks for min and max
		float xmin=0;
		float xmax=100;
		float ymin=0;
		float ymax=100;
		for (CompositeFloatPoly cfp:rois.rois) {
		    Roi roi = cfp.getRoi();
			if (roi.getBounds().x<xmin) xmin=roi.getBounds().x;
			if (roi.getBounds().y<ymin) ymin=roi.getBounds().y;
			if (roi.getBounds().x+roi.getBounds().width>xmax) xmax=roi.getBounds().x+roi.getBounds().width;
			if (roi.getBounds().y+roi.getBounds().height>ymax) ymax=roi.getBounds().y+roi.getBounds().height;
		}
		ImagePlus imp = IJ.createImage("Labels_"+this,"16-bit black", (int)xmax,(int)ymax,1);
		//ImagePlus imp = new ImagePlus("Labels_"+this.toString())
		ImageProcessor ip = imp.getProcessor();
		int roiIndex=0;
        for (CompositeFloatPoly cfp:rois.rois) {
            Roi roi = cfp.getRoi();
			roiIndex=roiIndex+1;
			imp.setRoi(roi);
			ip.setColor(roiIndex);
			ip.fill(roi);
			roiIndex=roiIndex+1;
		}
		return imp;
	}

	/**
	 * Tries to do something clever...
	 * @param imp
	 * @return
	 */
	static public IJShapeRoiArray labelImageToRoiArrayVectorize(ImagePlus imp) {
		// Finds all tricolored pixels in imp
		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();

		// Converts data in case that's a RGB Image
		FloatProcessor fp = new FloatProcessor(ip.getWidth(), ip.getHeight());	
		fp.setFloatArray(pixels);

		ImagePlus imgFloatCopy = new ImagePlus("FloatLabel",fp);
		
		boolean[][] movablePx = new boolean[ip.getWidth()+1][ip.getHeight()+1];
		for (int x=1;x<ip.getWidth();x++) {
			for (int y=1;y<ip.getHeight();y++) {
				boolean is3Colored = false;
				boolean isCrossed = false;
				float p1p1 = pixels[x][y];
				float p1m1 = pixels[x][y-1];
				float m1p1 = pixels[x-1][y];
				float m1m1 = pixels[x-1][y-1];
				float min = p1p1;
				if (p1m1<min) min = p1m1;
				if (m1p1<min) min = m1p1;
				if (m1m1<min) min = m1m1;
				float max = p1p1;
				if (p1m1>max) max = p1m1;
				if (m1p1>max) max = m1p1;
				if (m1m1>max) max = m1m1;
				if (min!=max) {
					if ((p1p1!=min)&&(p1p1!=max)) is3Colored=true; 
					if ((m1p1!=min)&&(m1p1!=max)) is3Colored=true; 
					if ((p1m1!=min)&&(p1m1!=max)) is3Colored=true; 
					if ((m1m1!=min)&&(m1m1!=max)) is3Colored=true;
					
					if (!is3Colored) {
						if ((p1p1==m1m1)&&(p1m1==m1p1)) {
							isCrossed=true;
						}
					}
				} // if not it's monocolored
				movablePx[x][y]=(!is3Colored)&&(!isCrossed);
			}
		}

		ArrayList<Roi> roiArray = new ArrayList<>();

		HashSet<Float> existingPixelValues = new HashSet<>();

		for (int x=0;x<ip.getWidth();x++) {
			for (int y=0;y<ip.getHeight();y++) {
				existingPixelValues.add((pixels[x][y]));
			}
		}

		existingPixelValues.forEach(v -> {
			fp.setThreshold( v,v,ImageProcessor.NO_LUT_UPDATE);
			Roi roi = SelectToROIKeepLines.run(imgFloatCopy,movablePx,true);//ThresholdToSelection.run(imgFloatCopy);
			roi.setName(Integer.toString((int) (double) v));
			roiArray.add(roi);
		});

		IJShapeRoiArray output = new IJShapeRoiArray(roiArray);
		output.smoothenWithConstrains(movablePx);
		return output;
	}

	@Converter
	public static IJShapeRoiArray labelImageToRoiArrayKeepSinglePixelPrecision(ImagePlus imp) {
		ArrayList<Roi> roiArray = new ArrayList<>();
		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();
		
		HashSet<Float> existingPixelValues = new HashSet<>();

		for (int x=0;x<ip.getWidth();x++) {
			for (int y=0;y<ip.getHeight();y++) {
				existingPixelValues.add((pixels[x][y]));
			}
		}
		
		// Converts data in case that's a RGB Image
		FloatProcessor fp = new FloatProcessor(ip.getWidth(), ip.getHeight());	
		fp.setFloatArray(pixels);
		ImagePlus imgFloatCopy = new ImagePlus("FloatLabel",fp);
		//SelectToROIKeepLines.filterMergable=true;
		existingPixelValues.forEach(v -> {
			fp.setThreshold( v,v,ImageProcessor.NO_LUT_UPDATE);
			Roi roi = SelectToROIKeepLines.run(imgFloatCopy,null,false);//ThresholdToSelection.run(imgFloatCopy);
			roi.setName(Integer.toString((int) (double) v));
			roiArray.add(roi);
		});
		return new IJShapeRoiArray(roiArray);
	}

	@Converter
	public static IJShapeRoiArray labelImageToRoiArray(ImagePlus imp) {
		ArrayList<Roi> roiArray = new ArrayList<>();
		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();

		HashSet<Float> existingPixelValues = new HashSet<>();

		for (int x=0;x<ip.getWidth();x++) {
			for (int y=0;y<ip.getHeight();y++) {
				existingPixelValues.add((pixels[x][y]));
			}
		}

		// Converts data in case that's a RGB Image
		FloatProcessor fp = new FloatProcessor(ip.getWidth(), ip.getHeight());
		fp.setFloatArray(pixels);
		ImagePlus imgFloatCopy = new ImagePlus("FloatLabel",fp);
		//SelectToROIKeepLines.filterMergable=true;
		existingPixelValues.forEach(v -> {
			fp.setThreshold( v,v,ImageProcessor.NO_LUT_UPDATE);
			Roi roi = ThresholdToSelection.run(imgFloatCopy);
			roi.setName(Integer.toString((int) (double) v));
			roiArray.add(roi);
		});
		return new IJShapeRoiArray(roiArray);
	}


	@Converter
	public static TransformixInputRoisFile realPointListToTransformixFile(RealPointList rpl) {
		BufferedWriter writer = null;
		try {
			File temp = File.createTempFile("tpts", ".txt");
			temp.deleteOnExit();
			TransformixInputRoisFile erf = new TransformixInputRoisFile(temp);
			writer = new BufferedWriter(new FileWriter(temp));
			writer.write("point");
			writer.newLine();
			writer.write(Integer.toString(rpl.ptList.size()));
			writer.newLine();

			for (RealPoint pt:rpl.ptList) {
				for (int i = 0; i <pt.numDimensions();i++) {
					writer.write(Double.toString(pt.getDoublePosition(i)));
					if (i != pt.numDimensions()-1) {
						writer.write("\t");
					}
				}
				writer.newLine();
			}
			writer.close();
			erf.shapeRoiList = rpl.shapeRoiList; // keeps connectivity info
			return erf;
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
	public static RealPointList transformixFileToRealPointList(TransformixOutputRoisFile erf) {

		//Point	0	; InputIndex = [ 6335 7530 ]	; InputPoint = [ 6335.000000 7530.000000 ]	; OutputIndexFixed = [ 6270 7520 ]	; OutputPoint = [ 6270.019712 7520.199309 ]	; Deformation = [ -64.980286 -9.800692 ]
		BufferedReader reader = null;
		{
			try {
				reader = new BufferedReader(new FileReader(erf.f));
				List<RealPoint> out = new ArrayList<>();String line;
				String[] parts;
				String[] part;
				while ((line = reader.readLine())!=null) {
					parts = line.split(";");//\\d\\s+");
					part = parts[4].split("[\\s]");
					int nDim = 2;//part.length-4; to solve when todoing 3d
					//RealPoint rp = new RealPoint();
					double[] coords = new double [nDim];
					for (int d=0;d<nDim;d++) {
						coords[d]=Double.parseDouble(part[4+d].trim());
					}
					RealPoint rp = new RealPoint(coords);
					//rp.setPosition(coords);
					out.add(rp);
				}
				reader.close();
				RealPointList outrpl = new RealPointList(out);
				outrpl.shapeRoiList = erf.shapeRoiList; // keep connectivity info
				return outrpl;
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			} finally {
				try {
					// Close the reader regardless of what happens...
					reader.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

}

