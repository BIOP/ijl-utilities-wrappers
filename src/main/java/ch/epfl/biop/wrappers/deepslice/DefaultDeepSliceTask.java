package ch.epfl.biop.wrappers.deepslice;
import java.util.ArrayList;

/**
 * A local runner for DeepSlice
 */
public class DefaultDeepSliceTask extends DeepSliceTask {

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();

        options.add(settings.model);
        options.add(settings.input_folder);

        if ((settings.output_folder!=null)&&(!settings.output_folder.trim().equals(""))) {
            options.add("--output_folder");
            options.add(settings.output_folder);
        }

        if (settings.ensemble) options.add("--ensemble");
        if (settings.section_numbers) options.add("--section_numbers");
        if (settings.propagate_angles) options.add("--propagate_angles");
        if (settings.enforce_index_order) options.add("--enforce_index_order");
        if (settings.use_enforce_index_spacing) {
            options.add("--enforce_index_spacing");
            options.add(settings.enforce_index_spacing);
        }

        DeepSlice.execute(options, null);
    }
}
