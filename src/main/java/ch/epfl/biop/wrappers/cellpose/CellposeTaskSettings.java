package ch.epfl.biop.wrappers.cellpose;

public class CellposeTaskSettings {

    String envPath;
    String envType = "conda";
    String datasetDir;
    String model;
    int ch1;
    int ch2 = -1;

    // value defined from https://cellpose.readthedocs.io/en/latest/api.html
    float diameter = 30;
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

    public CellposeTaskSettings setDiameter(float diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
