package ch.epfl.biop.wrappers.transformix;

import ch.epfl.biop.wrappers.elastix.ElastixTaskSettings;

import java.util.function.Supplier;

public class TransformixTaskSettings {

    public Supplier<String> imagePathSupplier,
            outputFolderSupplier,
            transformFileSupplier,
            inputPtsFileSupplier;

    int nThreads=1;

    public String taskInfo; // extra field for task specific info -> metadata for remote processing

    public TransformixTaskSettings() {
        transformFileSupplier = () -> "";
        imagePathSupplier =() -> "";
        outputFolderSupplier=() -> "";
        inputPtsFileSupplier=() -> "";
        nThreads=1;
    }

    public TransformixTaskSettings singleThread() {
        this.nThreads = 1;
        return this;
    }

    public TransformixTaskSettings nThreads(int nThreads) {
        this.nThreads = nThreads;
        return this;
    }

    public TransformixTaskSettings image(Supplier<String> mImgSupplier) {
        this.imagePathSupplier = mImgSupplier;
        return this;
    }

    public TransformixTaskSettings pts(Supplier<String> ptsSupplier) {
        this.inputPtsFileSupplier = ptsSupplier;
        return this;
    }

    public TransformixTaskSettings transform(Supplier<String> transformSupplier) {
        transformFileSupplier = transformSupplier;
        return this;
    }

    public TransformixTaskSettings outFolder(Supplier<String> outputFolderSupplier) {
        this.outputFolderSupplier = outputFolderSupplier;
        return this;
    }


}
