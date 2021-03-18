package ch.epfl.biop.java.utilities;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper method for temporary directories
 * Deletes all files within the directory on java exit
 */

public class TempDirectory {
    final Path path;

    public TempDirectory(String prefix) {
        try {
            path = Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Path getPath() {
        return path;
    }

    public void deleteOnExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                FileUtils.deleteDirectory(path.toFile());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }));
    }

}