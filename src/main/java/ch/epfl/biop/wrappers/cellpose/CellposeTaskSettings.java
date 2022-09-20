package ch.epfl.biop.wrappers.cellpose;


import ij.Prefs;

public class CellposeTaskSettings {

    String datasetDir;
    String model;
    int ch1;
    int ch2 = -1;

    // value defined from https://cellpose.readthedocs.io/en/latest/api.html
    int diameter = 30;
    double flow_threshold = 0.4;
    double cellprob_threshold = 0.0;
    double anisotropy = 1;
    double stitch_threshold = 0;

    boolean use3D = false;

    boolean useGpu;
    boolean useFastMode;
    boolean useResample;
    boolean useMxnet;
    String version;
    boolean omni = false;
    boolean cluster = false;
    String additional_flags = "";
    double diam_threshold;

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

    public CellposeTaskSettings setDiameter(int diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeTaskSettings setFlowTh(double flow_threshold) {
        this.flow_threshold = flow_threshold;
        return this;
    }

    public CellposeTaskSettings setCellProbTh(double cellprob_threshold) {
        this.cellprob_threshold = cellprob_threshold;
        return this;
    }

    public CellposeTaskSettings setAnisotropy(double anisotropy) {
        this.anisotropy = anisotropy;
        return this;
    }

    public CellposeTaskSettings setDiamThreshold(double diam_threshold) {
        this.diam_threshold = diam_threshold;
        return this;
    }

    public CellposeTaskSettings setStitchThreshold(double stitch_threshold) {
        this.stitch_threshold = stitch_threshold;
        return this;
    }

    public CellposeTaskSettings setDo3D() {
        this.use3D = true;
        return this;
    }

    public CellposeTaskSettings do3D(boolean do3D) {
        this.use3D = do3D;
        return this;
    }

    public CellposeTaskSettings setOmni(boolean omni) {
        this.omni = omni;
        return this;
    }

    public CellposeTaskSettings setCluster(boolean cluster) {
        this.cluster = cluster;
        return this;
    }

    public CellposeTaskSettings setFromPrefs() {
        String keyPrefix = Cellpose.class.getName() + ".";
        this.useGpu = Prefs.get(keyPrefix + "useGpu", Cellpose.useGpu);
        this.useMxnet = Prefs.get(keyPrefix + "useMxnet", Cellpose.useMxnet);
        this.useFastMode = Prefs.get(keyPrefix + "useFastMode", Cellpose.useFastMode);
        this.useResample = Prefs.get(keyPrefix + "useResample", Cellpose.useResample);
        this.version = Prefs.get(keyPrefix + "version", Cellpose.version);
        return this;
    }

    public CellposeTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
