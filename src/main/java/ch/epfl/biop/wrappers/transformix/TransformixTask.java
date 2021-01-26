package ch.epfl.biop.wrappers.transformix;

abstract public class TransformixTask {

    protected TransformixTaskSettings settings;

    public void setSettings(TransformixTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;

}