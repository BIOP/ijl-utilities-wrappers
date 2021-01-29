package ch.epfl.biop.wrappers.elastix;

/**
 * Elastix task with builder pattern
 * Allows the composition of several consecutive registrations
 */

abstract public class ElastixTask {

    public ElastixTaskSettings settings;

    public void setSettings(ElastixTaskSettings settings) {
        this.settings = settings;
    }

    abstract public void run() throws Exception;

}