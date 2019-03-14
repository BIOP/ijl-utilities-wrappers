package ch.epfl.biop.java.utilities.roi.types;

import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.plugin.frame.RoiManager;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IJShapeRoiArray {
    public List<CompositeFloatPoly> roiscvt;

    public List<Roi> rois;

    public IJShapeRoiArray(List<Roi> rois_in) {
        rois = new ArrayList<>();
        roiscvt = new ArrayList<>();
       // rois_in.forEach(roi -> {
       //     rois.add(roi);
       // });
        rois_in.forEach(roi -> {
            System.out.println(roi);
            roiscvt.add(new CompositeFloatPoly(roi));
        });

    }

    public List<Point2D> getPoints() {
        /*ArrayList<Point2D> list = new ArrayList<>();
        for (ShapeRoi roi:rois) {

            Shape  shape = rois.get(0).getShape();

            Path2D.Double path = new Path2D.Double(shape, new AffineTransform());
            if (path instanceof Path2D) {
                // if I understand correctly, this means there's a single shape
                Path2D castPath = (Path2D) path;
               // castPath
               // path.getPathIterator(new AffineTransform()).currentSegment()
            } else {

            }*/

            /*
             if (var1 instanceof Path2D) {
                Path2D var3 = (Path2D)var1;
                this.setWindingRule(var3.windingRule);
                this.numTypes = var3.numTypes;
                this.pointTypes = Arrays.copyOf(var3.pointTypes, var3.numTypes);
                this.numCoords = var3.numCoords;
                this.doubleCoords = var3.cloneCoordsDouble(var2);
            } else {
                PathIterator var4 = var1.getPathIterator(var2);
                this.setWindingRule(var4.getWindingRule());
                this.pointTypes = new byte[20];
                this.doubleCoords = new double[40];
                this.append(var4, false);
            }
             */
        //}

        return null;// list;
    }

    public void setPoints(List<Point2D> controlPoints) {

        //(new AffineTransform()).createTransformedShape(shape);
    }



}
