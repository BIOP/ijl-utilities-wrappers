package ch.epfl.biop.wrappers.elastix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Elastix task with buider pattern
 * Allows the composition of several consecutive registrations
 */

public class ElastixTask implements Runnable {

    private Supplier<String> fixedImagePathSupplier,
                     movingImagePathSupplier,
                     outputFolderSupplier;
    private String initialTransformFilePath;

    ArrayList<Supplier<String>> transformationParameterPathSupplier;

    public ElastixTask(ElastixTaskBuilder builder) {
        this.fixedImagePathSupplier=builder.fixedImagePathSupplier;
        this.movingImagePathSupplier=builder.movingImagePathSupplier;
        this.outputFolderSupplier=builder.outputFolderSupplier;
        this.transformationParameterPathSupplier=builder.transformationParameterPathSupplier;
        this.initialTransformFilePath=builder.initialTransformFilePath;
    }

    public void run() {
        ArrayList<String> options = new ArrayList<>();
        options.add("-f");options.add(fixedImagePathSupplier.get());
        options.add("-m");options.add(movingImagePathSupplier.get());
        if (initialTransformFilePath!=null) {
            options.add("-t0");options.add(initialTransformFilePath);
        }
        for (Supplier<String> s : transformationParameterPathSupplier) {
            options.add("-p");
            options.add(s.get());
        }
        options.add("-out");
        options.add(outputFolderSupplier.get());
        //System.out.println(options);
        try {
        	Elastix.execute(options);
        } catch (IOException|InterruptedException e) {
        	e.printStackTrace();
        }
    }

    public static class ElastixTaskBuilder {

        private Supplier<String> fixedImagePathSupplier,
                         movingImagePathSupplier,
                         outputFolderSupplier;

        private String initialTransformFilePath;

        int nThreads=-1;

        private ArrayList<Supplier<String>> transformationParameterPathSupplier;

        public ElastixTaskBuilder() {
            transformationParameterPathSupplier = new ArrayList<>();
            nThreads=-1;
        }

        public ElastixTaskBuilder fixedImage(Supplier<String> fImgSupplier) {
            this.fixedImagePathSupplier = fImgSupplier;
            return this;
        }

        public ElastixTaskBuilder movingImage(Supplier<String> mImgSupplier) {
            this.movingImagePathSupplier = mImgSupplier;
            return this;
        }

        public ElastixTaskBuilder addTransform(Supplier<String> transformSupplier) {
            transformationParameterPathSupplier.add(transformSupplier);
            return this;
        }

        public ElastixTaskBuilder outFolder(Supplier<String> outputFolderSupplier) {
            this.outputFolderSupplier = outputFolderSupplier;
            return this;
        }

        public ElastixTaskBuilder addInitialTransform(String initialTransformFilePath) {
            this.initialTransformFilePath = initialTransformFilePath;
            return this;
        }

        public ElastixTask build() {
            return new ElastixTask(this);
        }

    }

}