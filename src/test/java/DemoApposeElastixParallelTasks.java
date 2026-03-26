import ch.epfl.biop.wrappers.elastix.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Benchmarks many BSpline registrations submitted in parallel.
 *
 * <p>Submits {@code N_PARALLEL} registrations concurrently to the same cached
 * {@link ApposeElastixTask} (and separately to {@link DefaultElastixTask} for
 * comparison). Each job is an independent BSpline registration of the same
 * image pair. All jobs are dispatched at once via a fixed thread pool and the
 * wall-clock time until the last one finishes is recorded.
 *
 * <p>Usage (from project root):
 * <pre>
 *   # default parallelism (4)
 *   mvn exec:java -Dexec.mainClass=DemoApposeElastixParallelTasks
 *
 *   # explicit parallelism
 *   mvn exec:java -Dexec.mainClass=DemoApposeElastixParallelTasks -Dexec.args="8"
 * </pre>
 *
 * <p>Requires {@code src/test/resources/blobs.tif} and
 * {@code src/test/resources/blobs-rot15deg.tif}.
 */
public class DemoApposeElastixParallelTasks {

    static final int DEFAULT_N_PARALLEL = 64;

    public static void main(String... args) throws Exception {

        int nParallel = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_N_PARALLEL;

        File fixedImage  = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        System.out.println("Parallel jobs: " + nParallel);

        // ---------------------------------------------------------------
        // Appose backend
        // ---------------------------------------------------------------
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println(  "║       Backend: ApposeElastixTask (parallel)          ║");
        System.out.println(  "╚══════════════════════════════════════════════════════╝");


        ApposeElastixTask apposeTask = new ApposeElastixTask();

        long[] apposeTimes = runParallel(fixedImage, movingImage, apposeTask, nParallel);
        printSummary("Appose", apposeTimes);

        // ---------------------------------------------------------------
        // CLI backend
        // ---------------------------------------------------------------
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println(  "║       Backend: DefaultElastixTask / CLI (parallel)   ║");
        System.out.println(  "╚══════════════════════════════════════════════════════╝");

        DefaultElastixTask cliTask = new DefaultElastixTask();
        long[] cliTimes = runParallel(fixedImage, movingImage, cliTask, nParallel);
        printSummary("CLI  ", cliTimes);

        // ---------------------------------------------------------------
        // Comparison table
        // ---------------------------------------------------------------
        System.out.println("\n╔═══════════════════╦═══════════╦═══════════╦═══════════╦═══════════╗");
        System.out.println(  "║  Backend          ║  Min (ms) ║  Max (ms) ║  Avg (ms) ║ Wall (ms) ║");
        System.out.println(  "╠═══════════════════╬═══════════╬═══════════╬═══════════╬═══════════╣");
        printTableRow("Appose (n=" + nParallel + ")", apposeTimes);
        printTableRow("CLI    (n=" + nParallel + ")", cliTimes);
        System.out.println(  "╚═══════════════════╩═══════════╩═══════════╩═══════════╩═══════════╝");
    }

    /**
     * Runs {@code nParallel} BSpline registrations concurrently using the given task backend.
     *
     * @return per-job wall-clock times in ms, one entry per job
     */
    static long[] runParallel(File fixedImage, File movingImage, ElastixTask task, int nParallel)
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(nParallel);
        List<Callable<Long>> jobs = new ArrayList<>(nParallel);

        for (int i = 0; i < nParallel; i++) {
            final int jobId = i;
            jobs.add(() -> {
                System.out.println("[Job " + jobId + "] Starting BSpline registration...");
                RegisterHelper rh = new RegisterHelper();
                rh.setFixedImage(fixedImage.getAbsolutePath());
                rh.setMovingImage(movingImage.getAbsolutePath());
                rh.addTransform(new RegParamBSpline_Default());
                rh.verbose();

                long t0 = System.currentTimeMillis();
                rh.align(task);
                long elapsed = System.currentTimeMillis() - t0;

                String transformFile = rh.getFinalTransformFile();
                boolean ok = new File(transformFile).exists();
                System.out.printf("[Job %d] Done in %d ms — transform exists: %b%n", jobId, elapsed, ok);
                if (!ok) {
                    System.err.println("[Job " + jobId + "] *** FAILURE: transform file missing! ***");
                }
                return elapsed;
            });
        }

        long wallStart = System.currentTimeMillis();
        List<Future<Long>> futures = pool.invokeAll(jobs);
        pool.shutdown();

        long[] times = new long[nParallel + 1]; // last slot = total wall clock
        for (int i = 0; i < nParallel; i++) {
            times[i] = futures.get(i).get();
        }
        times[nParallel] = System.currentTimeMillis() - wallStart;
        return times;
    }

    static void printSummary(String label, long[] times) {
        int n = times.length - 1; // last entry is wall clock
        long wall = times[n];
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, times[i]);
            max = Math.max(max, times[i]);
            sum += times[i];
        }
        System.out.printf("[%s] wall=%d ms  min=%d ms  max=%d ms  avg=%d ms%n",
                label, wall, min, max, sum / n);
    }

    static void printTableRow(String label, long[] times) {
        int n = times.length - 1;
        long wall = times[n];
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, times[i]);
            max = Math.max(max, times[i]);
            sum += times[i];
        }
        System.out.printf("║ %-17s ║ %9d ║ %9d ║ %9d ║ %9d ║%n",
                label, min, max, sum / n, wall);
    }
}