package ch.epfl.biop.wrappers.elastix;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class ElastixTaskSettings {
    public List<Supplier<String>> fixedImagePathSuppliers = new ArrayList<>();
    public List<Supplier<String>> movingImagePathSuppliers = new ArrayList<>();
    public Supplier<String> outputFolderSupplier;

    public  String initialTransformFilePath;

    public int nThreads=1;

    public ArrayList<Supplier<String>> transformationParameterPathSupplier;

    public String taskInfo; // extra field for task specific info -> metadata for remote processing

    public boolean verbose = false;

    public ElastixTaskSettings() {
        transformationParameterPathSupplier = new ArrayList<>();
        nThreads=1;
    }

    public ElastixTaskSettings singleThread() {
        this.nThreads = 1;
        return this;
    }

    public ElastixTaskSettings nThreads(int nThreads) {
        this.nThreads = nThreads;
        return this;
    }

    public ElastixTaskSettings fixedImage(Supplier<String> fImgSupplier) {
        this.fixedImagePathSuppliers.add(fImgSupplier);
        return this;
    }

    public ElastixTaskSettings movingImage(Supplier<String> mImgSupplier) {
        this.movingImagePathSuppliers.add(mImgSupplier);
        return this;
    }

    public ElastixTaskSettings addTransform(Supplier<String> transformSupplier) {
        transformationParameterPathSupplier.add(transformSupplier);
        return this;
    }

    public ElastixTaskSettings outFolder(Supplier<String> outputFolderSupplier) {
        this.outputFolderSupplier = outputFolderSupplier;
        return this;
    }

    public ElastixTaskSettings addInitialTransform(String initialTransformFilePath) {
        this.initialTransformFilePath = initialTransformFilePath;
        return this;
    }

    public ElastixTaskSettings verbose() {
        this.verbose = true;
        return this;
    }

}
