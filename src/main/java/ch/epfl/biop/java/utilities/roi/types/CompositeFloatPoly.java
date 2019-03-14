package ch.epfl.biop.java.utilities.roi.types;

import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;

import java.awt.*;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.util.*;

import java.awt.geom.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Class representing an AREA, a list of floatpolygon
 * depending on the polygon orientation (cw or ccw), the polygons are recombined
 * when the function getRoi() is called
 *  PointROI or Line ROI  (non area) are only supported if there are not part of a ShapeROI
 *  calibration unsupported
 *  curves unsupported
 *
 *  the control points do not include any relative positioning  ? to check, maybe basex and base y are important
 *
 * The list of floatpolygon is generated from the constructor, which depending on the ROI class:
 * - if it is something else than ShapeROI, then the ROI is stored as is
 * - if it is a ShapeROI, it is splitted into several float polygons
 *  - the splitting functions (getiterator) are copied and modified from the original ShapeRoi function in order to avoid precision loss (int)
 *  - however the recombination is loosing the precision (why ?)
 * A significant amount of the code is taken from ShapeRoi (see @package ij.gui.ShapeROI). Trying to solve these issues in order to not non regid transformations:
 * - removes integer coordinates conversion when getting rois path list
 * - being able to retrieve a list of control points
 * - being able to set new shapes based on a list of control points
 *
 * TODO :
 * - check whether the polygon is repeating
 * - check whether ROIs are representing areas
 *     - for the moment : write an error message
 *     - later plan, depending on use, is to handle correctly these non AREA rois
 * - manage to do a getRoi() function keeping the float precision in the reconstruction ShapeRoi
 *
 */

public class CompositeFloatPoly {
    public int getNumberOfCtrlPts() {
        return polys.stream().mapToInt(fp -> fp.npoints).sum();
    }

    public ArrayList<Point2D> getControlPoints() {
        ArrayList<Point2D> list = new ArrayList<>();
        polys.forEach( fp -> {
            for (int i=0;i<fp.npoints;i++) {
                list.add(new Point2D.Double(fp.xpoints[i],fp.ypoints[i]));
            }
        });
        return list;
    }

    public void setControlPoints(List<Point2D> pts) {
        int ptsIndex = 0;
        for (FloatPolygon fp:this.polys) {
            for (int i=0;i<fp.npoints;i++) {
                Point2D pt = pts.get(ptsIndex);
                fp.xpoints[i]= (float) pt.getX();
                fp.ypoints[i]= (float) pt.getY();
                ptsIndex++;
            }
        }
    }

    private static final double SHAPE_TO_ROI=-1.0;

    /***/
    static final int NO_TYPE = 128;

    /**Parsing a shape composed of linear segments less than this value will result in Roi objects of type
     * {@link ij.gui.Roi#POLYLINE} and {@link ij.gui.Roi#POLYGON} for open and closed shapes, respectively.
     * Conversion of shapes open and closed with more than MAXPOLY line segments will result,
     * respectively, in {@link ij.gui.Roi#FREELINE} and {@link ij.gui.Roi#FREEROI} (or
     * {@link ij.gui.Roi#TRACED_ROI} if {@link #forceTrace} flag is <strong><code>true</code></strong>.
     */
    private static final int MAXPOLY = 10; // I hate arbitrary values !!!!

    /**Flag which specifies how Roi objects will be constructed from closed (sub)paths having more than
     * <code>MAXPOLY</code> and composed exclusively of line segments.
     * If <strong><code>true</code></strong> then (sub)path will be parsed into a
     * {@link ij.gui.Roi#TRACED_ROI}; else, into a {@link ij.gui.Roi#FREEROI}. */
    private boolean forceTrace = false;

    /**The instance value of the maximum tolerance (MAXERROR) allowed in calculating the
     * length of the curve segments of this ROI's shape.
     */
    private double maxerror = CompositeFloatPoly.MAXERROR;

    /**The maximum tolerance allowed in calculating the length of the curve segments of this ROI's shape.*/
    static final double MAXERROR = 1.0e-3;

    /**Flag which specifies if Roi objects constructed from open (sub)paths composed of only two line segments
     * will be of type {@link ij.gui.Roi#ANGLE}.
     * If <strong><code>true</code></strong> then (sub)path will be parsed into a {@link ij.gui.Roi#ANGLE};
     * else, into a {@link ij.gui.Roi#POLYLINE}. */
    private boolean forceAngle = false;

