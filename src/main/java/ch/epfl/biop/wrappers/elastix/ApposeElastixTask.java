package ch.epfl.biop.wrappers.elastix;

import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <h2>Parallel throughput model</h2>
 *
 * <p>The CLI is fast for many-small-jobs workloads because <em>each job is a separate OS
 * process</em> with a fully isolated ITK thread pool, allocator, and threader state. This
 * class mirrors that with a pool of <b>warm, isolated, single-threaded worker processes</b>
 * (see {@link #borrowWorker()} / {@link #returnWorker(Service)}):</p>
 * <ul>
 *   <li>Each worker is a persistent Appose Python process that imports {@code itk} <b>once</b>
 *       and processes registrations one at a time — amortizing the (non-trivial) {@code import itk}
 *       cost while keeping CLI-like process isolation.</li>
 *   <li>ITK threading state is fixed <b>at import time</b> in the init script
 *       ({@link #getInitScript()}): the {@code Pool} threader (never {@code Platform}/{@code TBB}
 *       for small images) and {@code SetGlobalDefaultNumberOfThreads(1)}, both <em>before</em> the
 *       ITK thread pool is constructed lazily on first use.</li>
 *   <li>Per-job thread count is set via {@code ElastixRegistrationMethod.SetNumberOfThreads(...)}
 *       — elastix silently ignores {@code NumberOfThreads} as a parameter-map key.</li>
 *   <li>Default is <b>1 thread per job</b> with parallelism <em>across</em> jobs
 *       ({@link #POOL_SIZE} concurrent workers), which is what saturates the CPU for a throughput
 *       batch without oversubscription.</li>
 * </ul>
 *
 * <p>Rationale and benchmarks:
 * <a href="https://discourse.itk.org/t/8x-slower-registration-with-itk-elastix-python-api-vs-elastix-cli-minimal-reproducible-example/7736">ITK Discourse thread</a>.</p>
 */
public class ApposeElastixTask implements ElastixTask {

    /**
     * Number of concurrent warm worker processes. For a throughput batch the sweet spot is
     * roughly the number of <em>physical</em> cores, each running a single ITK thread. The JVM
     * only exposes logical processors, so this defaults to {@link Runtime#availableProcessors()};
     * on hyperthreaded machines you may get closer to CLI throughput by lowering it to the
     * physical-core count, e.g. {@code -Delastix.appose.workers=8}.
     */
    public static volatile int POOL_SIZE = Integer.getInteger(
            "elastix.appose.workers",
            Math.max(1, Runtime.getRuntime().availableProcessors()));

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

        // Dispatch onto a warm, isolated, single-threaded worker process. Each concurrent run()
        // borrows its own process, so registrations never contend in shared Python/ITK state,
        // which avoids the parallel-batch slowdown.
        Service worker = borrowWorker();
        boolean reusable = false;
        try {
            final Service.Task task = worker.task(getScript(), inputs);
            if (settings.verbose) {
                task.listen(evt -> System.out.println("[itk-elastix] " + evt.message));
            }
            task.waitFor(); // auto-starts; throws TaskException if the worker reports failure
            reusable = true;
        } finally {
            // Keep a healthy worker warm for reuse; discard (and let the pool respawn) one
            // that errored, since its interpreter state may be compromised.
            if (reusable) returnWorker(worker);
            else discardWorker(worker);
        }
    }

    /**
     * Worker initialization script, run <b>once</b> per worker process before any task.
     *
     * <p>Fixes ITK threading state at import time, which is the only point where it is honored:
     * the ITK thread pool is built lazily on first use from these globals.</p>
     * <ul>
     *   <li>{@code ITK_GLOBAL_DEFAULT_THREADER=Pool} — for small (≈256px) images Pool beats
     *       Platform, and TBB must be avoided (it defaults to ~1024 work units, pure overhead here).</li>
     *   <li>{@code SetGlobalDefaultNumberOfThreads(1)} — one ITK thread per process; parallelism
     *       comes from running many worker processes, not many threads per process.</li>
     * </ul>
     */
    private static String getInitScript() {
        return ""
                + "import os\n"
                // Must be set before 'import itk' so the threader choice is in effect when the
                // ITK thread pool is constructed lazily on first registration.
                + "os.environ['ITK_GLOBAL_DEFAULT_THREADER'] = 'Pool'\n"
                + "import itk\n"
                // Single-threaded ITK per process: parallelism is across worker processes.
                + "itk.MultiThreaderBase.SetGlobalDefaultNumberOfThreads(1)\n";
    }

    private static String getScript() {
        return ""
                + "import shutil\n"
                + "import tempfile\n"
                + "import sys\n"
                + "import os\n"
                + "import itk\n"
                + "\n"
                // Report both logical and physical core counts for diagnostics.
                // psutil.cpu_count(logical=False) gives physical cores; os.cpu_count() includes HT.
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
                // Thread count per job. Default is 1 (throughput model: 1 thread/job, parallelism
                // across worker processes). A caller may request more via ElastixTaskSettings.nThreads,
                // in which case it is applied below through erm.SetNumberOfThreads(...).
                //
                // NB: we deliberately do NOT set pm['NumberOfThreads'] — elastix silently ignores
                // NumberOfThreads as a parameter-map key (https://discourse.itk.org/t/.../7736/16).
                // The thread count must be set on the registration object instead.
                + "    effective_threads = n_threads if n_threads > 0 else 1\n"
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
                // Set the thread count on the registration object — the API elastix actually honors.
                + "    erm.SetNumberOfThreads(effective_threads)\n"
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

    // -----------------------------------------------------------------------------------------
    // Warm worker pool
    //
    // A bounded pool (up to POOL_SIZE) of persistent, single-threaded Appose worker processes.
    // Each run() borrows one exclusively, submits one registration, and returns it warm — so the
    // 'import itk' cost is paid once per worker rather than once per registration, while every
    // concurrent job still gets the CLI-like isolation of its own OS process.
    // -----------------------------------------------------------------------------------------

    private static final BlockingDeque<Service> WORKER_POOL = new LinkedBlockingDeque<>();
    private static final AtomicInteger WORKERS_CREATED = new AtomicInteger(0);

    static {
        // Best-effort cleanup of warm worker processes on JVM shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(ApposeElastixTask::shutdownWorkers,
                "appose-elastix-pool-shutdown"));
    }

    /**
     * Borrows an idle warm worker, creating a new one if the pool has not reached {@link #POOL_SIZE},
     * otherwise blocking until one is returned.
     */
    public static Service borrowWorker() throws Exception {
        while (true) {
            Service idle = WORKER_POOL.pollFirst();
            if (idle != null) return idle;

            int created = WORKERS_CREATED.get();
            if (created < POOL_SIZE) {
                if (WORKERS_CREATED.compareAndSet(created, created + 1)) {
                    try {
                        return newWarmWorker();
                    } catch (Exception e) {
                        WORKERS_CREATED.decrementAndGet();
                        throw e;
                    }
                }
                // CAS lost to another thread — retry.
            } else {
                // At capacity: wait for a worker to be returned. Poll (rather than block forever)
                // so that if a worker was discarded after an error we loop back and respawn one.
                Service waited = WORKER_POOL.pollFirst(5, TimeUnit.SECONDS);
                if (waited != null) return waited;
            }
        }
    }

    /** Returns a healthy worker to the pool for reuse. */
    public static void returnWorker(Service worker) {
        WORKER_POOL.offerFirst(worker);
    }

    /** Discards a worker that errored, freeing a pool slot so a fresh one can be created. */
    public static void discardWorker(Service worker) {
        WORKERS_CREATED.decrementAndGet();
        try {
            worker.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Creates and starts a new warm worker process, importing itk and fixing thread state once. */
    private static Service newWarmWorker() throws Exception {
        Service worker = getElastixApposeService().init(getInitScript());
        worker.start(); // launch the process now so 'import itk' happens up front, not on first job
        return worker;
    }

    private static void shutdownWorkers() {
        Service worker;
        while ((worker = WORKER_POOL.pollFirst()) != null) {
            try {
                worker.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    static volatile Environment CACHED_ENV = null;

    public synchronized static Service getElastixApposeService() throws BuildException {

        if (CACHED_ENV == null) CACHED_ENV = Appose
                .pixi()
                .channels("conda-forge")
                .conda("python==3.11", "numpy", "psutil")
                .pypi("appose @ git+https://github.com/apposed/appose-python@e44d688e0aac65b048978ddb40e18aef5afa6c96")
                // 0.23.x+ adds GIL-release protections relevant to in-process parallelism and
                // matches-or-beats the CLI on single jobs; 0.21.0 predates those fixes.
                // (Note: the forum's "0.23.3" was never released — 0.23.0 then 0.24.0/0.25.x.)
                // See https://discourse.itk.org/t/.../7736/31
                .pypi("itk-elastix==0.25.3")
                .env("NSLOTS", "1") // See https://discourse.itk.org/t/8x-slower-registration-with-itk-elastix-python-api-vs-elastix-cli-minimal-reproducible-example/7736/15
                // Pool beats Platform for small images; never TBB (defaults to ~1024 work units).
                // The init script also sets this before 'import itk' for belt-and-suspenders.
                .env("ITK_GLOBAL_DEFAULT_THREADER", "Pool")
                .name("itk-elastix-v6")   // bump name to force rebuild after itk-elastix + threader change
                .logDebug()
                .build();

        return CACHED_ENV.python();
    }
}