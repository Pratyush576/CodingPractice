package org.pk.practices.design.locking.demo;

import java.util.concurrent.Phaser;

/**
 * Demonstrates {@link Phaser} for flexible, multi-phase coordination with dynamic registration.
 *
 * <h2>Phaser vs. CyclicBarrier</h2>
 * <pre>
 *   Feature                CyclicBarrier    Phaser
 *   ─────────────────────────────────────────────────────────────────
 *   Party count            Fixed at create  Dynamic — parties may register/deregister
 *   Reusable               Yes              Yes
 *   Barrier action         Runnable         Override onAdvance(phase, parties)
 *   Tiered (tree)          No               Yes — Phasers can be chained
 *   Per-phase behaviour    Same always      Conditional — onAdvance can terminate
 * </pre>
 *
 * <h2>Key API</h2>
 * <ul>
 *   <li>{@code phaser.register()} — adds one party (thread) to the current phase.</li>
 *   <li>{@code phaser.arriveAndAwaitAdvance()} — signal arrival at this phase barrier
 *       and block until all parties have arrived; then advance to next phase.</li>
 *   <li>{@code phaser.arriveAndDeregister()} — signal arrival, deregister self (so
 *       future phases don't wait for this party).</li>
 *   <li>{@code phaser.onAdvance(phase, registeredParties)} — override to add custom
 *       logic between phases or to terminate the phaser (return true).</li>
 * </ul>
 *
 * <h2>Use case: three-phase ETL pipeline</h2>
 * 4 worker threads process separate data chunks through three phases:
 * <ol>
 *   <li><b>Extract</b>  — read records from source</li>
 *   <li><b>Transform</b> — apply business rules / enrichment</li>
 *   <li><b>Load</b>     — write to destination</li>
 * </ol>
 * No worker begins Transform until all workers have completed Extract.
 * No worker begins Load until all workers have completed Transform.
 * The Phaser's {@code onAdvance} hook prints a phase-transition summary.
 */
public final class PhaserDemo {

    private static final int WORKERS = 4;

    private static final String[] PHASE_NAMES = {"Extract", "Transform", "Load"};
    private static final int[]    PHASE_MS     = {250, 150, 100};  // simulated work per phase

    private PhaserDemo() {}

    public static void run() throws InterruptedException {
        System.out.printf("  ETL pipeline: %d workers × %d phases%n", WORKERS, PHASE_NAMES.length);

        // Custom Phaser that prints a banner on each phase transition
        Phaser phaser = new Phaser(WORKERS) {
            @Override
            protected boolean onAdvance(int phase, int registeredParties) {
                if (phase < PHASE_NAMES.length) {
                    System.out.printf("  ──── Phase %d (%s) complete — advancing" +
                            " (%d parties registered) ────%n",
                            phase, PHASE_NAMES[phase], registeredParties);
                }
                // Return true to terminate after the last phase
                return phase >= PHASE_NAMES.length - 1;
            }
        };

        Thread[] workers = new Thread[WORKERS];
        long start = System.currentTimeMillis();

        for (int id = 0; id < WORKERS; id++) {
            final int myId = id;
            workers[id] = new Thread(() -> {
                for (int p = 0; p < PHASE_NAMES.length; p++) {
                    String phase  = PHASE_NAMES[p];
                    int    workMs = PHASE_MS[p] + myId * 20; // slightly staggered per worker

                    System.out.printf("  [Worker-%d] %s started...%n", myId, phase);
                    try { Thread.sleep(workMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                    System.out.printf("  [Worker-%d] %s done  (+%d ms)%n",
                            myId, phase, System.currentTimeMillis() - start);

                    phaser.arriveAndAwaitAdvance();  // wait for all workers to finish this phase
                }
            }, "ETL-Worker-" + id);
        }

        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        System.out.printf("%n  Pipeline complete in %d ms%n  " +
                "Phaser is%s terminated%n",
                System.currentTimeMillis() - start,
                phaser.isTerminated() ? "" : " NOT");
    }
}
