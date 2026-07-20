package org.pk.practices.design.locking.demo;

import java.util.concurrent.CountDownLatch;

/**
 * Demonstrates {@link CountDownLatch} for one-shot coordination.
 *
 * <h2>What CountDownLatch is</h2>
 * A count-down-to-zero barrier. One or more threads call {@code await()} and block
 * until the count reaches zero. Other threads call {@code countDown()} to decrement it.
 * Once zero, the latch stays open forever — it cannot be reset (use {@link CyclicBarrierDemo}
 * if you need a reusable barrier).
 *
 * <h2>Two canonical uses</h2>
 * <ol>
 *   <li><b>Start gate</b> — count=1. One thread holds the gate; N workers call
 *       {@code await()}. Main releases all at once with {@code countDown()}.</li>
 *   <li><b>End gate</b> — count=N. Main thread calls {@code await()}; each of N workers
 *       calls {@code countDown()} when finished.</li>
 * </ol>
 *
 * <h2>Use case: service startup</h2>
 * An application server must not accept HTTP traffic until its Database, Cache, and
 * ConfigService are all ready. Each service starts in its own thread and calls
 * {@code latch.countDown()} when it's up. The main thread is blocked on
 * {@code latch.await()} and proceeds only when all three have counted down to zero.
 *
 * <h2>Difference from join()</h2>
 * {@code thread.join()} ties you to the thread lifecycle. {@code CountDownLatch} is
 * more flexible: a thread can count down multiple times for distinct milestones, or
 * the same latch can be signalled by non-thread entities (e.g. callbacks, futures).
 */
public final class CountDownLatchDemo {

    private CountDownLatchDemo() {}

    public static void run() throws InterruptedException {
        final int SERVICE_COUNT = 3;

        // End gate: main waits until all services are up
        CountDownLatch readyLatch = new CountDownLatch(SERVICE_COUNT);

        String[] services = {"DatabaseService", "CacheService", "ConfigService"};
        int[]    startMs  = {400, 200, 300};     // each service takes different time to start

        System.out.println("  Starting " + SERVICE_COUNT + " dependent services...");

        long globalStart = System.currentTimeMillis();

        for (int i = 0; i < SERVICE_COUNT; i++) {
            final String svcName = services[i];
            final int    delay   = startMs[i];

            new Thread(() -> {
                try {
                    System.out.printf("  [%s] initialising...%n", svcName);
                    Thread.sleep(delay);
                    System.out.printf("  [%s] ready! (+%d ms)%n",
                            svcName, System.currentTimeMillis() - globalStart);
                    readyLatch.countDown();   // signal one service ready
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, svcName).start();
        }

        System.out.println("  [ApplicationServer] waiting for all services...");
        readyLatch.await();                   // blocks here until count = 0

        long elapsed = System.currentTimeMillis() - globalStart;
        System.out.printf("%n  [ApplicationServer] all services ready in %d ms — " +
                "accepting traffic!%n", elapsed);
        System.out.printf("  (Sequential startup would have taken %d ms)%n",
                startMs[0] + startMs[1] + startMs[2]);
    }
}
