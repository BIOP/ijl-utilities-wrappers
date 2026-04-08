package ch.epfl.biop.wrappers.cellpose;

public class DistributedCellposeTaskSettings {

    String envPath;
    String envType = "conda";

    String zarrInputPath;
    String tiffInputFolderPath;
    String outputPath;
    String outputFormat = "ome-tiff";
    String outputResolution = "level0";
    int resolutionLevel = -1;

    /** Cellpose 3 model name (e.g. "cyto3", "nuclei"). Ignored in Cellpose 4. */
    String modelType = "cyto3";

    /**
     * Path to a custom pre-trained model file. When set, it is used in both
     * Cellpose 3 and Cellpose 4 (overrides modelType).
     */
    String pretrainedModel = "";

    /** Cell diameter in pixels. Used when diameterUm <= 0. */
    float diameter = 30f;

    /**
     * Cell diameter in µm. When > 0, takes priority over {@code diameter} and
     * the Python script converts it to pixels using the pixel size stored in
     * the Zarr attributes (or {@code pixelSizeXyUm} if > 0).
     */
    float diameterUm = -1f;

    /**
     * XY pixel size in µm. When > 0, overrides the value stored in the Zarr
     * attributes. Useful when segmenting a pre-existing Zarr without metadata.
     */
    double pixelSizeXyUm = -1.0;

    /**
     * Z pixel size in µm. When > 0, passed to the Python script as
     * {@code --pixel_size_z_um} so that anisotropy can be computed automatically
     * from Z/XY even when the Zarr has no calibration metadata.
     */
    double pixelSizeZUm = -1.0;

    /** 0-indexed zarr channel index for the cytoplasm channel. -1 = grayscale. */
    int ch1 = -1;

    /** 0-indexed zarr channel index for the nucleus channel. -1 = none. */
    int ch2 = -1;

    /** Which zarr axis is the channel axis. -1 = last axis. */
    int channelAxis = -1;

    /**
     * Block size in voxels as a comma-separated string, e.g. "128,128,128",
     * or "auto" to derive the optimal size from available RAM and cell diameter.
     */
    String blocksize = "auto";

    int nWorkers = 4;
    int nCpus = 4;
    String memoryPerWorker = "8GB";

    boolean useGpu  = false;
    boolean do3D    = false;
    boolean noResample = false;
    boolean openDaskDashboard = true;
    float anisotropy = 1.0f;
    double cellprobThreshold = 0.0;
    int minSize = 15;
    double flow3DSmooth = 1.0;
    double cellprobSmooth = 0.0;

    /**
     * When true, the Python script auto-detects the number of workers and
     * memory per worker from the machine's CPU count and available RAM.
     * Overrides {@code nWorkers} and {@code memoryPerWorker} at runtime.
     */
    boolean autoCluster = true;

    /** Extra CLI flags appended verbatim (comma-separated). */
    String additionalFlags = "";

    public DistributedCellposeTaskSettings setEnvPath(String envPath) {
        this.envPath = envPath;
        return this;
    }

    public DistributedCellposeTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public DistributedCellposeTaskSettings setZarrInputPath(String zarrInputPath) {
        this.zarrInputPath = zarrInputPath;
        return this;
    }

    public DistributedCellposeTaskSettings setTiffInputFolderPath(String tiffInputFolderPath) {
        this.tiffInputFolderPath = tiffInputFolderPath;
        return this;
    }

    public DistributedCellposeTaskSettings setOutputPath(String outputPath) {
        this.outputPath = outputPath;
        return this;
    }

    public DistributedCellposeTaskSettings setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
        return this;
    }

    public DistributedCellposeTaskSettings setOutputResolution(String outputResolution) {
        this.outputResolution = outputResolution;
        return this;
    }

    public DistributedCellposeTaskSettings setResolutionLevel(int resolutionLevel) {
        this.resolutionLevel = resolutionLevel;
        return this;
    }

    public DistributedCellposeTaskSettings setOutputTiffPath(String outputTiffPath) {
        this.outputPath = outputTiffPath;
        this.outputFormat = "ome-tiff";
        return this;
    }

    public DistributedCellposeTaskSettings setModelType(String modelType) {
        this.modelType = modelType;
        return this;
    }

    public DistributedCellposeTaskSettings setPretrainedModel(String pretrainedModel) {
        this.pretrainedModel = pretrainedModel;
        return this;
    }

    public DistributedCellposeTaskSettings setDiameter(float diameter) {
        this.diameter = diameter;
        return this;
    }

    public DistributedCellposeTaskSettings setDiameterUm(float diameterUm) {
        this.diameterUm = diameterUm;
        return this;
    }

    public DistributedCellposeTaskSettings setPixelSizeXyUm(double pixelSizeXyUm) {
        this.pixelSizeXyUm = pixelSizeXyUm;
        return this;
    }

    public DistributedCellposeTaskSettings setPixelSizeZUm(double pixelSizeZUm) {
        this.pixelSizeZUm = pixelSizeZUm;
        return this;
    }

    public DistributedCellposeTaskSettings setChannel1(int ch1) {
        this.ch1 = ch1;
        return this;
    }

    public DistributedCellposeTaskSettings setChannel2(int ch2) {
        this.ch2 = ch2;
        return this;
    }

    public DistributedCellposeTaskSettings setChannelAxis(int channelAxis) {
        this.channelAxis = channelAxis;
        return this;
    }

    public DistributedCellposeTaskSettings setBlocksize(String blocksize) {
        this.blocksize = blocksize;
        return this;
    }

    public DistributedCellposeTaskSettings setNWorkers(int nWorkers) {
        this.nWorkers = nWorkers;
        return this;
    }

    public DistributedCellposeTaskSettings setNCpus(int nCpus) {
        this.nCpus = nCpus;
        return this;
    }

    public DistributedCellposeTaskSettings setMemoryPerWorker(String memoryPerWorker) {
        this.memoryPerWorker = memoryPerWorker;
        return this;
    }

    public DistributedCellposeTaskSettings setUseGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    public DistributedCellposeTaskSettings setDo3D(boolean do3D) {
        this.do3D = do3D;
        return this;
    }

    public DistributedCellposeTaskSettings setNoResample(boolean noResample) {
        this.noResample = noResample;
        return this;
    }

    public DistributedCellposeTaskSettings setOpenDaskDashboard(boolean openDaskDashboard) {
        this.openDaskDashboard = openDaskDashboard;
        return this;
    }

    public DistributedCellposeTaskSettings setAnisotropy(float anisotropy) {
        this.anisotropy = anisotropy;
        return this;
    }

    public DistributedCellposeTaskSettings setCellprobThreshold(double cellprobThreshold) {
        this.cellprobThreshold = cellprobThreshold;
        return this;
    }

    public DistributedCellposeTaskSettings setMinSize(int minSize) {
        this.minSize = minSize;
        return this;
    }

    public DistributedCellposeTaskSettings setFlow3DSmooth(double flow3DSmooth) {
        this.flow3DSmooth = flow3DSmooth;
        return this;
    }

    public DistributedCellposeTaskSettings setCellprobSmooth(double cellprobSmooth) {
        this.cellprobSmooth = cellprobSmooth;
        return this;
    }

    public DistributedCellposeTaskSettings setAutoCluster(boolean autoCluster) {
        this.autoCluster = autoCluster;
        return this;
    }

    public DistributedCellposeTaskSettings setAdditionalFlags(String additionalFlags) {
        this.additionalFlags = additionalFlags;
        return this;
    }
}
