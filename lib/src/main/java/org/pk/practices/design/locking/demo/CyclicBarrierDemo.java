package org.pk.practices.design.locking.demo;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates {@link CyclicBarrier} for reusable phased computation.
 *
 * <h2>What CyclicBarrier is</h2>
 * A barrier that all N threads must reach before any can proceed. Unlike
 * {@link java.util.concurrent.CountDownLatch}, a CyclicBarrier:
 * <ul>
 *   <li>Is <b>reusable</b> — automatically resets after all N threads arrive.</li>
 *   <li>Runs a <b>barrier action</b> — an optional {@code Runnable} that executes
 *       once when the last thread arrives, before any are released.</li>
 * </ul>
 *
 * <pre>
 *   CyclicBarrier barrier = new CyclicBarrier(N, barrierAction);
 *   // Phase 1
 *   doPhase1Work();
 *   barrier.await();   // blocks until all N threads arrive
 *                      // barrierAction runs here (only once, by the last thread)
 *   // Phase 2 — all threads guaranteed to see Phase 1 results
 *   doPhase2Work();
 *   barrier.await();   // reused for Phase 2
 * </pre>
 *
 * <h2>Barrier action timing</h2>
 * The barrier action runs in the context of the last thread to arrive. It completes
 * before any waiting thread is released. This makes it the correct place to aggregate
 * partial results from Phase 1 before Phase 2 begins.
 *
 * <h2>Use case: parallel document word count</h2>
 * A large document is split into shards. Each of 4 threads counts words in its shard
 * (Phase 1). The barrier action aggregates the partial counts. Then each thread finds
 * the most frequent word in its shard (Phase 2). Barrier fires again and overall result
 * is printed. No thread starts Phase 2 until ALL have finished Phase 1.
 */
public final class CyclicBarrierDemo {

    private static final int    WORKER_COUNT  = 4;
    private static final int    SHARD_SIZE    = 10_000;
    private static final Random RNG           = new Random(42);

    private CyclicBarrierDemo() {}

    public static void run() throws InterruptedException {
        // Shared state written by workers, read by barrier action and Phase 2
        long[]   partialCounts = new long[WORKER_COUNT];
        long[]   partialMaxes  = new long[WORKER_COUNT];
        AtomicLong grandTotal  = new AtomicLong(0);
        AtomicReference<String> winner = new AtomicReference<>("none");

        // Build a simulated word-frequency array per shard
        int[][] shards = buildShards();

        // Barrier action: runs once after Phase 1, before Phase 2 begins
        Runnable aggregatePhase1 = () -> {
            long total = Arrays.stream(partialCounts).sum();
            grandTotal.set(total);
            System.out.printf("  [Barrier] Phase 1 complete — total words counted: %,d%n", total);
        };

        CyclicBarrier barrier = new CyclicBarrier(WORKER_COUNT, aggregatePhase1);

        System.out.printf("  %d workers, each processing %,d words (%,d total)%n",
                WORKER_COUNT, SHARD_SIZE, (long) WORKER_COUNT * SHARD_SIZE);

        Thread[] workers = new Thread[WORKER_COUNT];
        for (int id = 0; id < WORKER_COUNT; id++) {
            final int myId = id;
            workers[id] = new Thread(() -> {
                // ── Phase 1: count total words in my shard ────────────────────
                long count = 0;
                for (int freq : shards[myId]) count += freq;
                partialCounts[myId] = count;
                System.out.printf("  [Worker-%d] Phase 1 done: counted %,d words%n", myId, count);

                try { barrier.await(); } catch (BrokenBarrierException | InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }

                // ── Phase 2: find max-frequency word in my shard ──────────────
                // All workers now see grandTotal set by the barrier action
                long max = 0;
                for (int freq : shards[myId]) if (freq > max) max = freq;
                partialMaxes[myId] = max;
                System.out.printf("  [Worker-%d] Phase 2 done: local max frequency = %d  " +
                        "(grand total was %,d)%n", myId, max, grandTotal.get());

                try { barrier.await(); } catch (BrokenBarrierException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Worker-" + id);
        }

        long start = System.currentTimeMillis();
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        long overallMax = Arrays.stream(partialMaxes).max().orElse(0);
        System.out.printf("%n  All phases complete in %d ms — overall max word frequency: %d%n",
                System.currentTimeMillis() - start, overallMax);
    }

    private static int[][] buildShards() {
        int[][] shards = new int[WORKER_COUNT][SHARD_SIZE];
        for (int i = 0; i < WORKER_COUNT; i++)
            for (int j = 0; j < SHARD_SIZE; j++)
                shards[i][j] = RNG.nextInt(50) + 1;
        return shards;
    }
}
