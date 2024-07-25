package ch.epfl.biop.java.utilities.roi.types;

import ij.gui.Roi;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.UnaryOperator;

public class IJShapeRoiArray {

    final public List<CompositeFloatPoly> rois = new ArrayList<>();

    public IJShapeRoiArray(List<Roi> rois_in) {
        rois_in.forEach(roi -> rois.add(new CompositeFloatPoly(roi)));
    }

    public IJShapeRoiArray(IJShapeRoiArray input) {
        for (CompositeFloatPoly cfp: input.rois) {
            rois.add(new CompositeFloatPoly(cfp));
        }
    }

    public List<Point2D> getPoints() {
        LinkedList<Point2D> allPts = new LinkedList<>();
        this.rois.forEach(fp -> allPts.addAll(fp.getControlPoints()));
        return allPts;
    }

    public void setPoints(List<Point2D> controlPoints) {
        // Split list between different ROIs
        int index = 0;
        for (CompositeFloatPoly cfp : rois) {
            int nPts = cfp.getNumberOfCtrlPts();
            cfp.setControlPoints(controlPoints.subList(index, index + nPts));
            index += nPts;
        }
    }

    public void transform(UnaryOperator<Point2D> transformer) {
        List<Point2D> ctrlPts = getPoints();
        ctrlPts.replaceAll(transformer);
        setPoints(ctrlPts);
    }

    public void smoothenWithConstrains(boolean[][] movablePx) {
        for (CompositeFloatPoly cfp : this.rois) {
            cfp.smoothenWithConstrains(movablePx);
        }
    }


}
