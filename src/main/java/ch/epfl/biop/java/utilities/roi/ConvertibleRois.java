package ch.epfl.biop.java.utilities.roi;

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
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.svg.SVGDocument;

import ch.epfl.biop.java.utilities.Converter;
import ch.epfl.biop.java.utilities.ConvertibleObject;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;

/**
 * Converters for various forms of ROI
 * ArrayList<Roi>
 * Zip file of rois from IJ1 (ImageJRoisFile)
 * SVG File (SVGRoisFormat)
 * URL (assert svg file...)
 * Transformix File Format
 * ROIManager
 * ImagePlus label Image
 */

public class ConvertibleRois extends ConvertibleObject{
	
	@Converter
	public static ImageJRoisFile arrayToImageJFileFormat(ArrayList<Roi> rois) {
		HashMap<String,Integer> avoidDuplicates = new HashMap<>();
		try {
			// copied from https://github.com/imagej/imagej1/blob/master/ij/plugin/frame/RoiManager.java
			File temp = File.createTempFile("trois", ".zip");
			temp.deleteOnExit();
			DataOutputStream out = null;
			ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
			out = new DataOutputStream(new BufferedOutputStream(zos));
			RoiEncoder re = new RoiEncoder(out);
			for (Roi roi: rois) {
				if (roi==null) continue;
				String label = roi.getName();
				if (avoidDuplicates.containsKey(label)) {
					avoidDuplicates.put(label,avoidDuplicates.get(label)+1);
					label=label+"-"+avoidDuplicates.get(label);
				} else {
					avoidDuplicates.put(label,0);
				}
				//String label = getUniqueName(names, indexes[i]);
				zos.putNextEntry(new ZipEntry(label+".roi"));
				re.write(roi);
				out.flush();
			}
			out.close();	
			return new ImageJRoisFile(temp);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Converter
	public static ArrayList<Roi> imageJFileFormatToArray(ImageJRoisFile ijf) {
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
			return list;
		} catch (IOException e) {
			return null;
		} finally {
			if (in!=null)
				try {in.close();} catch (IOException e) {}
			if (out!=null)
				try {out.close();} catch (IOException e) {}
		}
	}
	
	@Converter
	public static ArrayList<Roi> svgFileFormatToArray(SVGRoisFormat svg) {
		SVGDocument svgDoc = Svg2Roi.getSVGDocumentFromFilePath(svg.f.getAbsolutePath());
		return Svg2Roi.getRoisFromSVGNodeList(svgDoc.getElementsByTagName("path"), svg.sampleLength*svg.downscaleFactor, svg.downscaleFactor);
	}
	
	@Converter
	public static SVGRoisFormat urlToSvgFileFormat(URL url) {
		try {
			File temp = File.createTempFile("timg", ".svg");
	        temp.deleteOnExit();
	        FileUtils.copyURLToFile(url, temp, 10000, 10000);
	        SVGRoisFormat svgff = new SVGRoisFormat(temp);
	        return svgff;
		} catch (IOException e) {
            e.printStackTrace();
            return null;
        }
	}
	
	@Converter
	public static TransformixInputRoisFile arrayToElastixFileFormat(ArrayList<Roi> rois) {
		BufferedWriter writer = null;
		try {
			File temp = File.createTempFile("tpts", ".txt");
	        //temp.deleteOnExit();		
			TransformixInputRoisFile erf = new TransformixInputRoisFile(temp);
			int nPts = 0;
			for (Roi roi: rois) {
				FloatPolygon pol = roi.getFloatPolygon();
				nPts+=pol.npoints;
				/*for (int i=0;i<pol.npoints;i++) {
					//pol.xpoints[i];
					//pol.ypoints[i];
				}*/
			}
			writer = new BufferedWriter(new FileWriter(temp));
            writer.write("point");
            writer.newLine();
            writer.write(Integer.toString(nPts));
            writer.newLine();
			for (Roi roi: rois) {
				FloatPolygon pol = roi.getFloatPolygon();
				//nPts+=pol.npoints;
				for (int i=0;i<pol.npoints;i++) {
					writer.write(Float.toString(pol.xpoints[i]));
					writer.write("\t");
					writer.write(Float.toString(pol.ypoints[i]));
					writer.newLine();
				}
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
            }
        }
	}

	ArrayList<Roi> local;
	
	public void setInitialArrayList(ArrayList<Roi> aIni) {
		this.local=aIni;
	}

	@Converter
	public static ArrayList<Roi> roiManagerToArray(RoiManager rm) {
		ArrayList<Roi> rois = new ArrayList<>();
		Roi[] roisArray = rm.getRoisAsArray();
		for (int i=0;i<roisArray.length;i++) {
			rois.add(roisArray[i]);
		}
        return rois;
	}
	
	@Converter
	public static RoiManager arrayToRoiManager(ArrayList<Roi> rois) {
		System.out.println("arrayToRoiManager called");
        RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
            roiManager = new RoiManager();
        }
        roiManager.reset();
        for (int i = 0; i < rois.size(); i++) {
            roiManager.addRoi(rois.get(i));
        }
        return roiManager;
	}

