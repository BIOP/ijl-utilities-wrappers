package ch.epfl.biop.wrappers.transformix;

import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Appose-based implementation of {@link TransformixTask} using itk-elastix.
 *
 * <p>Drop-in replacement for {@link DefaultTransformixTask}. Instead of shelling out to the
 * transformix CLI executable, this runs transformation in a Python process via Appose using
 * the {@code itk-elastix} library. No external transformix binary or PATH configuration needed.</p>
 *
 * <p>The output contract is identical to the CLI version: after {@link #run}, the output
 * folder contains {@code result.tif} for image transforms.</p>
 *
 * <p><b>Current limitation:</b> point/ROI transformation is not yet supported.
 * Image transformation only for now.</p>
 */
public class ApposeTransformixTask implements TransformixTask {

    @Override
    public void run(TransformixTaskSettings settings) throws Exception {

        String imagePath = settings.imagePathSupplier.get();
        String ptsPath = settings.inputPtsFileSupplier.get();
        String transformFile = settings.transformFileSupplier.get().replace("\\", "/");
        String outputFolder = settings.outputFolderSupplier.get().replace("\\", "/");
        int nThreads = settings.nThreads;

        boolean hasImage = imagePath != null && !imagePath.isEmpty();
        boolean hasPts = ptsPath != null && !ptsPath.isEmpty();

        if (hasPts) {
            throw new UnsupportedOperationException(
                    "Point/ROI transformation is not yet supported by ApposeTransformixTask. " +
                    "Use DefaultTransformixTask (CLI) for point transformations.");
        }

        if (!hasImage) {
            throw new IllegalArgumentException(
                    "No image provided for transformation. Set an image path in TransformixTaskSettings.");
        }

        imagePath = imagePath.replace("\\", "/");

        // --- Build the Appose environment ---
        /*final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();*/

        // --- Run transformation in Python ---
        //try (
            Service python = ApposeElastixTask.getElastixApposeService();//env.python().init(callImports());// {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("image_path", imagePath);
            inputs.put("transform_file", transformFile);
            inputs.put("output_folder", outputFolder);
            inputs.put("n_threads", nThreads);

            final Service.Task task = python.task(getScript(), inputs);
            if (settings.verbose) {
                task.listen(evt -> System.out.println("[itk-transformix] " + evt.message));
            }
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("itk-transformix transformation failed: " + task.error);
            }
        //}
    }

    private static String callImports() {
        return ""
                + "import itk\n"
                + "import os\n";
    }

    private static String getScript() {
        return ""
                + "task.update('Loading transform parameters...')\n"
                + "param_object = itk.ParameterObject.New()\n"
                + "param_object.ReadParameterFile(transform_file)\n"
                + "\n"
                // Override ResultImagePixelType to float for consistency
                + "for i in range(param_object.GetNumberOfParameterMaps()):\n"
                + "    pm = param_object.GetParameterMap(i)\n"
                + "    pm['ResultImagePixelType'] = ['float']\n"
                + "    if n_threads > 0:\n"
                + "        pm['NumberOfThreads'] = [str(n_threads)]\n"
                + "    param_object.SetParameterMap(i, pm)\n"
                + "\n"
                + "task.update('Loading image...')\n"
                + "moving = itk.imread(image_path, itk.F)\n"
                + "\n"
                + "task.update('Running transformix...')\n"
                + "result = itk.transformix_filter(moving, param_object)\n"
                + "\n"
                + "output_path = os.path.join(output_folder, 'result.tif')\n"
                + "task.update(f'Writing result to {output_path}...')\n"
                + "itk.imwrite(result, output_path)\n"
                + "\n"
                + "task.update('done.')\n";
    }

    static volatile Service CACHED_SERVICE = null;


}