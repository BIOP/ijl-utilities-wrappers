package ch.epfl.biop.wrappers.stardist;

public class StardistTaskSettings {

    String image_path;
    String model_path;
    String output_path;
    int x_tiles = -1;
    int y_tiles = -1;
    int z_tiles = -1;
    float pmin = (float) 3.0 ;
    float pmax = (float) 99.8;
    float prob_thresh = -1;
    float nms_thresh = -1;

    public StardistTaskSettings setImagePath(String image_path) {
        this.image_path = image_path;
        return this;
    }

    public StardistTaskSettings setModelPath(String model_path) {
        this.model_path = model_path;
        return this;
    }

    public StardistTaskSettings setOutputPath(String output_path) {
        this.output_path = output_path;
        return this;
    }

    public StardistTaskSettings setXTiles(int x_tiles) {
        this.x_tiles = x_tiles;
        return this;
    }

    public StardistTaskSettings setYTiles(int y_tiles) {
        this.y_tiles = y_tiles;
        return this;
    }
    public StardistTaskSettings setZTiles(int z_tiles) {
        this.z_tiles = z_tiles;
        return this;
    }
    public StardistTaskSettings setPmin(float pmin) {
        this.pmin = pmin;
        return this;
    }

    public StardistTaskSettings setPmax(float pmax) {
        this.pmax = pmax;
        return this;
    }

    public StardistTaskSettings setProbThresh(float prob_thresh) {
        this.prob_thresh = prob_thresh;
        return this;
    }

    public StardistTaskSettings setNmsThresh(float nms_thresh) {
        this.nms_thresh = nms_thresh;
        return this;
    }
}

