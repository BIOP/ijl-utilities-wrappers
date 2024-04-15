package ch.epfl.biop.wrappers.omnipose;

public class OmniposeTaskSettings {

    String conda_env_path;
    String datasetDir;
    String model;
    int ch1 = 0;
    int ch2 = -1;

    // value defined from https://omnipose.readthedocs.io/cli.html
    int diameter = 30;
    boolean omni = false;
    boolean cluster = false;
    String additional_flags = "";

    public OmniposeTaskSettings setCondaEnvDir(String conda_env_path) {
        this.conda_env_path = conda_env_path;
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

    public OmniposeTaskSettings setDiameter(int diameter) {
        this.diameter = diameter;
        return this;
    }

    public OmniposeTaskSettings setOmni(boolean omni) {
        this.omni = omni;
        return this;
    }

    public OmniposeTaskSettings setCluster(boolean cluster) {
        this.cluster = cluster;
        return this;
    }
    
    public OmniposeTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