    double x=0;
    double y=0;

    ArrayList<FloatPolygon> polys;

    public String name;

    public String toString() {
        return name;
    }

    public static boolean isClockwise(FloatPolygon pr) {
        if (pr.npoints<3) {
            return false;
        }
        float area = 0;
        for (int i=0;i<pr.npoints-1;i++) {
            area+=pr.xpoints[i]*pr.ypoints[i+1]-pr.ypoints[i]*pr.xpoints[i+1];
        }
        area+=pr.xpoints[pr.npoints-1]*pr.ypoints[0]-pr.ypoints[pr.npoints-1]*pr.xpoints[0];
        return area>0;
    }

    public Roi getRoi() {
        if (polys==null) {
            return null;
        }
        if (polys.size()==0) {
            return null;
        }
        if (polys.size()==1) {
            FloatPolygon fp = polys.get(0);
            return new PolygonRoi(fp.xpoints, fp.ypoints, fp.npoints, Roi.POLYGON);
        } else {
            Map<Boolean, List<FloatPolygon>> partitionedPolygons =
                    polys.stream()
                         .collect(Collectors.partitioningBy(CompositeFloatPoly::isClockwise));

            Optional<ShapeRoi> positiveShape = partitionedPolygons.get(true)
                                                                 .stream()
                                                                 .map(fp -> (new PolygonRoi(fp.xpoints, fp.ypoints, fp.npoints, Roi.POLYGON)))
                                                                 .map(pr -> new ShapeRoi(pr))
                                                                 .reduce(ShapeRoi::or);

            Optional<ShapeRoi> negativeShape = partitionedPolygons.get(false)
                    .stream()
                    .map(fp -> (new PolygonRoi(fp.xpoints, fp.ypoints, fp.npoints, Roi.POLYGON)))
                    .map(pr -> new ShapeRoi(pr))
                    .reduce(ShapeRoi::or);

            if (positiveShape.isPresent()) {
                if (negativeShape.isPresent()) {
                    return positiveShape.get().xor(negativeShape.get());
                } else {
                    return positiveShape.get();
                }
            } else {
                System.err.println("Could not build ROI : no positive area defined.");
                return null;
            }
        }
    }

    public CompositeFloatPoly(Roi roi) {
        name = roi.getName();
        polys = new ArrayList<>();
        this.x = roi.getXBase();
        this.y = roi.getYBase();
        if (roi instanceof ShapeRoi) {
            RoiManager roiManager = RoiManager.getRoiManager();
            ShapeRoi sr = (ShapeRoi) roi;
            Roi[] rois = getRois(sr);
            for (Roi r:rois) {
                polys.add(r.getFloatPolygon());
                System.out.println("class = "+r.getClass()+" name="+r.getName());
                roiManager.addRoi(r);
            }
        } else {
            // Single ROI
            // TODO  Error message if it's not an area
            polys.add(roi.getFloatPolygon());
        }

        for (FloatPolygon fp : polys) {
            System.out.println(fp.toString());
        }
    }

    Roi[] getRois (ShapeRoi sr) {
        Vector rois = new Vector();
        Shape shape = sr.getShape();
        if (shape instanceof Rectangle2D.Double) {
            Roi r = new Roi((int)((Rectangle2D.Double)shape).getX(), (int)((Rectangle2D.Double)shape).getY(), (int)((Rectangle2D.Double)shape).getWidth(), (int)((Rectangle2D.Double)shape).getHeight());
            rois.addElement(r);
        } else if (shape instanceof Ellipse2D.Double) {
            Roi r = new OvalRoi((int)((Ellipse2D.Double)shape).getX(), (int)((Ellipse2D.Double)shape).getY(), (int)((Ellipse2D.Double)shape).getWidth(), (int)((Ellipse2D.Double)shape).getHeight());
            rois.addElement(r);
        } else if (shape instanceof Line2D.Double) {
            Roi r = new ij.gui.Line((int)((Line2D.Double)shape).getX1(), (int)((Line2D.Double)shape).getY1(), (int)((Line2D.Double)shape).getX2(), (int)((Line2D.Double)shape).getY2());
            rois.addElement(r);
        } else if (shape instanceof Polygon) {
            Roi r = new PolygonRoi(((Polygon)shape).xpoints, ((Polygon)shape).ypoints, ((Polygon)shape).npoints, Roi.POLYGON);
            rois.addElement(r);
        } else if (shape instanceof GeneralPath) {
            PathIterator pIter; // assume never flatten
            pIter = shape.getPathIterator(new AffineTransform());
            parsePath(pIter, null, null, rois, null);
        }
        Roi[] array = new Roi[rois.size()];
        rois.copyInto((Roi[])array);
        return array;
    }

