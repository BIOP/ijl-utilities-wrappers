package ch.epfl.biop.java.utilities.roi;

import net.imglib2.RealPoint;

import java.util.List;

public class RealPointList {

    public RealPointList(List<RealPoint> rpl) {
        this.ptList = rpl;
    }

    public List<RealPoint> ptList;
}
