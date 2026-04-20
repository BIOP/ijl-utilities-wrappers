package ch.epfl.biop.wrappers.cellpose;

public class ZarrConversionTaskSettings {

    String envPath;
    String envType = "conda";
    String inputPath;
    String outputZarrPath;
    String chunks = "auto";
    String nLevels = "auto";

    // Physical pixel sizes in µm – values <= 0 mean "auto-detect from file metadata"
    double pixelSizeXUm = -1.0;
    double pixelSizeYUm = -1.0;
    double pixelSizeZUm = -1.0;

    public ZarrConversionTaskSettings setEnvPath(String envPath) {
        this.envPath = envPath;
        return this;
    }

    public ZarrConversionTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public ZarrConversionTaskSettings setInputPath(String inputPath) {
        this.inputPath = inputPath;
        return this;
    }

    public ZarrConversionTaskSettings setOutputZarrPath(String outputZarrPath) {
        this.outputZarrPath = outputZarrPath;
        return this;
    }

    public ZarrConversionTaskSettings setChunks(String chunks) {
        this.chunks = chunks;
        return this;
    }

    public ZarrConversionTaskSettings setNLevels(String nLevels) {
        this.nLevels = nLevels;
        return this;
    }

    public ZarrConversionTaskSettings setPixelSizeXUm(double pixelSizeXUm) {
        this.pixelSizeXUm = pixelSizeXUm;
        return this;
    }

    public ZarrConversionTaskSettings setPixelSizeYUm(double pixelSizeYUm) {
        this.pixelSizeYUm = pixelSizeYUm;
        return this;
    }

    public ZarrConversionTaskSettings setPixelSizeZUm(double pixelSizeZUm) {
        this.pixelSizeZUm = pixelSizeZUm;
        return this;
    }
}
