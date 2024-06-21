package ch.epfl.biop.java.utilities.roi;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.anim.dom.SVGOMPathElement;
import org.apache.batik.anim.dom.SVGPathSupport;
import org.apache.batik.bridge.*;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.NodeList;
import org.w3c.dom.svg.SVGDocument;
import org.w3c.dom.svg.SVGPoint;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.StringTokenizer;

public class Svg2Roi {

    public static ArrayList<Roi> getRoisFromSVGNodeList(NodeList paths, float sampleLength, float scaleFactor) {
        ArrayList<Roi> rois = new ArrayList<>();
        for(int i=0; i < paths.getLength(); i++){
            SVGOMPathElement path = (SVGOMPathElement)paths.item(i);
            rois.add(getRoiFromSVGPathElement(path, sampleLength, scaleFactor));
        }
        return rois;
    }

    public static Roi getRoiFromSVGPathElement(SVGOMPathElement path, float sampleLength, float downscaleFactor ) {
        float total_path_length = path.getTotalLength();
        //System.out.println("---------------------------- downscaleFactor="+downscaleFactor);
        int NTotalPts = (int) (total_path_length/ sampleLength);

        float[] xp = new float[NTotalPts];
        float[] yp = new float[NTotalPts];

        for(int j=0; j < NTotalPts ; j++){

            SVGPoint tmp_point = SVGPathSupport.getPointAtLength(path, sampleLength*j);
            xp[j]=tmp_point.getX()/downscaleFactor;
            yp[j]=tmp_point.getY()/downscaleFactor;

        }
        PolygonRoi roi = new PolygonRoi(xp,yp, Roi.POLYGON);
        roi.setName(path.getAttribute("structure_id"));//path.getId());
        //path.getAttribute("structure_id");
        String color = "aaaa150,150,150";//path.getStyle().getPropertyCSSValue("fill").getCssText();
        String s1 = color.substring(4);
        color = s1.replace(')', ' ');
        StringTokenizer st = new StringTokenizer(color);
        int r = Integer.parseInt(st.nextToken(",").trim());
        int g = Integer.parseInt(st.nextToken(",").trim());
        int b = Integer.parseInt(st.nextToken(",").trim());
        Color c = new Color(r, g, b);
        roi.setStrokeColor(c);
        return roi;
    }


    public static SVGDocument getSVGDocumentFromFilePath(String path) {
        URI fileURI = new File(path).toURI();
        return getSVGDocumentFromURI(fileURI);
    }

    public static SVGDocument getSVGDocumentFromURL(String urlString) {
        URI uri = null;
        try {
            URL url = new URL(urlString);
            uri = new URI(url.toString());
        } catch (MalformedURLException | URISyntaxException e) {
            e.printStackTrace();
        }
        return getSVGDocumentFromURI(uri);
    }


    public static SVGDocument getSVGDocumentFromURI(URI fileURI) {
        SVGDocument    svgDoc = null;
        UserAgent      userAgent;
        DocumentLoader loader;
        org.apache.batik.bridge.BridgeContext  ctx;
        GVTBuilder     builder;

        userAgent = new UserAgentAdapter();
        loader    = new DocumentLoader(userAgent);
        ctx       = new BridgeContext(userAgent, loader);
        ctx.setDynamicState(BridgeContext.DYNAMIC);
        builder   = new GVTBuilder();

        String parser = XMLResourceDescriptor.getXMLParserClassName();
        SAXSVGDocumentFactory svgf = new SAXSVGDocumentFactory(parser);
        try {
            svgDoc = (SVGDocument)svgf.createDocument(fileURI.toString());
            builder.build( ctx, svgDoc);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return svgDoc;
    }

    public static void putRoisToRoiManager(ArrayList<Roi> rois) {
        RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
            roiManager = new RoiManager();
        }

        for (Roi points : rois) {
            roiManager.addRoi(points);
        }
    }


}