    boolean parsePath(PathIterator pIter, double[] params, Vector segments, Vector rois, Vector handles) {
        if (pIter==null || pIter.isDone())
            return false;
        boolean result = true;
        double pw = 1.0, ph = 1.0;
        Vector xCoords = new Vector();
        Vector yCoords = new Vector();
        if (segments==null) segments = new Vector();
        if (handles==null) handles = new Vector();
        //if(rois==null) rois = new Vector();
        if (params == null) params = new double[1];
        boolean shapeToRoi = params[0]==SHAPE_TO_ROI;
        int subPaths = 0; // the number of subpaths
        int count = 0;// the number of segments in each subpath w/o SEG_CLOSE; resets to one after each SEG_MOVETO
        int roiType = Roi.RECTANGLE;
        int segType;
        boolean closed = false;
        boolean linesOnly = true;
        boolean curvesOnly = true;
        //boolean success = false;
        double[] coords; // scaled coordinates of the path segment
        double[] ucoords; // unscaled coordinates of the path segment
        double sX = Double.NaN; // start x of subpath (scaled)
        double sY = Double.NaN; // start y of subpath (scaled)
        double x0 = Double.NaN; // last x in the subpath (scaled)
        double y0 = Double.NaN; // last y in the subpath (scaled)
        double usX = Double.NaN;// unscaled versions of the above
        double usY = Double.NaN;
        double ux0 = Double.NaN;
        double uy0 = Double.NaN;
        double pathLength = 0.0;
        Shape curve; // temporary reference to a curve segment of the path
        boolean done = false;
        while (!done) {
            coords = new double[6];
            ucoords = new double[6];
            segType = pIter.currentSegment(coords);
            segments.add(new Integer(segType));
            count++;
            System.arraycopy(coords,0,ucoords,0,coords.length);
            switch(segType) {
                case PathIterator.SEG_MOVETO:
                    if (subPaths>0) {
                        closed = ((int)ux0==(int)usX && (int)uy0==(int)usY);
                        if (closed && (int)ux0!=(int)usX && (int)uy0!=(int)usY) { // this may only happen after a SEG_CLOSE
                            xCoords.add(new Double(((Double)xCoords.elementAt(0)).doubleValue()));
                            yCoords.add(new Double(((Double)yCoords.elementAt(0)).doubleValue()));
                        }
                        if (rois!=null) {
                            roiType = guessType(count, linesOnly, curvesOnly, closed);
                            Roi r = createRoi(xCoords, yCoords, roiType);
                            if (r!=null)
                                rois.addElement(r);
                        }
                        xCoords = new Vector();
                        yCoords = new Vector();
                        count = 1;
                    }
                    subPaths++;
                    usX = ucoords[0];
                    usY = ucoords[1];
                    ux0 = ucoords[0];
                    uy0 = ucoords[1];
                    sX = coords[0];
                    sY = coords[1];
                    x0 = coords[0];
                    y0 = coords[1];
                    handles.add(new Point2D.Double(ucoords[0],ucoords[1]));
                    xCoords.add(new Double(ucoords[0]));
                    yCoords.add(new Double(ucoords[1]));
                    closed = false;
                    break;
                case PathIterator.SEG_LINETO:
                    linesOnly = linesOnly & true;
                    curvesOnly = curvesOnly & false;
                    pathLength += Math.sqrt(Math.pow((y0-coords[1]),2.0)+Math.pow((x0-coords[0]),2.0));
                    ux0 = ucoords[0];
                    uy0 = ucoords[1];
                    x0 = coords[0];
                    y0 = coords[1];
                    handles.add(new Point2D.Double(ucoords[0],ucoords[1]));
                    xCoords.add(new Double(ucoords[0]));
                    yCoords.add(new Double(ucoords[1]));
                    closed = ((int)ux0==(int)usX && (int)uy0==(int)usY);
                    break;
                case PathIterator.SEG_QUADTO:
                    linesOnly = linesOnly & false;
                    curvesOnly = curvesOnly & true;
                    curve = new QuadCurve2D.Double(x0,y0,coords[0],coords[2],coords[2],coords[3]);
                    pathLength += qBezLength((QuadCurve2D.Double)curve);
                    ux0 = ucoords[2];
                    uy0 = ucoords[3];
                    x0 = coords[2];
                    y0 = coords[3];
                    handles.add(new Point2D.Double(ucoords[0],ucoords[1]));
                    handles.add(new Point2D.Double(ucoords[2],ucoords[3]));
                    xCoords.add(new Double((double)ucoords[2]));
                    yCoords.add(new Double((double)ucoords[3]));
                    closed = ((int)ux0==(int)usX && (int)uy0==(int)usY);
                    break;
                case PathIterator.SEG_CUBICTO:
                    linesOnly = linesOnly & false;
                    curvesOnly  = curvesOnly & true;
                    curve = new CubicCurve2D.Double(x0,y0,coords[0],coords[1],coords[2],coords[3],coords[4],coords[5]);
                    pathLength += cBezLength((CubicCurve2D.Double)curve);
                    ux0 = ucoords[4];
                    uy0 = ucoords[5];
                    x0 = coords[4];
                    y0 = coords[5];
                    handles.add(new Point2D.Double(ucoords[0],ucoords[1]));
                    handles.add(new Point2D.Double(ucoords[2],ucoords[3]));
                    handles.add(new Point2D.Double(ucoords[4],ucoords[5]));
                    xCoords.add(new Double((double)ucoords[4]));
                    yCoords.add(new Double((double)ucoords[5]));
                    closed = ((int)ux0==(int)usX && (int)uy0==(int)usY);
                    break;
                case PathIterator.SEG_CLOSE:
                    if((int)ux0 != (int)usX && (int)uy0 != (int)usY) pathLength += Math.sqrt(Math.pow((x0-sX),2.0) + Math.pow((y0-sY),2.0));
                    closed = true;
                    break;
                default:
                    break;
            }
            pIter.next();
            done = pIter.isDone() || (shapeToRoi&&rois!=null&&rois.size()==1);
            if (done) {
                if(closed && (int)x0!=(int)sX && (int)y0!=(int)sY) { // this may only happen after a SEG_CLOSE
                    xCoords.add(new Double(((Double)xCoords.elementAt(0)).doubleValue()));
                    yCoords.add(new Double(((Double)yCoords.elementAt(0)).doubleValue()));
                }
                if (rois!=null) {
                    roiType = shapeToRoi?Roi.TRACED_ROI:guessType(count+1, linesOnly, curvesOnly, closed);
                    Roi r = createRoi(xCoords, yCoords, roiType);
                    if (r!=null)
                        rois.addElement(r);
                }
            }
        }
        params[0] = pathLength;
        return result;
    }

