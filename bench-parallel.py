"""
Minimal benchmark: elastix CLI vs itk-elastix Python API.

Context
-------
In a Java/Appose-based setup we observed a ~5x slowdown when running
itk-elastix inside a persistent Python subprocess (via Appose) compared
to calling the elastix CLI executable directly.  This script reproduces
the two execution paths in pure Python to isolate where the time goes.

Two methods are compared:

  CLI     -- subprocess.run(["elastix", "-f", ..., "-m", ..., "-p", ..., "-out", ...])
             Each call spawns a fresh elastix process, exactly like DefaultElastixTask.

  itk-API -- itk.ElastixRegistrationMethod[...].UpdateLargestPossibleRegion()
             Registration runs inside the current Python process, exactly like the
             script that Appose dispatches to its persistent worker process.

Usage
-----
    python bench.py \\
        --elastix /path/to/elastix \\
        --fixed   ../src/test/resources/blobs-rot15deg.tif \\
        --moving  ../src/test/resources/blobs.tif

Optional flags:
    --threads  N  number of ITK/elastix threads per job (0 = auto-detect physical cores)
    --runs     N  total number of timed repetitions (default 3; run 1 is warm-up)
    --parallel N  number of concurrent jobs per run (default 1 = sequential)
    --no-cli      skip the CLI measurements
    --no-itk      skip the itk-elastix measurements

Parallel mode (--parallel N > 1)
---------------------------------
Each run dispatches N independent registrations of the same image pair
concurrently via a ThreadPoolExecutor.  Because ITK C++ releases the GIL
during UpdateLargestPossibleRegion, multiple itk-API jobs can overlap in
the same process.  CLI jobs each spawn a separate elastix process, so they
are always truly parallel.  The reported wall time is the elapsed time from
the first job starting to the last one finishing.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

# itk uses lazy attribute loading (itkTemplate) that is not thread-safe on first access.
# This lock serializes only the setup phase (object construction + parameter loading);
# the expensive UpdateLargestPossibleRegion() runs outside the lock so jobs truly overlap.
_itk_init_lock = threading.Lock()

PARAM_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "params_bspline.txt")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def physical_cores():
    try:
        import psutil
        return psutil.cpu_count(logical=False) or os.cpu_count()
    except ImportError:
        return os.cpu_count()


def resolve_threads(n):
    return physical_cores() if n == 0 else n


# ---------------------------------------------------------------------------
# CLI backend
# ---------------------------------------------------------------------------

def run_cli(elastix_exe, fixed, moving, param_file, n_threads, job_id=None):
    tag = f"[CLI job {job_id}]" if job_id is not None else "[CLI]"
    out_dir = tempfile.mkdtemp(prefix="elastix_cli_")
    try:
        cmd = [
            elastix_exe,
            "-f",       fixed,
            "-m",       moving,
            "-p",       param_file,
            "-out",     out_dir,
            "-threads", str(n_threads),
        ]
        t0 = time.perf_counter()
        result = subprocess.run(cmd, capture_output=True, text=True)
        elapsed = time.perf_counter() - t0
        if result.returncode != 0:
            print(f"  {tag} STDERR (last 600 chars):", result.stderr[-600:], file=sys.stderr)
            raise RuntimeError(f"elastix CLI failed (rc={result.returncode})")
        transform = os.path.join(out_dir, "TransformParameters.0.txt")
        ok = os.path.exists(transform)
        print(f"  {tag}     {elapsed:.2f}s  transform exists: {ok}")
        return elapsed
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# itk-elastix backend (in-process)
# ---------------------------------------------------------------------------

def run_itk(fixed, moving, param_file, n_threads, job_id=None):
    tag = f"[itk-API job {job_id}]" if job_id is not None else "[itk-API]"

    out_dir = tempfile.mkdtemp(prefix="elastix_itk_")
    try:
        # Serialize itk object construction: itk's lazy attribute loader (itkTemplate)
        # is not thread-safe on first access, causing AttributeError under parallelism.
        # The import must also be inside the lock because `import itk` itself triggers
        # lazy attribute resolution that is not thread-safe.
        with _itk_init_lock:
            import itk  # noqa: import inside lock to avoid concurrent lazy-loading
            fixed_img  = itk.imread(fixed,  itk.F)
            moving_img = itk.imread(moving, itk.F)

            param_obj = itk.ParameterObject.New()
            param_obj.ReadParameterFile(param_file)
            pm = param_obj.GetParameterMap(0)
            param_obj.SetParameterMap(0, pm)

            ImageType = type(fixed_img)
            erm = itk.ElastixRegistrationMethod[ImageType, ImageType].New()
            erm.SetFixedImage(fixed_img)
            erm.SetMovingImage(moving_img)
            erm.SetParameterObject(param_obj)
            erm.SetOutputDirectory(out_dir)
            erm.SetLogToConsole(False)
            erm.SetLogToFile(True)
            erm.SetNumberOfThreads(n_threads)

        # Release the lock before the heavy computation so jobs run in parallel.
        t0 = time.perf_counter()
        erm.UpdateLargestPossibleRegion()
        elapsed = time.perf_counter() - t0

        transform = os.path.join(out_dir, "TransformParameters.0.txt")
        ok = os.path.exists(transform)
        print(f"  {tag} {elapsed:.2f}s  transform exists: {ok}")
        return elapsed
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Parallel runner
# ---------------------------------------------------------------------------

def run_parallel(fn, n_parallel, *fn_args):
    """Run fn(*fn_args, job_id=i) for i in range(n_parallel) concurrently.

    Returns (per_job_times, wall_time) where per_job_times is a list of floats.
    ITK C++ releases the GIL during registration, so multiple itk-API jobs
    can genuinely overlap within a single process.
    """
    wall_t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=n_parallel) as executor:
        futures = [executor.submit(fn, *fn_args, job_id=i) for i in range(n_parallel)]
        per_job = [f.result() for f in futures]
    wall = time.perf_counter() - wall_t0
    return per_job, wall


def parallel_stats(per_job):
    """Return (min, max, avg) for a list of per-job times."""
    return min(per_job), max(per_job), sum(per_job) / len(per_job)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Benchmark elastix CLI vs itk-elastix Python API"
    )
    parser.add_argument("--elastix",  default=None,
                        help="Path to elastix CLI executable (required unless --no-cli)")
    parser.add_argument("--fixed",    required=True, help="Fixed image (TIFF/MHD/...)")
    parser.add_argument("--moving",   required=True, help="Moving image")
    parser.add_argument("--threads",  type=int, default=0,
                        help="Number of threads per job (0 = physical cores, default)")
    parser.add_argument("--runs",     type=int, default=3,
                        help="Number of timed runs (first is warm-up)")
    parser.add_argument("--parallel", type=int, default=1,
                        help="Number of concurrent jobs per run (default 1 = sequential)")
    parser.add_argument("--no-cli",   action="store_true", help="Skip CLI measurements")
    parser.add_argument("--no-itk",   action="store_true", help="Skip itk-elastix measurements")
    args = parser.parse_args()

    if not args.no_cli and args.elastix is None:
        parser.error("--elastix is required unless --no-cli is set")
    if args.parallel < 1:
        parser.error("--parallel must be >= 1")

    n_threads = resolve_threads(args.threads)
    n_parallel = args.parallel

    print(f"Python {sys.version}")
    print(f"Threads per job: {n_threads}  (requested: {args.threads})")
    print(f"Parallel jobs:   {n_parallel}")
    try:
        import itk_elastix
        print(f"itk-elastix version: {itk_elastix.__version__}")
    except Exception:
        pass
    print(f"Param file: {PARAM_FILE}")
    print(f"Fixed:      {args.fixed}")
    print(f"Moving:     {args.moving}")
    print()

    # Each entry is (wall_time, per_job_times_list)
    cli_results = []
    itk_results = []

    for run in range(args.runs):
        label = f"Run {run + 1}/{args.runs}" + (" (warm-up)" if run == 0 else "")
        print(f"--- {label} (parallel={n_parallel}) ---")

        if not args.no_cli:
            if n_parallel == 1:
                t = run_cli(args.elastix, args.fixed, args.moving, PARAM_FILE, n_threads)
                cli_results.append((t, [t]))
            else:
                per_job, wall = run_parallel(
                    run_cli, n_parallel,
                    args.elastix, args.fixed, args.moving, PARAM_FILE, n_threads)
                mn, mx, avg = parallel_stats(per_job)
                print(f"  [CLI parallel] wall={wall:.2f}s  min={mn:.2f}s  max={mx:.2f}s  avg={avg:.2f}s")
                cli_results.append((wall, per_job))

        if not args.no_itk:
            if n_parallel == 1:
                t = run_itk(args.fixed, args.moving, PARAM_FILE, n_threads)
                itk_results.append((t, [t]))
            else:
                per_job, wall = run_parallel(
                    run_itk, n_parallel,
                    args.fixed, args.moving, PARAM_FILE, n_threads)
                mn, mx, avg = parallel_stats(per_job)
                print(f"  [itk parallel] wall={wall:.2f}s  min={mn:.2f}s  max={mx:.2f}s  avg={avg:.2f}s")
                itk_results.append((wall, per_job))

        print()

    # Summary table — show wall time (= job time when sequential)
    col = 14
    if n_parallel == 1:
        header = f"{'Run':<6}  {'CLI (s)':>{col}}  {'itk-API (s)':>{col}}  {'ratio (itk/cli)':>{col}}"
        print(header)
        print("-" * len(header))
        for i in range(args.runs):
            warm = "*" if i == 0 else " "
            c = f"{cli_results[i][0]:.3f}" if cli_results else "n/a"
            t = f"{itk_results[i][0]:.3f}" if itk_results else "n/a"
            if cli_results and itk_results:
                ratio = f"{itk_results[i][0] / cli_results[i][0]:.2f}x"
            else:
                ratio = "n/a"
            print(f"{warm}{i + 1:<5}  {c:>{col}}  {t:>{col}}  {ratio:>{col}}")
    else:
        header = (f"{'Run':<6}  {'CLI wall(s)':>{col}}  {'CLI avg(s)':>{col}}"
                  f"  {'ITK wall(s)':>{col}}  {'ITK avg(s)':>{col}}  {'wall ratio':>{col}}")
        print(header)
        print("-" * len(header))
        for i in range(args.runs):
            warm = "*" if i == 0 else " "
            if cli_results:
                cw = f"{cli_results[i][0]:.3f}"
                ca = f"{parallel_stats(cli_results[i][1])[2]:.3f}"
            else:
                cw = ca = "n/a"
            if itk_results:
                tw = f"{itk_results[i][0]:.3f}"
                ta = f"{parallel_stats(itk_results[i][1])[2]:.3f}"
            else:
                tw = ta = "n/a"
            if cli_results and itk_results:
                ratio = f"{itk_results[i][0] / cli_results[i][0]:.2f}x"
            else:
                ratio = "n/a"
            print(f"{warm}{i + 1:<5}  {cw:>{col}}  {ca:>{col}}  {tw:>{col}}  {ta:>{col}}  {ratio:>{col}}")

    if args.runs > 1:
        print()
        if cli_results and len(cli_results) > 1:
            avg_c = sum(r[0] for r in cli_results[1:]) / (len(cli_results) - 1)
            print(f"Steady-state avg CLI     wall (runs 2+): {avg_c:.3f}s")
        if itk_results and len(itk_results) > 1:
            avg_t = sum(r[0] for r in itk_results[1:]) / (len(itk_results) - 1)
            print(f"Steady-state avg itk-API wall (runs 2+): {avg_t:.3f}s")
        if cli_results and itk_results and len(cli_results) > 1:
            print(f"Steady-state wall ratio (itk/cli): {avg_t / avg_c:.2f}x")


if __name__ == "__main__":
    main()