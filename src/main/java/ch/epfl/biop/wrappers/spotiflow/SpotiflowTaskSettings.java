package ch.epfl.biop.wrappers.spotiflow;

import ch.epfl.biop.wrappers.omnipose.OmniposeTaskSettings;

public class SpotiflowTaskSettings {

    String envPath;
    String envType = "conda";
    String datasetDir;
    String additional_flags = "";

    public SpotiflowTaskSettings setEnvPath(String envPath) {
        this.envPath = envPath;
        return this;
    }

    public SpotiflowTaskSettings setEnvType(String envType) {
        this.envType = envType;
        return this;
    }

    public SpotiflowTaskSettings setDatasetDir(String datasetDir) {
        this.datasetDir = datasetDir;
        return this;
    }

    public SpotiflowTaskSettings setAdditionalFlags(String additional_flags) {
        this.additional_flags = additional_flags;
        return this;
    }
}