    /**Implements the rules of conversion from <code>java.awt.geom.GeneralPath</code> to <code>ij.gui.Roi</code>.
     * @param segments The number of segments that compose the path
     * @param linesOnly Indicates wether the GeneralPath object is composed only of SEG_LINETO segments
     * @param curvesOnly Indicates wether the GeneralPath object is composed only of SEG_CUBICTO and SEG_QUADTO segments
     * @param closed Indicates a closed GeneralPath
     * @see_#shapeToRois()
     * @return a type flag
     */
    private int guessType(int segments, boolean linesOnly, boolean curvesOnly, boolean closed) {
        closed = true; // lines currently not supported
        int roiType = Roi.RECTANGLE;
        if (linesOnly) {
            switch(segments) {
                case 0: roiType = NO_TYPE; break;
                case 1: roiType = NO_TYPE; break;
                case 2: roiType = (closed ? NO_TYPE : Roi.LINE); break;
                case 3: roiType = (closed ? Roi.POLYGON : (forceAngle ? Roi.ANGLE: Roi.POLYLINE)); break;
                case 4: roiType = (closed ? Roi.RECTANGLE : Roi.POLYLINE); break;
                default:
                    if (segments <= MAXPOLY)
                        roiType = closed ? Roi.POLYGON : Roi.POLYLINE;
                    else
                        roiType = closed ? (forceTrace ? Roi.TRACED_ROI: Roi.FREEROI): Roi.FREELINE;
                    break;
            }
        }
        else roiType = segments >=2 ? Roi.COMPOSITE : NO_TYPE;
        return roiType;
    }

