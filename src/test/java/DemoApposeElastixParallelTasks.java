import ch.epfl.biop.wrappers.elastix.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Benchmarks many BSpline registrations submitted in parallel, comparing the
 * {@link ApposeElastixTask} (itk-elastix) and {@link DefaultElastixTask} (CLI) backends.
 *
 * <p>Each backend runs the same batch of {@code N_PARALLEL} independent BSpline
 * registrations {@code N_RUNS} times. Within a run, all jobs are dispatched at once
 * via a fixed thread pool and the wall-clock time until the last one finishes is
 * recorded, along with per-job min/max/avg.</p>
 *
 * <p>Inspired by {@code bench-parallel.py}, the key reported metric is the
 * <b>wall-time ratio (Appose / CLI)</b>. Run 1 is treated as warm-up: the Appose
 * backend pays the one-time pixi-env build, worker-process spawn and {@code import itk}
 * cost there, so the steady-state ratio over runs 2+ is what reflects the warm-pool
 * throughput. A ratio near 1.0 means Appose has caught up with the CLI.</p>
 *
 * <p>Usage (from project root):
 * <pre>
 *   # defaults: N_PARALLEL=64, N_RUNS=3
 *   mvn exec:java -Dexec.mainClass=DemoApposeElastixParallelTasks
 *
 *   # explicit parallelism, explicit run count
 *   mvn exec:java -Dexec.mainClass=DemoApposeElastixParallelTasks -Dexec.args="8 5"
 *
 *   # bound the warm worker pool (defaults to availableProcessors())
 *   mvn exec:java -Dexec.mainClass=DemoApposeElastixParallelTasks -Dexec.args="8 5" \
 *       -Dexec.jvmArgs="-Delastix.appose.workers=8"
 * </pre>
 *
 * <p>Requires {@code src/test/resources/blobs.tif} and
 * {@code src/test/resources/blobs-rot15deg.tif}.
 */
public class DemoApposeElastixParallelTasks {

    static final int DEFAULT_N_PARALLEL = 8;
    static final int DEFAULT_N_RUNS = 3;

    public static void main(String... args) throws Exception {

        int nParallel = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_N_PARALLEL;
        int nRuns     = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_N_RUNS;

        File fixedImage  = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // Point the CLI wrapper at the local elastix binary for the comparison.
        Elastix.setExePath(new File("C:/elastix-5.2.0-win64/elastix.exe"));
        boolean cliAvailable = isElastixCliAvailable();

        System.out.println("Parallel jobs: " + nParallel + "    Runs: " + nRuns
                + " (run 1 = warm-up)    Pool size: "
                + ApposeElastixTask.POOL_SIZE);

        if (!cliAvailable) {
            System.out.println();
            System.out.println("[!] elastix CLI not runnable (Elastix.exePath=\"" + Elastix.exePath + "\").");
            System.out.println("    Skipping the CLI comparison — benchmarking the Appose backend only.");
            System.out.println("    To enable it: -Delastix.exe=/path/to/elastix(.exe) or set the path in ImageJ prefs.");
        }

        ApposeElastixTask apposeTask = new ApposeElastixTask();
        DefaultElastixTask cliTask = new DefaultElastixTask();

        // Per-run results; each long[] is per-job times with the last slot = batch wall time.
        long[][] apposeRuns = new long[nRuns][];
        long[][] cliRuns = cliAvailable ? new long[nRuns][] : null;

        for (int run = 0; run < nRuns; run++) {
            String warm = run == 0 ? " (warm-up)" : "";
            System.out.printf("%n╔══════════════════════════════════════════════════════╗%n");
            System.out.printf(  "║  Run %d/%d%-44s║%n", run + 1, nRuns, warm);
            System.out.printf(  "╚══════════════════════════════════════════════════════╝%n");

            System.out.println("\n── Backend: ApposeElastixTask (itk-elastix) ──");
            apposeRuns[run] = runParallel(fixedImage, movingImage, apposeTask, nParallel);
            printSummary("Appose", apposeRuns[run]);

            if (cliAvailable) {
                System.out.println("\n── Backend: DefaultElastixTask (CLI) ──");
                cliRuns[run] = runParallel(fixedImage, movingImage, cliTask, nParallel);
                printSummary("CLI  ", cliRuns[run]);
            }
        }

        printComparison(apposeRuns, cliRuns, nParallel, nRuns);
    }

    /**
     * Probes whether the configured elastix CLI binary can actually be launched, so the demo can
     * fall back to an Appose-only benchmark instead of crashing when no native elastix is installed.
     */
    static boolean isElastixCliAvailable() {
        try {
            // A bare attempt to start the executable; if it isn't found this throws IOException
            // (CreateProcess error=2 on Windows). We don't care about its output — kill it at once.
            Process p = new ProcessBuilder(Elastix.exePath).start();
            p.destroyForcibly();
            return true;
        } catch (IOException notFound) {
            return false;
        }
    }

