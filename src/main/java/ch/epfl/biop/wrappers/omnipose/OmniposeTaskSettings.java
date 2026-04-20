package ch.epfl.biop.wrappers.omnipose;

public class OmniposeTaskSettings {

    String envPath;
    String envType = "conda";
    String datasetDir;
    String model;
    int ch1 = 0;
    int ch2 = -1;

    // value defined from https://omnipose.readthedocs.io/cli.html
    double diameter = 30;
    int batch_size = 1;
    boolean use_tile = true;
    int tile_size = 0;
    double flow_threshold = 0.4;
    double cellprob_threshold = 0.0;
    String additional_flags = "";

    public OmniposeTaskSettings setEnvPath(String envPath) {
        this.envPath = envPath;
        return this;
    }

    public OmniposeTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public OmniposeTaskSettings setDatasetDir(String datasetDir) {
        this.datasetDir = datasetDir;
        return this;
    }

    public OmniposeTaskSettings setModel(String model) {
        this.model = model;
        return this;
    }

    public OmniposeTaskSettings setChannel1(int ch1) {
        this.ch1 = ch1;
        return this;
    }

    public OmniposeTaskSettings setChannel2(int ch2) {
        this.ch2 = ch2;
        return this;
    }

    public OmniposeTaskSettings setDiameter(double diameter) {
        this.diameter = diameter;
        return this;
    }

    public OmniposeTaskSettings setBatchSize(int batch_size) {
        this.batch_size = batch_size;
        return this;
    }

    public OmniposeTaskSettings setUseTile(boolean use_tile) {
        this.use_tile = use_tile;
        return this;
    }

    public OmniposeTaskSettings setTileSize(int tile_size) {
        this.tile_size = tile_size;
        return this;
    }

    public OmniposeTaskSettings setFlowThreshold(double flow_threshold) {
        this.flow_threshold = flow_threshold;
        return this;
    }

    public OmniposeTaskSettings setCellprobThreshold(double cellprob_threshold) {
        this.cellprob_threshold = cellprob_threshold;
        return this;
    }

    public OmniposeTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
