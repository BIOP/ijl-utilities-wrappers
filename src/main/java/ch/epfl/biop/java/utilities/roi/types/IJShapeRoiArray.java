package ch.epfl.biop.java.utilities.roi.types;

import ij.gui.Roi;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.UnaryOperator;

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
        LinkedList<Point2D> allPts = new LinkedList<>();
        this.roiscvt.forEach(fp -> {
            allPts.addAll(fp.getControlPoints());
        });
        return allPts;
    }

    public void setPoints(List<Point2D> controlPoints) {
        // Split list between different ROIs
        int index = 0;
        for (int i = 0;i<roiscvt.size();i++) {
            CompositeFloatPoly cfp = roiscvt.get(i);
            int nPts = cfp.getNumberOfCtrlPts();
            cfp.setControlPoints(controlPoints.subList(index,index+nPts));
            index+=nPts;
        }
    }

    public void transform(UnaryOperator<Point2D> transformer) {
        List<Point2D> ctrlPts = getPoints();
        ctrlPts.replaceAll(transformer);
        setPoints(ctrlPts);
    }

}