    /**
     * Runs {@code nParallel} BSpline registrations concurrently using the given task backend.
     *
     * @return per-job wall-clock times in ms, with the last slot holding the batch wall time
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

    /** Batch wall time = last slot. */
    static long wallOf(long[] times) {
        return times[times.length - 1];
    }

    /** Average per-job time in ms (excludes the wall-time slot). */
    static long avgJobOf(long[] times) {
        int n = times.length - 1;
        long sum = 0;
        for (int i = 0; i < n; i++) sum += times[i];
        return sum / n;
    }

    static void printSummary(String label, long[] times) {
        int n = times.length - 1; // last entry is wall clock
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, times[i]);
            max = Math.max(max, times[i]);
        }
        System.out.printf("[%s] wall=%d ms  min=%d ms  max=%d ms  avg=%d ms%n",
                label, wallOf(times), min, max, avgJobOf(times));
    }

    /**
     * Prints the per-run comparison table plus the steady-state (runs 2+) wall-time ratio,
     * which is the headline figure: Appose wall / CLI wall, with 1.0 meaning parity.
     */
    static void printComparison(long[][] apposeRuns, long[][] cliRuns, int nParallel, int nRuns) {
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════════╗");
        System.out.printf(  "║  Parallel comparison  (n=%d jobs/run)%-44s║%n", nParallel, "");
        System.out.println(  "╚════════════════════════════════════════════════════════════════════════════════╝");

        if (cliRuns == null) {
            // Appose-only: no CLI binary was available to compare against.
            System.out.println("┌────────────┬─────────────┬─────────────┐");
            System.out.println("│ Run        │ App wall ms │ App avg  ms │");
            System.out.println("├────────────┼─────────────┼─────────────┤");
            for (int i = 0; i < nRuns; i++) {
                String runLabel = (i == 0 ? "*" : " ") + (i + 1) + (i == 0 ? " (warm)" : "");
                System.out.printf("│ %-10s │ %11d │ %11d │%n",
                        runLabel, wallOf(apposeRuns[i]), avgJobOf(apposeRuns[i]));
            }
            System.out.println("└────────────┴─────────────┴─────────────┘");
            System.out.println("  * run 1 is warm-up: Appose pays the one-time env build / worker spawn / import itk.");
            if (nRuns > 1) {
                long appSum = 0;
                for (int i = 1; i < nRuns; i++) appSum += wallOf(apposeRuns[i]);
                System.out.printf("%nSteady-state avg Appose wall (runs 2+): %.0f ms%n", (double) appSum / (nRuns - 1));
            }
            System.out.println("\n(No CLI binary configured — see the note above to enable the Appose-vs-CLI ratio.)");
            return;
        }

        System.out.println("┌────────────┬─────────────┬─────────────┬─────────────┬─────────────┬────────────┐");
        System.out.println("│ Run        │ CLI wall ms │ CLI avg  ms │ App wall ms │ App avg  ms │ wall ratio │");
        System.out.println("├────────────┼─────────────┼─────────────┼─────────────┼─────────────┼────────────┤");
        for (int i = 0; i < nRuns; i++) {
            String runLabel = (i == 0 ? "*" : " ") + (i + 1) + (i == 0 ? " (warm)" : "");
            long cliWall = wallOf(cliRuns[i]);
            long appWall = wallOf(apposeRuns[i]);
            double ratio = cliWall == 0 ? Double.NaN : (double) appWall / cliWall;
            System.out.printf("│ %-10s │ %11d │ %11d │ %11d │ %11d │ %9.2fx │%n",
                    runLabel, cliWall, avgJobOf(cliRuns[i]), appWall, avgJobOf(apposeRuns[i]), ratio);
        }
        System.out.println("└────────────┴─────────────┴─────────────┴─────────────┴─────────────┴────────────┘");
        System.out.println("  * run 1 is warm-up: Appose pays the one-time env build / worker spawn / import itk.");

        // Steady-state: average wall time over runs 2+ (skip warm-up), and the ratio.
        if (nRuns > 1) {
            long cliSum = 0, appSum = 0;
            for (int i = 1; i < nRuns; i++) {
                cliSum += wallOf(cliRuns[i]);
                appSum += wallOf(apposeRuns[i]);
            }
            int steady = nRuns - 1;
            double cliAvg = (double) cliSum / steady;
            double appAvg = (double) appSum / steady;
            System.out.println();
            System.out.printf("Steady-state avg wall (runs 2+):  CLI = %.0f ms   Appose = %.0f ms%n", cliAvg, appAvg);
            System.out.printf("Steady-state wall ratio (Appose / CLI): %.2fx%n", cliAvg == 0 ? Double.NaN : appAvg / cliAvg);
        } else {
            System.out.println("\n(Run with N_RUNS >= 2 to get a warm-up-excluded steady-state ratio.)");
        }
    }
}