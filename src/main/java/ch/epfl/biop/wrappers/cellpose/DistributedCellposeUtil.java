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

    /** Names of all helper resources that must be co-located with the main script. */
    private static final String[] HELPER_SCRIPTS = {
        "worker_patches.py"
    };

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
            System.out.println("Cellpose Distributed script copied to: " + destinationPath);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        // Copy helper scripts that the main script depends on at runtime
        for (String helper : HELPER_SCRIPTS) {
            try {
                InputStream helperStream = DistributedCellposeUtil.class.getResourceAsStream("/" + helper);
                if (helperStream != null) {
                    Path helperDest = Paths.get(envDirPath, helper);
                    Files.copy(helperStream, helperDest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                // Non-fatal: log but continue; the main script has an inline fallback
                System.err.println("Warning: could not copy helper script " + helper + ": " + e.getMessage());
            }
        }

        return f.exists();
    }
}
