package ch.epfl.biop.wrappers.cellpose;

abstract public class DistributedCellposeTask {

    protected DistributedCellposeTaskSettings settings;

    public void setSettings(DistributedCellposeTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}
