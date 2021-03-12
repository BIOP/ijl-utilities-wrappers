package ch.epfl.biop.wrappers.cellpose;


import ij.Prefs;

public class CellposeTaskSettings {


    String datasetDir;
    String model;
    int ch1 = 1 ;
    // value defined from https://cellpose.readthedocs.io/en/latest/command.html#input-settings
    int diameter = 30 ;
    double flow_threshold = 0.0 ;
    double cellprob_threshold = 0.0 ;

    boolean useGpu;
    boolean useFastMode;
    boolean useResample;
    boolean useMxnet;
    boolean use3D;

    public CellposeTaskSettings setDo3D() {
        this.use3D = true;
        return this;
    }

    public CellposeTaskSettings setDatasetDir( String datasetDir) {
        this.datasetDir = datasetDir;
        return this;
    }

    public CellposeTaskSettings setModel( String model) {
        this.model = model;
        return this;
    }

    public CellposeTaskSettings setModelNuclei() {
        this.model = "nuclei";
        return this;
    }

    public CellposeTaskSettings setModelCyto() {
        this.model = "cyto";
        return this;
    }

    public CellposeTaskSettings setChannel1( int ch1) {
        this.ch1 = ch1;
        return this;
    }

    public CellposeTaskSettings setDiameter( int diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeTaskSettings setFlowTH( double flow_threshold) {
        this.flow_threshold = flow_threshold;
        return this;
    }

    public CellposeTaskSettings setCellProbTh( double cellprob_threshold) {
        this.cellprob_threshold = cellprob_threshold;
        return this;
    }
}
