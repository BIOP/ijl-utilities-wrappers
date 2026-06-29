package ch.epfl.biop.wrappers.transformix;

import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
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
 * folder contains {@code result.tif} for image transforms, or {@code outputpoints.txt}
 * for point/ROI transforms.</p>
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

        if (!hasImage && !hasPts) {
            throw new IllegalArgumentException(
                    "No image or points provided for transformation. " +
                    "Set an image or points path in TransformixTaskSettings.");
        }

        final Map<String, Object> inputs = new HashMap<>();
        inputs.put("transform_file", transformFile);
        inputs.put("output_folder", outputFolder);
        inputs.put("n_threads", nThreads);

        String script;
        if (hasImage) {
            inputs.put("image_path", imagePath.replace("\\", "/"));
            script = getImageScript();
        } else {
            inputs.put("pts_file", ptsPath.replace("\\", "/"));
            script = getPointsScript();
        }

        // Borrow a warm worker from the shared itk-elastix pool. The worker's init script
        // (ApposeElastixTask.getInitScript) already ran 'import itk'/'import os' once, before
        // the ITK thread pool was built and with the threading env in place — so the task script
        // must NOT import itk itself. Importing itk lazily inside a task, in a parallel context,
        // races on submodule loading ('module itk has no attribute ...') and skips the threading
        // setup; reusing the warm worker is what avoids that.
        Service worker = ApposeElastixTask.borrowWorker();
        boolean reusable = false;
        try {
            final Service.Task task = worker.task(script, inputs);
            if (settings.verbose) {
                task.listen(evt -> System.out.println("[itk-transformix] " + evt.message));
            }
            task.waitFor(); // auto-starts; throws TaskException if the worker reports failure
            reusable = true;
        } finally {
            // Keep a healthy worker warm for reuse; discard (and let the pool respawn) one
            // that errored, since its interpreter state may be compromised.
            if (reusable) ApposeElastixTask.returnWorker(worker);
            else ApposeElastixTask.discardWorker(worker);
        }
    }


    private static String loadParamsSnippet() {
        return ""
                + "task.update('Loading transform parameters...')\n"
                + "param_object = itk.ParameterObject.New()\n"
                + "param_object.ReadParameterFile(transform_file)\n"
                + "\n"
                + "for i in range(param_object.GetNumberOfParameterMaps()):\n"
                + "    pm = param_object.GetParameterMap(i)\n"
                + "    pm['ResultImagePixelType'] = ['float']\n"
                + "    if n_threads > 0:\n"
                + "        pm['NumberOfThreads'] = [str(n_threads)]\n"
                + "    param_object.SetParameterMap(i, pm)\n"
                + "\n";
    }

    private static String getImageScript() {
        return loadParamsSnippet()
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

    private static String getPointsScript() {
        return loadParamsSnippet()
                + "import numpy as np\n"
                + "\n"
                // Create a small dummy image — transformix_filter requires a moving image
                + "dummy = itk.image_from_array(np.zeros((10, 10), dtype=np.float32))\n"
                + "\n"
                + "task.update(f'Transforming points from {pts_file}...')\n"
                + "result = itk.transformix_filter(\n"
                + "    dummy,\n"
                + "    param_object,\n"
                + "    fixed_point_set_file_name=pts_file,\n"
                + "    output_directory=output_folder,\n"
                + ")\n"
                + "\n"
                + "task.update('done.')\n";
    }

}