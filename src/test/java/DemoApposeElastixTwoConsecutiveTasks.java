import ch.epfl.biop.wrappers.elastix.ApposeElastixTask;
import ch.epfl.biop.wrappers.elastix.DefaultElastixTask;
import ch.epfl.biop.wrappers.elastix.ElastixTask;
import ch.epfl.biop.wrappers.elastix.RegParamAffine_Default;
import ch.epfl.biop.wrappers.elastix.RegParamBSpline_Default;
import ch.epfl.biop.wrappers.elastix.RegisterHelper;

import java.io.File;

/**
 * Minimal reproducible example for the Appose service reuse bug.
 *
 * <p>Runs two consecutive, separate registrations on the same cached Appose service:
 * <ol>
 *   <li>Task 1 — affine registration</li>
 *   <li>Task 2 — BSpline registration, initialized from the affine result</li>
 * </ol>
 * Each is a separate Appose {@link org.apposed.appose.Service.Task} submission.
 * The {@link org.apposed.appose.Service} itself is reused (cached in
 * {@link ApposeElastixTask}).
 *
 * <p>Also benchmarks wall-clock time for each step, and compares against the
 * CLI-based {@link DefaultElastixTask}. Each backend is run {@value #N_RUNS} times
 * so that the first (warm-up) and subsequent (steady-state) costs are visible.
 *
 * <p>Run from the project root. Requires {@code src/test/resources/blobs.tif}
 * and {@code src/test/resources/blobs-rot15deg.tif}.
 */
public class DemoApposeElastixTwoConsecutiveTasks {

    static final int N_RUNS = 3;

    public static void main(String... args) throws Exception {

        File fixedImage  = new File("src/test/resources/blobs-rot15deg.tif");
        File movingImage = new File("src/test/resources/blobs.tif");

        if (!fixedImage.exists() || !movingImage.exists()) {
            System.err.println("Test images not found. Run from the project root directory.");
            return;
        }

        // ---------------------------------------------------------------
        // Appose backend
        // ---------------------------------------------------------------
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           Backend: ApposeElastixTask                 ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        ApposeElastixTask apposeTask = new ApposeElastixTask();
        long[][] apposeTimes = new long[N_RUNS][2];

        for (int run = 0; run < N_RUNS; run++) {
            System.out.println("\n--- Appose run " + (run + 1) + "/" + N_RUNS + " ---");
            apposeTimes[run] = runSequentialTasks(fixedImage, movingImage, apposeTask);
        }

        // ---------------------------------------------------------------
        // CLI backend
        // ---------------------------------------------------------------
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║           Backend: DefaultElastixTask (CLI)          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        DefaultElastixTask cliTask = new DefaultElastixTask();
        long[][] cliTimes = new long[N_RUNS][2];

        for (int run = 0; run < N_RUNS; run++) {
            System.out.println("\n--- CLI run " + (run + 1) + "/" + N_RUNS + " ---");
            cliTimes[run] = runSequentialTasks(fixedImage, movingImage, cliTask);
        }

        // ---------------------------------------------------------------
        // Summary table
        // ---------------------------------------------------------------
        System.out.println("\n╔════════════════════╦═════════════╦═════════════╦═════════════╗");
        System.out.println("║                    ║   Affine    ║   BSpline   ║    Total    ║");
        System.out.println("╠════════════════════╬═════════════╬═════════════╬═════════════╣");
        for (int run = 0; run < N_RUNS; run++) {
            String label = run == 0 ? "Appose run 1 (warm)" : "Appose run  " + (run + 1);
            printRow(label, apposeTimes[run]);
        }
        System.out.println("╠════════════════════╬═════════════╬═════════════╬═════════════╣");
        for (int run = 0; run < N_RUNS; run++) {
            String label = run == 0 ? "CLI   run 1 (warm)" : "CLI    run  " + (run + 1);
            printRow(label, cliTimes[run]);
        }
        System.out.println("╚════════════════════╩═════════════╩═════════════╩═════════════╝");
    }

    /**
     * Runs an affine registration followed by a BSpline registration using the given task.
     *
     * @return array of two wall-clock durations in ms: [affine_ms, bspline_ms]
     */
    static long[] runSequentialTasks(File fixedImage, File movingImage, ElastixTask task) throws Exception {

        // ---- Task 1: affine ----
        /*System.out.println("[Task 1] Starting affine registration...");
        RegisterHelper rh1 = new RegisterHelper();
        rh1.setFixedImage(fixedImage.getAbsolutePath());
        rh1.setMovingImage(movingImage.getAbsolutePath());
        rh1.addTransform(new RegParamAffine_Default());
        rh1.verbose();

        long t0 = System.currentTimeMillis();
        rh1.align(task);
        long affineMs = System.currentTimeMillis() - t0;

        String affineTransformFile = rh1.getFinalTransformFile();
        boolean affineOk = new File(affineTransformFile).exists();
        System.out.printf("[Task 1] Affine done in %d ms — transform exists: %b%n", affineMs, affineOk);*/

        // ---- Task 2: BSpline, initialized from affine result ----
        System.out.println("[Task 2] Starting BSpline registration (reusing service)...");
        RegisterHelper rh2 = new RegisterHelper();
        rh2.setFixedImage(fixedImage.getAbsolutePath());
        rh2.setMovingImage(movingImage.getAbsolutePath());
        rh2.addTransform(new RegParamBSpline_Default());
        //rh2.addInitialTransformFromFilePath(affineTransformFile);
        rh2.verbose();

        long t1 = System.currentTimeMillis();
        rh2.align(task);
        long bsplineMs = System.currentTimeMillis() - t1;

        String bsplineTransformFile = rh2.getFinalTransformFile();
        boolean bsplineOk = new File(bsplineTransformFile).exists();
        System.out.printf("[Task 2] BSpline done in %d ms — transform exists: %b%n", bsplineMs, bsplineOk);

        if (/*!affineOk ||*/ !bsplineOk) {
            System.err.println("  *** FAILURE: one or both transform files are missing! ***");
        }

        return new long[]{ 0/*affineMs*/, bsplineMs };
    }

    static void printRow(String label, long[] times) {
        long total = times[0] + times[1];
        System.out.printf("║ %-17s ║ %7d ms  ║ %7d ms  ║ %7d ms  ║%n",
                label, times[0], times[1], total);
    }
}