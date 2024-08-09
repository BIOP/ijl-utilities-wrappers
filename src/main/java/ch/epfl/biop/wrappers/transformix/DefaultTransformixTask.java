package ch.epfl.biop.wrappers.transformix;

import java.util.ArrayList;

public class DefaultTransformixTask implements TransformixTask {

    public void run(TransformixTaskSettings settings) throws Exception {
        ArrayList<String> options = new ArrayList<>();
        options.add("-threads");options.add(""+settings.nThreads);
        if (!settings.imagePathSupplier.get().isEmpty()) {
            options.add("-in");options.add(settings.imagePathSupplier.get());
        }
        if (!settings.inputPtsFileSupplier.get().isEmpty()) {
            options.add("-def");options.add(settings.inputPtsFileSupplier.get());
        }
        options.add("-out");options.add(settings.outputFolderSupplier.get());
        options.add("-tp");options.add(settings.transformFileSupplier.get());

        Transformix.execute(options, settings.verbose);
    }
}
