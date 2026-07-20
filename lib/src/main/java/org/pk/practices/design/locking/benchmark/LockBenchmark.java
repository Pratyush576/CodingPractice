package org.pk.practices.design.locking.benchmark;

import org.pk.practices.design.locking.strategy.LockingStrategy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Benchmarks {@link LockingStrategy} implementations under two contention patterns.
 *
 * <h2>Write-heavy mode</h2>
 * All {@code threads} threads increment the counter {@code opsPerThread} times each.
 * Total expected counter value = {@code threads × opsPerThread}.
 * <br>Correct strategies produce exactly that value; incorrect ones produce less
 * (lost updates from races). The benchmark detects correctness automatically.
 *
 * <h2>Read-heavy mode</h2>
 * {@code readerThreads} threads call {@code value()} in a tight loop; {@code writerThreads}
 * threads call {@code increment()} in a tight loop; all run for {@code durationMs} ms.
 * Total throughput = (reads + writes) per second.
 * <br>This mode is where {@link org.pk.practices.design.locking.strategy.ReadWriteLockStrategy}
 * and {@link org.pk.practices.design.locking.strategy.StampedLockStrategy} show their advantage:
 * readers do not block each other, so throughput scales with reader count.
 *
 * <h2>Measurement method: start gate</h2>
 * All threads are created and reach a {@link CountDownLatch} before the timer starts.
 * The latch is released in one instant, ensuring all threads run concurrently from the
 * same moment. Without a start gate, later-started threads face lower contention and
 * the measurement is skewed.
 *
 * <h2>Warmup</h2>
 * A short warmup run is executed before each timed run so that JIT compilation,
 * class loading, and thread pool warm-up don't inflate the measurement.
 */
public final class LockBenchmark {

    private LockBenchmark() {}

    // ── Write-heavy benchmark ─────────────────────────────────────────────────

    /**
     * Runs a write-heavy benchmark: all threads increment the shared counter.
     *
     * @param strategy       the locking strategy under test
     * @param threads        number of concurrent writer threads
     * @param opsPerThread   increment calls per thread
     * @return result including throughput and correctness
     */
    public static BenchmarkResult writeHeavy(
            LockingStrategy strategy, int threads, int opsPerThread) {

        // Warmup
        runWriteRound(strategy, threads, Math.min(opsPerThread / 10, 1_000));

        // Timed run
        return runWriteRound(strategy, threads, opsPerThread);
    }

    private static BenchmarkResult runWriteRound(
            LockingStrategy strategy, int threads, int opsPerThread) {
        strategy.reset();
        long expected = (long) threads * opsPerThread;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate   = new CountDownLatch(threads);

        IntStream.range(0, threads).forEach(i -> {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < opsPerThread; j++) strategy.increment();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }, strategy.name() + "-w-" + i);
            t.setDaemon(true);
            t.start();
        });

        long start = nanos();
        startGate.countDown();
        awaitQuietly(endGate);
        long elapsed = nanos() - start;

        return new BenchmarkResult(strategy.name(), "write-heavy",
                threads, expected, expected, strategy.value(), elapsed);
    }

    // ── Read-heavy benchmark ──────────────────────────────────────────────────

    /**
     * Runs a read-heavy benchmark: mostly readers, few writers, all for {@code durationMs} ms.
     *
     * @param strategy       the locking strategy under test
     * @param readerThreads  threads calling {@code value()} in a tight loop
     * @param writerThreads  threads calling {@code increment()} in a tight loop
     * @param durationMs     how long to run before stopping all threads
     * @return result including total throughput (reads + writes)
     */
    public static BenchmarkResult readHeavy(
            LockingStrategy strategy, int readerThreads, int writerThreads, long durationMs) {

        // Warmup (half duration)
        runReadRound(strategy, readerThreads, writerThreads, durationMs / 2);

        // Timed run
        return runReadRound(strategy, readerThreads, writerThreads, durationMs);
    }

    private static BenchmarkResult runReadRound(
            LockingStrategy strategy, int readerThreads, int writerThreads, long durationMs) {
        strategy.reset();

        AtomicLong    totalOps  = new AtomicLong(0);
        AtomicBoolean running   = new AtomicBoolean(true);
        int           allThreads = readerThreads + writerThreads;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate   = new CountDownLatch(allThreads);

        IntStream.range(0, readerThreads).forEach(i -> {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    long ops = 0;
                    while (running.get()) { strategy.value(); ops++; }
                    totalOps.addAndGet(ops);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }, strategy.name() + "-r-" + i);
            t.setDaemon(true);
            t.start();
        });

        IntStream.range(0, writerThreads).forEach(i -> {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    long ops = 0;
                    while (running.get()) { strategy.increment(); ops++; }
                    totalOps.addAndGet(ops);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }, strategy.name() + "-w-" + i);
            t.setDaemon(true);
            t.start();
        });

        long start = nanos();
        startGate.countDown();
        sleepMs(durationMs);
        running.set(false);
        awaitQuietly(endGate);
        long elapsed = nanos() - start;

        return new BenchmarkResult(strategy.name(), "read-heavy",
                allThreads, totalOps.get(), -1, strategy.value(), elapsed);
    }

    // ── Table printer ─────────────────────────────────────────────────────────

    /**
     * Prints a formatted benchmark table for a list of results.
     * Strategies that produce incorrect counters are flagged with "LOST UPDATES".
     */
    public static void printTable(List<BenchmarkResult> results) {
        if (results.isEmpty()) return;

        String mode = results.getFirst().mode();
        boolean isWriteHeavy = "write-heavy".equals(mode);

        System.out.printf("  %-22s %-10s %-10s %-15s%s%n",
                "Strategy", "Threads", isWriteHeavy ? "Correct" : "Mode",
                "Throughput", isWriteHeavy ? "  Lost ops" : "");
        System.out.println("  " + "─".repeat(68));

        long maxThroughput = results.stream()
                .mapToLong(BenchmarkResult::throughputPerSecond).max().orElse(1);

        for (BenchmarkResult r : results) {
            double ratio = (double) r.throughputPerSecond() / maxThroughput;
            String bar   = "█".repeat((int) (ratio * 20));

            if (isWriteHeavy) {
                String status = r.correct() ? "✓" : "✗ LOST " + (r.totalOps() - r.actualValue());
                System.out.printf("  %-22s %-10d %-10s %,12d/s  %s%n",
                        r.strategyName(), r.threads(), status,
                        r.throughputPerSecond(), bar);
            } else {
                System.out.printf("  %-22s %-10d %-10s %,12d/s  %s%n",
                        r.strategyName(), r.threads(), r.mode(),
                        r.throughputPerSecond(), bar);
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static long nanos() { return System.nanoTime(); }

    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
