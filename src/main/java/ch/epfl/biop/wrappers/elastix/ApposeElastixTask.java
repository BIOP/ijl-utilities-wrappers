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
 * <p>Supports multi-channel registration: when multiple fixed/moving images are provided,
 * they are passed to itk-elastix via {@code ElastixRegistrationMethod.SetFixedImage(img, index)}.</p>
 */
public class ApposeElastixTask implements ElastixTask {

    public static int MAX_TASK_ATTEMPTS = 4;

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
        final Map<String, Object> inputs = new HashMap<>();
        inputs.put("fixed_image_paths", fixedImagePaths);
        inputs.put("moving_image_paths", movingImagePaths);
        inputs.put("parameter_files", parameterFiles);
        inputs.put("output_folder", outputFolder);
        inputs.put("initial_transform_file", initialTransformFile);
        inputs.put("n_threads", nThreads);

        // Because of https://github.com/apposed/appose/issues/15 in windows

        try (Service python = getElastixApposeService().init(callImports())) {

            final Service.Task task = python.task(getScript(), inputs);

            if (settings.verbose) {
                task.listen(evt -> System.out.println("[itk-elastix] " + evt.message));
            }
            task.start();
            task.waitFor();
        }

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
                + "import sys\n"
                + "import os\n"
                + "import itk\n"
                + "\n"
                // Report both logical and physical core counts.
                // psutil.cpu_count(logical=False) gives physical cores; os.cpu_count() includes HT.
                // On an HT system, os.cpu_count() == 2× physical, and passing all logical CPUs to
                // ITK's thread pool can hurt BSpline performance (cache thrashing on dense Jacobian
                // accumulation). We default to physical cores if psutil is available.
                + "try:\n"
                + "    import psutil as _psutil\n"
                + "    _physical_cores = _psutil.cpu_count(logical=False) or os.cpu_count()\n"
                + "except ImportError:\n"
                + "    _physical_cores = None\n"
                + "print(f'[itk-elastix] Python {sys.version}, logical_cpus={os.cpu_count()}, physical_cores={_physical_cores}')\n"
                + "try:\n"
                + "    import itk_elastix; print(f'[itk-elastix] itk-elastix pkg version: {itk_elastix.__version__}')\n"
                + "except Exception: pass\n"
                // Report GIL status: sys._is_gil_enabled() is only available in Python 3.13+
                // Free-threaded Python (GIL disabled) requires: python-freethreading conda package
                // + PYTHON_GIL=0 env var at launch. Note: itk-elastix PyPI wheels may not yet
                // provide cp313t (free-threaded) builds; ITK C++ already releases the GIL anyway.
                + "try:\n"
                + "    gil_active = sys._is_gil_enabled()\n"
                + "except AttributeError:\n"
                + "    gil_active = True  # Python < 3.13: GIL always active\n"
                + "task.update(f'GIL active: {gil_active}')\n"
                + "\n"
                + "task.update(f'Loading {len(fixed_image_paths)} fixed and {len(moving_image_paths)} moving image(s)...')\n"
                + "fixed_images = [itk.imread(p, itk.F) for p in fixed_image_paths]\n"
                + "moving_images = [itk.imread(p, itk.F) for p in moving_image_paths]\n"
                + "multi_channel = len(fixed_images) > 1\n"
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
                // Use physical cores (not HT logical CPUs) to match what 'elastix -threads 0' does.
                // On a hyperthreaded system os.cpu_count() == 2× physical, and feeding all logical
                // CPUs to ITK's thread pool can hurt BSpline Jacobian accumulation (cache thrashing).
                // _physical_cores is set in callImports() via psutil; falls back to os.cpu_count().
                + "    _default_threads = _physical_cores if _physical_cores else os.cpu_count()\n"
                + "    effective_threads = n_threads if n_threads > 0 else _default_threads\n"
                + "    pm['NumberOfThreads'] = [str(effective_threads)]\n"
                // Do NOT add MaximumNumberOfSamplingAttempts here — CLI doesn't set it either,
                // and we want the two paths to be as identical as possible.
                + "    param_obj.SetParameterMap(0, pm)\n"
                + "    task.update(f'Stage {stage_idx}: using {effective_threads} thread(s)')\n"
                // Dump the full parameter map so we can verify itk-elastix sees the same params as the CLI.
                + "    task.update(f'Stage {stage_idx}: param dump: {dict(pm)}')\n"
                + "\n"
                // Use a temp directory for this stage's output
                + "    stage_dir = tempfile.mkdtemp(prefix=f'elastix_stage{stage_idx}_')\n"
                + "\n"
                + "    task.update(f'Stage {stage_idx}: running registration ({len(fixed_images)} channel(s))...')\n"
                + "\n"
                // Use the object API for all cases (single- and multi-channel alike).
                // SetLogToFile(True) writes elastix.log to stage_dir — read it after registration
                // to see BSpline grid schedule, per-level iteration counts, and any warnings.
                + "    ImageType = type(fixed_images[0])\n"
                + "    erm = itk.ElastixRegistrationMethod[ImageType, ImageType].New()\n"
                + "    if multi_channel:\n"
                + "        erm.SetFixedImage(fixed_images[0])\n"
                + "        for fimg in fixed_images[1:]:\n"
                + "            erm.AddFixedImage(fimg)\n"
                + "        erm.SetMovingImage(moving_images[0])\n"
                + "        for mimg in moving_images[1:]:\n"
                + "            erm.AddMovingImage(mimg)\n"
                + "    else:\n"
                + "        erm.SetFixedImage(fixed_images[0])\n"
                + "        erm.SetMovingImage(moving_images[0])\n"
                + "    erm.SetParameterObject(param_obj)\n"
                + "    erm.SetOutputDirectory(stage_dir)\n"
                + "    erm.SetLogToConsole(False)\n"
                // SetLogToFile writes stage_dir/elastix.log — captured below for diagnostics
                + "    erm.SetLogToFile(True)\n"
                + "    if prev_transform_path is not None:\n"
                + "        erm.SetInitialTransformParameterFileName(prev_transform_path)\n"
                + "    import time as _time\n"
                + "    _t0 = _time.perf_counter()\n"
                + "    erm.UpdateLargestPossibleRegion()\n"
                + "    _dt = _time.perf_counter() - _t0\n"
                + "    task.update(f'Stage {stage_idx}: UpdateLargestPossibleRegion took {_dt:.1f}s')\n"
                + "    result_params = erm.GetTransformParameterObject()\n"
                + "\n"
                // Print BSpline grid dimensions from the output transform to verify the grid
                // that itk-elastix actually built (should be ~12×12 for 256px / 20-voxel spacing).
                + "    try:\n"
                + "        out_pm = result_params.GetParameterMap(0)\n"
                + "        task.update(f'Stage {stage_idx}: result GridSize={out_pm.get(\"GridSize\",\"?\")}')\n"
                + "        task.update(f'Stage {stage_idx}: result GridSpacing={out_pm.get(\"GridSpacing\",\"?\")}')\n"
                + "    except Exception as _e:\n"
                + "        task.update(f'Stage {stage_idx}: could not read result grid info: {_e}')\n"
                + "\n"
                // Capture the elastix log (contains BSpline grid schedule, per-level timing,
                // iteration counts, and any warnings about parameter interpretation).
                + "    _log_path = os.path.join(stage_dir, 'elastix.log')\n"
                + "    if os.path.exists(_log_path):\n"
                + "        with open(_log_path, 'r', errors='replace') as _lf:\n"
                + "            task.update(f'Stage {stage_idx}: elastix.log:\\n' + _lf.read())\n"
                + "    else:\n"
                + "        task.update(f'Stage {stage_idx}: elastix.log not found in {stage_dir}')\n"
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

    static volatile Environment CACHED_ENV = null;

    public synchronized static Service getElastixApposeService() throws BuildException {

        if (CACHED_ENV == null) CACHED_ENV = Appose
                .pixi()
                .channels("conda-forge")
                .conda("appose", "python==3.11", "numpy", "psutil")
                .pypi("itk-elastix==0.21.0")
                .env("NSLOTS", "1") // See https://discourse.itk.org/t/8x-slower-registration-with-itk-elastix-python-api-vs-elastix-cli-minimal-reproducible-example/7736/15
                .env("ITK_GLOBAL_DEFAULT_THREADER", "Platform")
                .name("itk-elastix-v2")   // bump name to force rebuild after psutil addition
                .logDebug()
                .build();

        return CACHED_ENV.python();
    }
}