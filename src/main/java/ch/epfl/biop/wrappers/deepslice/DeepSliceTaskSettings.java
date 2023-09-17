package ch.epfl.biop.wrappers.deepslice;

/**
 * A holder class that should contain all information necessary to run a DeepSlice registration task
 */
public class DeepSliceTaskSettings {
    public String model;
    public String input_folder, output_folder;
    public boolean ensemble, section_numbers, propagate_angles, enforce_index_order;
    public int enforce_index_spacing;
}
