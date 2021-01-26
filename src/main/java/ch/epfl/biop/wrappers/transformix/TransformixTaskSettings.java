package ch.epfl.biop.wrappers.transformix;

import java.util.function.Supplier;

public class TransformixTaskSettings {

    public Supplier<String> imagePathSupplier,
            outputFolderSupplier,
            transformFileSupplier,
            inputPtsFileSupplier;

    int nThreads=-1;

    public TransformixTaskSettings() {
        transformFileSupplier = () -> "";
        imagePathSupplier =() -> "";
        outputFolderSupplier=() -> "";
        inputPtsFileSupplier=() -> "";
        nThreads=-1;
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
