package ch.epfl.biop.wrappers.cellpose;

public class CellposeTaskSettings {

    String envPath;
    String envType = "conda";
    String datasetDir;
    String model;
    int ch1;
    int ch2 = -1;

    // value defined from https://cellpose.readthedocs.io/en/latest/api.html
    double diameter = 30;
    int batch_size = 1;
    boolean use_tile = true;
    int tile_size = 0;
    double flow_threshold = 0.4;
    double cellprob_threshold = 0.0;
    String additional_flags = "";

    public CellposeTaskSettings setEnvPath(String conda_env_path) {
        this.envPath = conda_env_path;
        return this;
    }

    public CellposeTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public CellposeTaskSettings setDatasetDir(String datasetDir) {
        this.datasetDir = datasetDir;
        return this;
    }

    public CellposeTaskSettings setModel(String model) {
        this.model = model;
        return this;
    }

    public CellposeTaskSettings setChannel1(int ch1) {
        this.ch1 = ch1;
        return this;
    }

    public CellposeTaskSettings setChannel2(int ch2) {
        this.ch2 = ch2;
        return this;
    }

    public CellposeTaskSettings setDiameter(double diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeTaskSettings setBatchSize(int batch_size) {
        this.batch_size = batch_size;
        return this;
    }

    public CellposeTaskSettings setUseTile(boolean use_tile) {
        this.use_tile = use_tile;
        return this;
    }

    public CellposeTaskSettings setTileSize(int tile_size) {
        this.tile_size = tile_size;
        return this;
    }

    public CellposeTaskSettings setFlowThreshold(double flow_threshold) {
        this.flow_threshold = flow_threshold;
        return this;
    }

    public CellposeTaskSettings setCellprobThreshold(double cellprob_threshold) {
        this.cellprob_threshold = cellprob_threshold;
        return this;
    }

    public CellposeTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