    /**Calculates the length of a quadratic B&eacute;zier curve specified in double precision.
     * The algorithm is based on the theory presented in paper <br>
     * &quot;Jens Gravesen. Adaptive subdivision and the length and energy of B&eacute;zier curves. Computational Geometry <strong>8:</strong><em>13-31</em> (1997)&quot;
     * implemented using <code>java.awt.geom.CubicCurve2D.Double</code>.
     * Please visit {@link <a href="http://www.graphicsgems.org/gems.html#gemsiv">Graphics Gems IV</a>} for
     * examples of other possible implementations in C and C++.
     */
    double qBezLength(QuadCurve2D.Double c) {
        double l = 0.0;
        double cl = qclength(c);
        double pl = qplength(c);
        if((pl-cl)/2.0 > maxerror)
        {
            QuadCurve2D.Double[] cc = qBezSplit(c);
            for(int i=0; i<2; i++) l+=qBezLength(cc[i]);
            return l;
        }
        l = (2.0*pl+cl)/3.0;
        return l;
    }

    /**Length of the chord of the arc of the quadratic B&eacute;zier curve argument, in double precision.*/
    double qclength(QuadCurve2D.Double c)
    { return Math.sqrt(Math.pow((c.x2-c.x1),2.0) + Math.pow((c.y2-c.y1),2.0)); }

    /**Creates a Roi object based on the arguments.
     * @see_shapeToRois()
     * @param xCoords the x coordinates
     * @param yCoords the y coordinates
     * @param_type the type flag
     * @return a ij.gui.Roi object
     */
    private Roi createRoi(Vector xCoords, Vector yCoords, int roiType) {
        if (roiType==NO_TYPE) return null;
        Roi roi = null;
        if(xCoords.size() != yCoords.size() || xCoords.size()==0) { return null; }

        double[] xPoints = new double[xCoords.size()];
        double[] yPoints = new double[yCoords.size()];

        for (int i=0; i<xPoints.length; i++) {
            xPoints[i] = ((Double)xCoords.elementAt(i)).doubleValue() + x;
            yPoints[i] = ((Double)yCoords.elementAt(i)).doubleValue() + y;
        }

        double startX = 0;
        double startY = 0;
        double width = 0;
        double height = 0;
        switch(roiType) {
            //case NO_TYPE: roi = this; break; // I do not understand
            case Roi.COMPOSITE: System.err.println("Unsupported createRoi operation!"); break;//roi = this; break; // hmmm.....!!!???
            case Roi.OVAL:
                startX = xPoints[xPoints.length-4];
                startY = yPoints[yPoints.length-3];
                width = max(xPoints)-min(xPoints);
                height = max(yPoints)-min(yPoints);
                roi = new OvalRoi(startX, startY, width, height);
                break;
            case Roi.RECTANGLE:
                startX = xPoints[0];
                startY = yPoints[0];
                width = max(xPoints)-min(xPoints);
                height = max(yPoints)-min(yPoints);
                roi = new Roi(startX, startY, width, height);
                break;
            case Roi.LINE: roi = new ij.gui.Line(xPoints[0],yPoints[0],xPoints[1],yPoints[1]); break;
            default:
                int n = xPoints.length;
                roi = new PolygonRoi(toFloatArray(xPoints), toFloatArray(yPoints), n, roiType);
                if (roiType==Roi.FREEROI) {
                    double length = roi.getLength();
                    double mag = 1.0;
                    length *= mag;
                    if (length/n>=15.0) {
                        roi = new PolygonRoi(toFloatArray(xPoints), toFloatArray(yPoints), n, Roi.POLYGON);
                    }
                }
                break;
        }
        return roi;
    }

    //https://stackoverflow.com/questions/7513434/convert-a-double-array-to-a-float-array
    float[] toFloatArray(double[] arr) {
        if (arr == null) return null;
        int n = arr.length;
        float[] ret = new float[n];
        for (int i = 0; i < n; i++) {
            ret[i] = (float)arr[i];
        }
        return ret;
    }

