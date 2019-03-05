package ch.epfl.biop.wrappers.transformix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Supplier;

public class TransformixTask implements Runnable {
    private Supplier<String>
            transformFileSupplier,
            imagePathSupplier,
            outputFolderSupplier,
            inputPtsFileSupplier;

    public TransformixTask(TransformixTaskBuilder builder) {
        this.imagePathSupplier =builder.imagePathSupplier;
        this.outputFolderSupplier=builder.outputFolderSupplier;
        this.transformFileSupplier=builder.transformFileSupplier;
        this.inputPtsFileSupplier=builder.inputPtsFileSupplier;
    }

    public void run() {
        ArrayList<String> options = new ArrayList<>();
        if (!imagePathSupplier.get().equals("")) {
            options.add("-in");options.add(imagePathSupplier.get());
        }
        if (!inputPtsFileSupplier.get().equals("")) {
            options.add("-def");options.add(inputPtsFileSupplier.get());
        }
        options.add("-out");options.add(outputFolderSupplier.get());
        options.add("-tp");options.add(transformFileSupplier.get());
        try {
			Transformix.execute(options);
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
    }

    public static class TransformixTaskBuilder {

        private Supplier<String> imagePathSupplier,
                outputFolderSupplier,
                transformFileSupplier,
                inputPtsFileSupplier;

        int nThreads=-1;

        public TransformixTaskBuilder() {
            transformFileSupplier = () -> "";
            imagePathSupplier =() -> "";
            outputFolderSupplier=() -> "";
            inputPtsFileSupplier=() -> "";
            nThreads=-1;
        }

        public TransformixTaskBuilder image(Supplier<String> mImgSupplier) {
            this.imagePathSupplier = mImgSupplier;
            return this;
        }

        public TransformixTaskBuilder pts(Supplier<String> ptsSupplier) {
            this.inputPtsFileSupplier = ptsSupplier;
            return this;
        }

        public TransformixTaskBuilder transform(Supplier<String> transformSupplier) {
            transformFileSupplier = transformSupplier;
            return this;
        }

        public TransformixTaskBuilder outFolder(Supplier<String> outputFolderSupplier) {
            this.outputFolderSupplier = outputFolderSupplier;
            return this;
        }

        public TransformixTask build() {
            return new TransformixTask(this);
        }

    }

}