	// Non static because we loose information of rois in elastix format
	@Converter
	public ArrayList<Roi> elastixFileFormatToArray(TransformixOutputRoisFile erf) {
		//ArrayList<Roi> local = (ArrayList<Roi>) this.to(ArrayList.class);
		BufferedReader reader = null;
		ArrayList<Roi> out = new ArrayList<>();
		if (local==null) {
			return null;
		} else {
			try {
				reader = new BufferedReader(new FileReader(erf.f));
				//reader.readLine(); // skips points
				//reader.readLine(); // skips number of points
				for (Roi roi: local) {
					FloatPolygon pol = roi.getFloatPolygon();
					//Point	0	; InputIndex = [ 6335 7530 ]	; InputPoint = [ 6335.000000 7530.000000 ]	; OutputIndexFixed = [ 6270 7520 ]	; OutputPoint = [ 6270.019712 7520.199309 ]	; Deformation = [ -64.980286 -9.800692 ]
			        String line = null;
			        String[] parts = null;
			        String part[] = null;
			        float x,y;
			        float[] xout = new float[pol.npoints];
			        float[] yout = new float[pol.npoints];
					for (int i=0;i<pol.npoints;i++) {
						line  = reader.readLine();
						parts = line.split(";");//\\d\\s+");
						part = parts[4].split("[\\s]");
						
						//System.out.println(part[4]+"\t"+part[5]);
						
				        x = Float.valueOf(part[4].trim());
				        y = Float.valueOf(part[5].trim());
						
				        pol.xpoints[i]=x;
				        pol.ypoints[i]=y;
				        xout[i]=x;
				        yout[i]=y;
					}

					PolygonRoi roiOut = new PolygonRoi(xout, yout, xout.length, ij.gui.Roi.POLYGON );
					roiOut.setStrokeColor(roi.getStrokeColor());
					roiOut.setName(roi.getName());
					//roiOut.setStrokeColor(roi.getStrokeColor());
					out.add(roiOut);
					
				}
				
				reader.close();
				
				return out;//local;
			} catch (IOException e) {
		        e.printStackTrace();
		        return null;
		    } finally {
	            try {
	                // Close the writer regardless of what happens...
	                reader.close();
	            } catch (Exception e) {
	            }
	        }
		}
	}

	// Converts to label image
	@Converter
	public ImagePlus roiArrayToLabelImage(ArrayList<Roi> rois) {
		// Looks for min and max
		float xmin=0;
		float xmax=100;
		float ymin=0;
		float ymax=100;
		for (Roi roi:rois) {
			if (roi.getBounds().x<xmin) xmin=roi.getBounds().x;
			if (roi.getBounds().y<ymin) ymin=roi.getBounds().y;
			if (roi.getBounds().x+roi.getBounds().width>xmax) xmax=roi.getBounds().x+roi.getBounds().width;
			if (roi.getBounds().y+roi.getBounds().height>ymax) ymax=roi.getBounds().y+roi.getBounds().height;
		}
		ImagePlus imp = IJ.createImage("Labels_"+this.toString(),"16-bit black", (int)xmax,(int)ymax,1);
		//ImagePlus imp = new ImagePlus("Labels_"+this.toString())
		ImageProcessor ip = imp.getProcessor();
		int roiIndex=0;
		for (Roi roi:rois) {
			roiIndex=roiIndex+1;
			imp.setRoi(roi);
			ip.setColor(roiIndex);
			ip.fill(roi);
			roiIndex=roiIndex+1;
		}
		return imp;
	}
	
	static public ArrayList<Roi> labelImageToRoiArrayVectorize(ImagePlus imp) {

		
		// Finds all tricolored pixels in imp

		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();

		// Converts data in case thats a RGB Image	
		FloatProcessor fp = new FloatProcessor(ip.getWidth(), ip.getHeight());	
		fp.setFloatArray(pixels);
		//ImagePlus imgFloatCopy = new ImagePlus("FloatLabel",fp);
		
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
		//SelectToROIKeepLines.splitable = movablePx;
		ArrayList<Roi> output = labelImageToRoiArray(imp);
		output = convertRoisToPolygonRois(output);
		output.replaceAll(roi -> ROIReShape.smoothenWithConstrains(roi, movablePx));
		output.replaceAll(roi -> ROIReShape.smoothenWithConstrains(roi, movablePx));
		//output.replaceAll(roi -> ROIReShape.smoothenWithConstrains(roi, movablePx));
		//output.replaceAll(roi -> ROIReShape.smoothenWithConstrains(roi, movablePx));
		return output;
	}

	@Converter
	public static ArrayList<Roi> labelImageToRoiArray(ImagePlus imp) {
		ArrayList<Roi> roiArray = new ArrayList<>();
		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();
		
		HashSet<Float> existingPixelValues = new HashSet<>();

		for (int x=0;x<ip.getWidth();x++) {
			for (int y=0;y<ip.getHeight();y++) {
				existingPixelValues.add((pixels[x][y]));
			}
		}
		
		// Converts data in case thats a RGB Image	
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
		return roiArray;
	}
	
	public static ArrayList<Roi> convertRoisToPolygonRois(ArrayList<Roi> arrayIn) {
		ArrayList<Roi> arrayOut = new ArrayList<>();
		arrayIn.forEach(roi -> {
			if (roi.getClass()==ShapeRoi.class) {
				ArrayList<Roi> list = new ArrayList<Roi>(Arrays.asList(((ShapeRoi)roi).getRois()));
				list.forEach(r -> r.setName(roi.getName()));
				arrayOut.addAll(list);
			} else {
				arrayOut.add(roi);
			}
		});
		return arrayOut;
	}

}

