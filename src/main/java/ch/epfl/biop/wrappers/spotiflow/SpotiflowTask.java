package ch.epfl.biop.wrappers.spotiflow;

abstract public class SpotiflowTask {
    protected SpotiflowTaskSettings settings;

    public void setSettings(SpotiflowTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}