    /** Returns the element with the smallest value in the array argument.*/
    private int min(int[] array) {
        int val = array[0];
        for (int i=1; i<array.length; i++) val = Math.min(val,array[i]);
        return val;
    }

    /** Returns the element with the largest value in the array argument.*/
    private int max(int[] array) {
        int val = array[0];
        for (int i=1; i<array.length; i++) val = Math.max(val,array[i]);
        return val;
    }

    /** Returns the element with the smallest value in the array argument.*/
    private double min(double[] array) {
        double val = array[0];
        for (int i=1; i<array.length; i++) val = Math.min(val,array[i]);
        return val;
    }

    /** Returns the element with the largest value in the array argument.*/
    private double max(double[] array) {
        double val = array[0];
        for (int i=1; i<array.length; i++) val = Math.max(val,array[i]);
        return val;
    }


    /**Calculates the length of a cubic B&eacute;zier curve specified in double precision.
     * The algorithm is based on the theory presented in paper <br>
     * &quot;Jens Gravesen. Adaptive subdivision and the length and energy of B&eacute;zier curves. Computational Geometry <strong>8:</strong><em>13-31</em> (1997)&quot;
     * implemented using <code>java.awt.geom.CubicCurve2D.Double</code>.
     * Please visit {@link <a href="http://www.graphicsgems.org/gems.html#gemsiv">Graphics Gems IV</a>} for
     * examples of other possible implementations in C and C++.
     */
    double cBezLength(CubicCurve2D.Double c) {
        double l = 0.0;
        double cl = cclength(c);
        double pl = cplength(c);
        if((pl-cl)/2.0 > maxerror)
        {
            CubicCurve2D.Double[] cc = cBezSplit(c);
            for(int i=0; i<2; i++) l+=cBezLength(cc[i]);
            return l;
        }
        l = 0.5*pl+0.5*cl;
        return l;
    }

    /**Length of the chord of the arc of the cubic B&eacute;zier curve argument, in double precision.*/
    double cclength(CubicCurve2D.Double c)
    { return Math.sqrt(Math.pow((c.x2-c.x1),2.0) + Math.pow((c.y2-c.y1),2.0)); }

    /**Length of the control polygon of the cubic B&eacute;zier curve argument, in double precision.*/
    double cplength(CubicCurve2D.Double c) {
        double result = Math.sqrt(Math.pow((c.ctrlx1-c.x1),2.0)+Math.pow((c.ctrly1-c.y1),2.0));
        result += Math.sqrt(Math.pow((c.ctrlx2-c.ctrlx1),2.0)+Math.pow((c.ctrly2-c.ctrly1),2.0));
        result += Math.sqrt(Math.pow((c.x2-c.ctrlx2),2.0)+Math.pow((c.y2-c.ctrly2),2.0));
        return result;
    }


    /**Splits a cubic B&eacute;zier curve in half.
     * @param c A cubic B&eacute;zier curve to be divided
     * @return an array with the left and right cubic B&eacute;zier subcurves
     *
     */
    CubicCurve2D.Double[] cBezSplit(CubicCurve2D.Double c) {
        CubicCurve2D.Double[] cc = new CubicCurve2D.Double[2];
        for (int i=0; i<2 ; i++) cc[i] = new CubicCurve2D.Double();
        c.subdivide(cc[0],cc[1]);
        return cc;
    }

    /**Splits a quadratic B&eacute;zier curve in half.
     * @param c A quadratic B&eacute;zier curve to be divided
     * @return an array with the left and right quadratic B&eacute;zier subcurves
     *
     */
    QuadCurve2D.Double[] qBezSplit(QuadCurve2D.Double c) {
        QuadCurve2D.Double[] cc = new QuadCurve2D.Double[2];
        for(int i=0; i<2; i++) cc[i] = new QuadCurve2D.Double();
        c.subdivide(cc[0],cc[1]);
        return cc;
    }

    /**Length of the control polygon of the quadratic B&eacute;zier curve argument, in double precision.*/
    double qplength(QuadCurve2D.Double c) {
        double result = Math.sqrt(Math.pow((c.ctrlx-c.x1),2.0)+Math.pow((c.ctrly-c.y1),2.0));
        result += Math.sqrt(Math.pow((c.x2-c.ctrlx),2.0)+Math.pow((c.y2-c.ctrly),2.0));
        return result;
    }

}
