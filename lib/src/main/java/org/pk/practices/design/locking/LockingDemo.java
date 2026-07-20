package org.pk.practices.design.locking;

import org.pk.practices.design.locking.benchmark.BenchmarkResult;
import org.pk.practices.design.locking.benchmark.LockBenchmark;
import org.pk.practices.design.locking.demo.*;
import org.pk.practices.design.locking.strategy.*;

import java.util.List;

/**
 * Main entry point for the locking hands-on.
 *
 * <p>Runs in order:
 * <ol>
 *   <li>Write-heavy benchmark — all strategies, all threads write</li>
 *   <li>Read-heavy benchmark  — 8 readers + 2 writers, strategies compared</li>
 *   <li>Semaphore demo        — database connection pool</li>
 *   <li>CountDownLatch demo   — service startup coordination</li>
 *   <li>CyclicBarrier demo    — phased parallel computation</li>
 *   <li>Phaser demo           — multi-phase ETL pipeline</li>
 * </ol>
 *
 * <p>To add a new strategy: implement {@link org.pk.practices.design.locking.strategy.LockingStrategy}
 * and add an instance to {@link #strategies()} — nothing else needs changing.
 */
public class LockingDemo {

    private static final String LINE = "─".repeat(70);

    public static void main(String[] args) throws Exception {

        // ── Benchmark: write-heavy ────────────────────────────────────────────
        section("1. WRITE-HEAVY BENCHMARK (10 threads × 30 000 increments)");
        System.out.println("  All threads write. Validates correctness (no lost updates).");
        System.out.println("  ReentrantLock(fair) intentionally slower — FIFO overhead.\n");

        List<BenchmarkResult> writeResults = strategies().stream()
                .map(s -> LockBenchmark.writeHeavy(s, 10, 30_000))
                .toList();
        LockBenchmark.printTable(writeResults);

        // ── Benchmark: read-heavy ─────────────────────────────────────────────
        section("2. READ-HEAVY BENCHMARK (8 readers + 2 writers, 600 ms)");
        System.out.println("  8 reader threads call value(); 2 writer threads call increment().");
        System.out.println("  ReadWriteLock and StampedLock shine here — readers don't block each other.\n");

        List<BenchmarkResult> readResults = strategies().stream()
                .map(s -> LockBenchmark.readHeavy(s, 8, 2, 600))
                .toList();
        LockBenchmark.printTable(readResults);

        // ── Semaphore ─────────────────────────────────────────────────────────
        section("3. SEMAPHORE — DATABASE CONNECTION POOL");
        SemaphoreDemo.run();

        // ── CountDownLatch ────────────────────────────────────────────────────
        section("4. COUNTDOWNLATCH — SERVICE STARTUP COORDINATION");
        CountDownLatchDemo.run();

        // ── CyclicBarrier ─────────────────────────────────────────────────────
        section("5. CYCLICBARRIER — PHASED PARALLEL WORD COUNT");
        CyclicBarrierDemo.run();

        // ── Phaser ────────────────────────────────────────────────────────────
        section("6. PHASER — MULTI-PHASE ETL PIPELINE");
        PhaserDemo.run();
    }

    /**
     * All strategies under test. Add new implementations here to include them in
     * every benchmark automatically — the rest of the code is unaffected.
     */
    private static List<LockingStrategy> strategies() {
        return List.of(
                new SynchronizedStrategy(),
                new ReentrantLockStrategy(),
                new ReentrantLockStrategy(true),    // fair mode
                new ReadWriteLockStrategy(),
                new StampedLockStrategy(),
                new AtomicStrategy()
        );
    }

    private static void section(String title) {
        System.out.println("\n" + LINE);
        System.out.println("  " + title);
        System.out.println(LINE);
    }
}
