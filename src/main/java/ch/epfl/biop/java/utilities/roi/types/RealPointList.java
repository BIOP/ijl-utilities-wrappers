package ch.epfl.biop.java.utilities.roi.types;

import net.imglib2.RealPoint;

import java.util.List;

public class RealPointList {

    public IJShapeRoiArray shapeRoiList;

    public RealPointList(List<RealPoint> rpl) {
        this.ptList = rpl;
    }

    public final List<RealPoint> ptList;
}
