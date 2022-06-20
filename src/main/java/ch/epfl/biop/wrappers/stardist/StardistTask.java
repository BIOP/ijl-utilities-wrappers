package ch.epfl.biop.wrappers.stardist;

abstract public class StardistTask {

    protected StardistTaskSettings settings;

    public void setSettings(StardistTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;

}
