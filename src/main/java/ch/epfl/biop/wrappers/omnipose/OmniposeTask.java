package ch.epfl.biop.wrappers.omnipose;

abstract public class OmniposeTask {

    protected OmniposeTaskSettings settings;

    public void setSettings(OmniposeTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}
