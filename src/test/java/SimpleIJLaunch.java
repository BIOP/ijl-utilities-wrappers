import net.imagej.ImageJ;

public class SimpleIJLaunch {

    final public static void main(String... args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }
}
