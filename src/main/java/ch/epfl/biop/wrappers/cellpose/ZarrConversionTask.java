package ch.epfl.biop.wrappers.cellpose;

abstract public class ZarrConversionTask {

    protected ZarrConversionTaskSettings settings;

    public void setSettings(ZarrConversionTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}
