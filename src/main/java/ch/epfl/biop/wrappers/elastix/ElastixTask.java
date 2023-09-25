package ch.epfl.biop.wrappers.elastix;

/**
 * Elastix task with builder pattern
 * Allows the composition of several consecutive registrations
 */

public interface ElastixTask {
    void run(ElastixTaskSettings settings) throws Exception;
}