package ch.epfl.biop.wrappers.elastix;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DefaultElastixTask extends ElastixTask {

    @Override
    public void run() throws Exception {
        ArrayList<String> options = new ArrayList<>();
        options.add("-threads");options.add(""+settings.nThreads);
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

        Elastix.execute(options, settings.verbose);
    }

}
