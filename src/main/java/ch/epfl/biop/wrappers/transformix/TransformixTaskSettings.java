package ch.epfl.biop.wrappers.transformix;

import java.util.function.Supplier;

public class TransformixTaskSettings {

    public Supplier<String> imagePathSupplier,
            outputFolderSupplier,
            transformFileSupplier,
            inputPtsFileSupplier;

    int nThreads;

    public String taskInfo; // extra field for task specific info -> metadata for remote processing

    public boolean verbose = false;

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

    public TransformixTaskSettings verbose() {
        this.verbose = true;
        return this;
    }


}
