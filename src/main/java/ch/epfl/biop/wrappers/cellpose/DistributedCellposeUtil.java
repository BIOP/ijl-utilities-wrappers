package ch.epfl.biop.wrappers.cellpose;

import ij.IJ;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class DistributedCellposeUtil {

    public static String getScriptName() {
        return "distributed_cellpose_cli.py";
    }

    public static String getScriptPath(String envDirPath) {
        return envDirPath + File.separator + getScriptName();
    }

    public static boolean ensureScriptIsCopied(String envDirPath) {
        File f = new File(getScriptPath(envDirPath));
        // We always copy to ensure we have the latest version from the JAR
        // if (f.exists()) return true;

        String resourceFileName = getScriptName();

        try {
            InputStream inputStream = DistributedCellposeUtil.class.getResourceAsStream("/" + resourceFileName);
            if (inputStream == null) {
                throw new Exception("Cannot get resource \"" + resourceFileName + "\" from Jar file.");
            }

            Path destinationPath = Paths.get(envDirPath, resourceFileName);
            Files.copy(inputStream, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Distributed Cellpose script copied to: " + destinationPath);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return f.exists();
    }
}
