package org.pk.practices.design.locking.benchmark;

/**
 * Immutable result produced by one benchmark run.
 *
 * @param strategyName   name returned by {@code LockingStrategy.name()}
 * @param mode           "write-heavy" or "read-heavy"
 * @param threads        total thread count used in the run
 * @param totalOps       operations submitted (write-heavy) or completed (read-heavy)
 * @param expectedValue  counter value after all ops, if deterministic; -1 for read-heavy runs
 * @param actualValue    counter value observed after all threads finished
 * @param elapsedNanos   wall-clock time from first thread release to last thread done
 */
public record BenchmarkResult(
        String strategyName,
        String mode,
        int    threads,
        long   totalOps,
        long   expectedValue,
        long   actualValue,
        long   elapsedNanos) {

    /** True if no updates were lost (only meaningful for write-heavy runs). */
    public boolean correct() {
        return expectedValue == -1 || actualValue == expectedValue;
    }

    public double elapsedMs() {
        return elapsedNanos / 1_000_000.0;
    }

    public long throughputPerSecond() {
        if (elapsedNanos == 0) return 0;
        return (long) (totalOps * 1_000_000_000.0 / elapsedNanos);
    }
}
