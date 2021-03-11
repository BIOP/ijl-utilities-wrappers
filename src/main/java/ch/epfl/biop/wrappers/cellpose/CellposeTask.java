package ch.epfl.biop.wrappers.cellpose;

abstract public class CellposeTask {

    protected CellposeTaskSettings settings;

    public void setSettings(CellposeTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}
