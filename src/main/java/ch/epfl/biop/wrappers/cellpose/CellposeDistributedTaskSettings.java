package ch.epfl.biop.wrappers.cellpose;

import java.io.File;

public class CellposeDistributedTaskSettings {
    public String envPath;
    public String envType = "conda";
    public String inputPath;
    public String outputPath;
    public String model = "cyto3";
    public double diameter = 30;
    public int ch1 = 0;
    public int ch2 = -1;
    public String blocksize = "64,256,256";
    public int n_workers = 1;
    public double flow_threshold = 0.4;
    public double cellprob_threshold = 0.0;
    public double stitch_threshold = 0.0;
    public int min_size = 15;
    public String min_intensity = "None";
    public boolean use_gpu = true;
    public boolean optimize_parallel = false;
    public int batch_size = 1;
    public boolean show_dashboard = false;
    public double anisotropy = 1.0;
    public double gauss = 0.0;
    public int median = 0;
    public boolean global_norm = false;
    public int bsize = -1;
    public double tile_overlap = 0.1;
    public String logDirectory;
    public String additional_flags = "";

    public CellposeDistributedTaskSettings setEnvPath(String envPath) {
        this.envPath = envPath;
        return this;
    }

    public CellposeDistributedTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public CellposeDistributedTaskSettings setInputPath(String inputPath) {
        this.inputPath = inputPath;
        return this;
    }

    public CellposeDistributedTaskSettings setOutputPath(String outputPath) {
        this.outputPath = outputPath;
        return this;
    }

    public CellposeDistributedTaskSettings setModel(String model) {
        this.model = model;
        return this;
    }

    public CellposeDistributedTaskSettings setDiameter(double diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeDistributedTaskSettings setChannels(int ch1, int ch2) {
        this.ch1 = ch1;
        this.ch2 = ch2;
        return this;
    }

    public CellposeDistributedTaskSettings setBlocksize(String blocksize) {
        this.blocksize = blocksize;
        return this;
    }

    public CellposeDistributedTaskSettings setNWorkers(int n_workers) {
        this.n_workers = n_workers;
        return this;
    }

    public CellposeDistributedTaskSettings setFlowThreshold(double flow_threshold) {
        this.flow_threshold = flow_threshold;
        return this;
    }

    public CellposeDistributedTaskSettings setCellprobThreshold(double cellprob_threshold) {
        this.cellprob_threshold = cellprob_threshold;
        return this;
    }

    public CellposeDistributedTaskSettings setStitchThreshold(double stitch_threshold) {
        this.stitch_threshold = stitch_threshold;
        return this;
    }

    public CellposeDistributedTaskSettings setMinSize(int min_size) {
        this.min_size = min_size;
        return this;
    }

    public CellposeDistributedTaskSettings setMinIntensity(String minIntensity) {
        this.min_intensity = minIntensity;
        return this;
    }

    public CellposeDistributedTaskSettings setUseGpu(boolean use_gpu) {
        this.use_gpu = use_gpu;
        return this;
    }

    public CellposeDistributedTaskSettings setOptimizeParallel(boolean optimize_parallel) {
        this.optimize_parallel = optimize_parallel;
        return this;
    }

    public CellposeDistributedTaskSettings setBatchSize(int batch_size) {
        this.batch_size = batch_size;
        return this;
    }

    public CellposeDistributedTaskSettings setShowDashboard(boolean show_dashboard) {
        this.show_dashboard = show_dashboard;
        return this;
    }

    public CellposeDistributedTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }

    public CellposeDistributedTaskSettings setAnisotropy(double anisotropy) {
        this.anisotropy = anisotropy;
        return this;
    }

    public CellposeDistributedTaskSettings setGauss(double gauss) {
        this.gauss = gauss;
        return this;
    }

    public CellposeDistributedTaskSettings setMedian(int median) {
        this.median = median;
        return this;
    }

    public CellposeDistributedTaskSettings setGlobalNorm(boolean global_norm) {
        this.global_norm = global_norm;
        return this;
    }

    public CellposeDistributedTaskSettings setBsize(int bsize) {
        this.bsize = bsize;
        return this;
    }

    public CellposeDistributedTaskSettings setTileOverlap(double tile_overlap) {
        this.tile_overlap = tile_overlap;
        return this;
    }

    public CellposeDistributedTaskSettings setLogDirectory(String logDirectory) {
        this.logDirectory = logDirectory;
        return this;
    }
}
