package ch.epfl.biop.wrappers.elastix;

import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Appose-based implementation of {@link ElastixTask} using itk-elastix.
 *
 * <p>Drop-in replacement for {@link DefaultElastixTask}. Instead of shelling out to the
 * elastix CLI executable, this runs registration in a Python process via Appose using
 * the {@code itk-elastix} library. No external elastix binary or PATH configuration needed.</p>
 *
 * <p>The output contract is identical to the CLI version: after {@link #run}, the output
 * folder contains {@code TransformParameters.0.txt}, {@code TransformParameters.1.txt}, etc.</p>
 *
 * <p><b>Current limitation:</b> multi-channel registration (multiple fixed/moving images)
 * is not yet supported. Single-channel only for now.</p>
 */
public class ApposeElastixTask implements ElastixTask {

    @Override
    public void run(ElastixTaskSettings settings) throws Exception {

        // --- Resolve all supplier values on the Java side ---
        List<String> fixedImagePaths = new ArrayList<>();
        for (Supplier<String> s : settings.fixedImagePathSuppliers) {
            fixedImagePaths.add(s.get().replace("\\", "/"));
        }

        List<String> movingImagePaths = new ArrayList<>();
        for (Supplier<String> s : settings.movingImagePathSuppliers) {
            movingImagePaths.add(s.get().replace("\\", "/"));
        }

        if (fixedImagePaths.size() > 1 || movingImagePaths.size() > 1) {
            throw new UnsupportedOperationException(
                    "Multi-channel registration is not yet supported by ApposeElastixTask. " +
                    "Use DefaultElastixTask (CLI) for multi-channel images.");
        }

        List<String> parameterFiles = new ArrayList<>();
        for (Supplier<String> s : settings.transformationParameterPathSupplier) {
            parameterFiles.add(s.get().replace("\\", "/"));
        }

        String outputFolder = settings.outputFolderSupplier.get().replace("\\", "/");

        String initialTransformFile = settings.initialTransformFilePath != null
                ? settings.initialTransformFilePath.replace("\\", "/")
                : null;

        int nThreads = settings.nThreads;

        // --- Run registration in Python ---
        //try () {
            Service python = getElastixApposeService();
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("fixed_image_path", fixedImagePaths.get(0));
            inputs.put("moving_image_path", movingImagePaths.get(0));
            inputs.put("parameter_files", parameterFiles);
            inputs.put("output_folder", outputFolder);
            inputs.put("initial_transform_file", initialTransformFile);
            inputs.put("n_threads", nThreads);

            final Service.Task task = python.task(getScript(), inputs);
            if (settings.verbose) {
                task.listen(evt -> System.out.println("[itk-elastix] " + evt.message));
            }
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("itk-elastix registration failed: " + task.error);
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
                + "import shutil\n"
                + "import tempfile\n"
                + "\n"
                + "task.update('Loading images...')\n"
                + "fixed = itk.imread(fixed_image_path, itk.F)\n"
                + "moving = itk.imread(moving_image_path, itk.F)\n"
                + "\n"
                // Run each registration stage separately, using a temp subdirectory per stage
                // so itk-elastix doesn't overwrite previous results.
                + "prev_transform_path = initial_transform_file\n"
                + "\n"
                + "for stage_idx, pf in enumerate(parameter_files):\n"
                + "    task.update(f'Stage {stage_idx}: loading parameters from {os.path.basename(pf)}...')\n"
                + "    param_obj = itk.ParameterObject.New()\n"
                + "    param_obj.ReadParameterFile(pf)\n"
                + "\n"
                + "    pm = param_obj.GetParameterMap(0)\n"
                + "    if n_threads > 0:\n"
                + "        pm['MaximumNumberOfSamplingAttempts'] = ['5']\n"
                + "        pm['NumberOfThreads'] = [str(n_threads)]\n"
                + "    param_obj.SetParameterMap(0, pm)\n"
                + "\n"
                // Use a temp directory for this stage's output
                + "    stage_dir = tempfile.mkdtemp(prefix=f'elastix_stage{stage_idx}_')\n"
                + "\n"
                + "    task.update(f'Stage {stage_idx}: running registration...')\n"
                + "    kwargs = dict(\n"
                + "        fixed_image=fixed,\n"
                + "        moving_image=moving,\n"
                + "        parameter_object=param_obj,\n"
                + "        output_directory=stage_dir,\n"
                + "        log_to_console=False\n"
                + "    )\n"
                + "    if prev_transform_path is not None:\n"
                + "        kwargs['initial_transform_parameter_file_name'] = prev_transform_path\n"
                + "\n"
                + "    result_image, result_params = itk.elastix_registration_method(**kwargs)\n"
                + "\n"
                // Move TransformParameters.0.txt from stage temp dir to final location
                + "    src = os.path.join(stage_dir, 'TransformParameters.0.txt')\n"
                + "    dst = os.path.join(output_folder, f'TransformParameters.{stage_idx}.txt')\n"
                + "    shutil.move(src, dst)\n"
                + "\n"
                // Post-process the transform file to fix compatibility with CLI transformix:
                // 1. Fix InitialTransformParametersFileName path (itk-elastix wrote temp dir path)
                // 2. Normalize "float32" -> "float" (itk-elastix writes "float32", CLI expects "float")
                + "    with open(dst, 'r') as f:\n"
                + "        content = f.read()\n"
                + "\n"
                // Fix InitialTransformParametersFileName to point to the renamed file in output_folder
                + "    if stage_idx > 0 and prev_transform_path is not None:\n"
                + "        prev_final = os.path.join(output_folder, f'TransformParameters.{stage_idx - 1}.txt')\n"
                // Replace both forward-slash and backslash variants of the old path
                + "        content = content.replace(prev_transform_path.replace(os.sep, '/'), prev_final)\n"
                + "        content = content.replace(prev_transform_path.replace('/', os.sep), prev_final)\n"
                + "        content = content.replace(prev_transform_path, prev_final)\n"
                + "\n"
                // Normalize itk-elastix pixel type names to CLI elastix names
                + "    content = content.replace('\"float32\"', '\"float\"')\n"
                + "    content = content.replace('\"int16\"', '\"short\"')\n"
                + "    content = content.replace('\"uint16\"', '\"unsigned short\"')\n"
                + "    content = content.replace('\"uint8\"', '\"unsigned char\"')\n"
                + "    content = content.replace('\"int8\"', '\"char\"')\n"
                + "\n"
                + "    with open(dst, 'w') as f:\n"
                + "        f.write(content)\n"
                + "\n"
                + "    prev_transform_path = dst\n"
                + "    shutil.rmtree(stage_dir, ignore_errors=True)\n"
                + "    task.update(f'Stage {stage_idx}: wrote {os.path.basename(dst)}')\n"
                + "\n"
                + "task.update('done.')\n";
    }

    static volatile Service CACHED_SERVICE = null;

    public synchronized static Service getElastixApposeService() throws BuildException {
        // Already exists ? Reuse it.
        if (CACHED_SERVICE!=null) return CACHED_SERVICE;

        // --- Build the Appose environment ---
        final Environment env = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy")
                .pypi("itk-elastix")
                .name("itk-elastix-v0")
                .logDebug()
                .build();

        CACHED_SERVICE = env.python().init(callImports());
        return CACHED_SERVICE;
    }
}