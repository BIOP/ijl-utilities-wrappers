package ch.epfl.biop.wrappers.elastix;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Elastix task with buider pattern
 * Allows the composition of several consecutive registrations
 */

public class ElastixTask {

    ElastixTaskSettings settings;

    public ElastixTask(ElastixTaskSettings settings) {
        this.settings = settings;
    }

    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();
        options.add("-f");options.add(settings.fixedImagePathSupplier.get());
        options.add("-m");options.add(settings.movingImagePathSupplier.get());
        if (settings.initialTransformFilePath!=null) {
            options.add("-t0");options.add(settings.initialTransformFilePath);
        }
        for (Supplier<String> s : settings.transformationParameterPathSupplier) {
            options.add("-p");
            options.add(s.get());
        }
        options.add("-out");
        options.add(settings.outputFolderSupplier.get());
        //System.out.println(options);
        Elastix.execute(options, null);
    }

}