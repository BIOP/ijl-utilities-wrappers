package ch.epfl.biop.wrappers.spotiflow;

import ij.*;
import ij.gui.PointRoi;
import ij.plugin.frame.RoiManager;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotiflowPointsLoader {

    private ImagePlus targetImage = null;

    public SpotiflowPointsLoader() {
        this( null);
    }

    public SpotiflowPointsLoader( ImagePlus imp) {
        this.targetImage = imp;
    }

    public void loadPointsFromFiles(List<File> spotiflow_output_list_path) {
        if (spotiflow_output_list_path == null || spotiflow_output_list_path.isEmpty()) {
            IJ.error("No files provided");
            return;
        }

        RoiManager roiManager = RoiManager.getInstance();
        if (roiManager == null) {
            roiManager = new RoiManager();
        }


        for (File file : spotiflow_output_list_path) {
            if (!file.exists() || !file.canRead()) {
                IJ.log("Cannot read file: " + file.getPath());
                continue;
            }

            try {
                List<Point2D> points = readPointsFromCSV(file);
                if (!points.isEmpty()) {
                    int timeFrame = extractTimeFrame(file.getName());

                    // Set the image to the correct frame before adding ROIs
                    targetImage.setT(timeFrame);


                    createIndividualPointRois(targetImage, points, file, timeFrame, roiManager);


                    IJ.log("Loaded " + points.size() + " points from " + file.getName());
                } else {
                    IJ.log("No valid points found in " + file.getName());
                }
            } catch (IOException e) {
                IJ.error("Error reading file " + file.getName() + ": " + e.getMessage());
            }
        }

        roiManager.setVisible(true);
    }


    private void createIndividualPointRois(ImagePlus imp, List<Point2D> points, File file, int timeFrame, RoiManager roiManager) {
        String baseName = file.getName().replaceFirst("[.][^.]+$", ""); // Remove extension

        for (int i = 0; i < points.size(); i++) {
            Point2D point = points.get(i);
            double xCoord =  point.x;
            double yCoord =  point.y;

            imp.setT(timeFrame);

            PointRoi pointRoi = new PointRoi();
            pointRoi.addPoint(imp, xCoord, yCoord);
            String roiName = baseName + "_point_" + (i + 1);
            pointRoi.setName(roiName);

            roiManager.addRoi(pointRoi);
        }
    }

    private int extractTimeFrame(String filename) {

        // Pattern to match "-t" followed by digits (e.g., "-t1", "-t10")
        Pattern pattern = Pattern.compile("-t(\\d+)");
        Matcher matcher = pattern.matcher(filename);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private List<Point2D> readPointsFromCSV(File file) throws IOException {
        List<Point2D> points = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and potential header
                if (line.isEmpty()) continue;
                if (isFirstLine && (line.startsWith("y:") || line.startsWith("#") || line.toLowerCase().contains("coordinate"))) {
                    isFirstLine = false;
                    continue;
                }
                isFirstLine = false;

                try {
                    Point2D point = parseCSV(line);
                    if (point != null) {
                        points.add(point);
                    }
                } catch (NumberFormatException e) {
                    IJ.log("Skipping invalid line: " + line);
                }
            }
        }

        return points;
    }


    private Point2D parseCSV(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 2) {
            double y = Double.parseDouble(parts[0].trim());
            double x = Double.parseDouble(parts[1].trim());
            double intensity = parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 0;
            double probability = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 0;
            return new Point2D(x, y, intensity, probability);
        }
        return null;
    }

    // Setters for configuration

    public void setTargetImage(ImagePlus targetImage) {
        this.targetImage = targetImage;
    }

    // Helper class to store point data
    private static class Point2D {
        double x, y, intensity, probability;

        public Point2D(double x, double y, double intensity, double probability) {
            this.x = x;
            this.y = y;
            this.intensity = intensity;
            this.probability = probability;
        }
    }
}