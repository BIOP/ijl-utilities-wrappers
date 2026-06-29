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
 * they are passed to itk-elastix via {@code ElastixRegistrationMethod.AddFixedImage(...)}.</p>
 *
 * <p>Registrations are dispatched onto a pool of warm, single-threaded worker processes
 * ({@link #borrowWorker()} / {@link #returnWorker(Service)}). Each worker imports {@code itk}
 * once and processes registrations one at a time, so the import cost is amortized while every
 * concurrent job keeps the isolation of its own process. Parallelism comes from running
 * {@link #POOL_SIZE} workers, each single-threaded, rather than many threads per process.</p>
 */
public class ApposeElastixTask implements ElastixTask {

    /**
     * Number of concurrent warm worker processes. Defaults to {@link Runtime#availableProcessors()};
     * override with {@code -Delastix.appose.workers=N} (e.g. the physical-core count on
     * hyperthreaded machines).
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
        // borrows its own process, so registrations never contend in shared Python/ITK state.
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
     * Worker initialization script, run once per worker process before any task.
     * Fixes ITK threading state at import time (the only point where it is honored): the {@code Pool}
     * threader and a single ITK thread per process. Parallelism comes from running many workers.
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
                + "import os\n"
                + "import itk\n"
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
                // Thread count per job. Default is 1 (throughput model: 1 thread/job, parallelism
                // across worker processes). A caller may request more via ElastixTaskSettings.nThreads.
                // NB: we deliberately do NOT set pm['NumberOfThreads'] — elastix silently ignores
                // NumberOfThreads as a parameter-map key. The thread count is set on the registration
                // object below via SetNumberOfThreads(...).
                + "    effective_threads = n_threads if n_threads > 0 else 1\n"
                + "\n"
                + "    stage_dir = tempfile.mkdtemp(prefix=f'elastix_stage{stage_idx}_')\n"
                + "    task.update(f'Stage {stage_idx}: running registration ({len(fixed_images)} channel(s))...')\n"
                + "\n"
                // Use the object API for all cases (single- and multi-channel alike).
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
                + "    erm.SetNumberOfThreads(effective_threads)\n"
                + "    erm.SetOutputDirectory(stage_dir)\n"
                + "    erm.SetLogToConsole(False)\n"
                + "    if prev_transform_path is not None:\n"
                + "        erm.SetInitialTransformParameterFileName(prev_transform_path)\n"
                + "    erm.UpdateLargestPossibleRegion()\n"
                + "\n"
                // Move TransformParameters.0.txt from stage temp dir to final location
                + "    src = os.path.join(stage_dir, 'TransformParameters.0.txt')\n"
                + "    dst = os.path.join(output_folder, f'TransformParameters.{stage_idx}.txt')\n"
                + "    shutil.move(src, dst)\n"
                + "\n"
                // Post-process the transform file to fix compatibility with CLI transformix:
                // 1. Fix InitialTransformParametersFileName path (itk-elastix wrote temp dir path)
                // 2. Normalize itk-elastix pixel-type names to CLI elastix names
                + "    with open(dst, 'r') as f:\n"
                + "        content = f.read()\n"
                + "\n"
                + "    if stage_idx > 0 and prev_transform_path is not None:\n"
                + "        prev_final = os.path.join(output_folder, f'TransformParameters.{stage_idx - 1}.txt')\n"
                + "        content = content.replace(prev_transform_path.replace(os.sep, '/'), prev_final)\n"
                + "        content = content.replace(prev_transform_path.replace('/', os.sep), prev_final)\n"
                + "        content = content.replace(prev_transform_path, prev_final)\n"
                + "\n"
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
    // concurrent job still gets the isolation of its own OS process.
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
                .conda("python==3.11", "numpy")
                .pypi("appose @ git+https://github.com/apposed/appose-python@e44d688e0aac65b048978ddb40e18aef5afa6c96")
                .pypi("itk-elastix==0.25.3")
                .env("NSLOTS", "1")
                // Pool beats Platform for small images; never TBB (defaults to ~1024 work units).
                // The init script also sets this before 'import itk' for belt-and-suspenders.
                .env("ITK_GLOBAL_DEFAULT_THREADER", "Pool")
                .name("itk-elastix-v6")
                .logDebug()
                .build();

        return CACHED_ENV.python();
    }
}