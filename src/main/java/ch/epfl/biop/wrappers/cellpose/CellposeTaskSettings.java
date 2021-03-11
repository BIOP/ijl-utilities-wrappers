package ch.epfl.biop.wrappers.cellpose;


import ij.Prefs;

public class CellposeTaskSettings {


    String datasetDir;
    String model;
    String ch1 ;
    String diameter;
    String flow_threshold;
    String cellprob_threshold;
    boolean useGpu;
    boolean useFastMode;
    boolean useResample;
    boolean useMxnet;
    boolean use3D;

    public CellposeTaskSettings initialize() {
        String keyPrefix = Cellpose.class.getName() + ".";

        this.useGpu = Prefs.get(keyPrefix+"useGpu", true);
        this.useMxnet = Prefs.get(keyPrefix+"useMxnet", false);
        this.useFastMode = Prefs.get(keyPrefix+"useFastMode",false);
        this.useResample = Prefs.get(keyPrefix+"useResample",false);

        return this;
    }

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
        this.ch1 = ""+ch1;
        return this;
    }

    public CellposeTaskSettings setDiameter( int diameter) {
        this.diameter = ""+diameter;
        return this;
    }

    public CellposeTaskSettings setFlowTH( int flow_threshold) {
        this.flow_threshold = ""+flow_threshold;
        return this;
    }

    public CellposeTaskSettings setCellProbTh( int cellprob_threshold) {
        this.cellprob_threshold = ""+cellprob_threshold;
        return this;
    }
}
