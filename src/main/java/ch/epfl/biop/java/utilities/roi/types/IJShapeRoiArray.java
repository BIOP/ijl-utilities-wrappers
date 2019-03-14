package ch.epfl.biop.java.utilities.roi.types;

import ij.gui.Roi;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class IJShapeRoiArray {
    // TODO : remove roi list
    public List<CompositeFloatPoly> roiscvt;

    public List<Roi> rois;

    public IJShapeRoiArray(List<Roi> rois_in) {
        rois = new ArrayList<>();
        roiscvt = new ArrayList<>();
        rois_in.forEach(roi -> {
            System.out.println(roi);
            roiscvt.add(new CompositeFloatPoly(roi));
        });

    }

    public List<Point2D> getPoints() {
        return null;
    }

    public void setPoints(List<Point2D> controlPoints) {

    }

}
