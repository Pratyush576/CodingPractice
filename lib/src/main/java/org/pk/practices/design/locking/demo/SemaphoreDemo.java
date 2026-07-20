package org.pk.practices.design.locking.demo;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates {@link Semaphore} as a counting resource pool.
 *
 * <h2>What a Semaphore is NOT</h2>
 * A Semaphore is not a mutex. It does not have ownership — any thread may release
 * a permit, even one that didn't acquire it. It is purely a counter of available
 * permits; {@code acquire()} blocks when the count is zero; {@code release()}
 * increments it and unblocks a waiting thread.
 *
 * <h2>Use case: database connection pool</h2>
 * A pool has exactly {@code MAX_CONNECTIONS} open connections.
 * Each thread must acquire a permit (= borrow a connection) before running a query.
 * When done it releases the permit (= return the connection).
 * At most {@code MAX_CONNECTIONS} threads hold permits at any time; the rest wait.
 *
 * <pre>
 *   Semaphore pool = new Semaphore(MAX_CONNECTIONS, true); // fair = FIFO wait queue
 *   pool.acquire();  // blocks if 0 permits
 *   try {
 *       runQuery();
 *   } finally {
 *       pool.release();  // always return the permit
 *   }
 * </pre>
 *
 * <h2>tryAcquire()</h2>
 * {@code pool.tryAcquire(500, MILLISECONDS)} attempts to acquire but gives up after
 * the timeout, allowing the caller to handle the "no connection available" case
 * gracefully rather than waiting indefinitely.
 */
public final class SemaphoreDemo {

    private static final int MAX_CONNECTIONS = 3;
    private static final int CLIENT_THREADS  = 8;
    private static final int QUERY_DURATION_MS = 200;

    private SemaphoreDemo() {}

    public static void run() {
        // Fair = true ensures FIFO ordering so early arrivals aren't starved
        Semaphore pool = new Semaphore(MAX_CONNECTIONS, true);
        AtomicInteger queryId = new AtomicInteger(1);

        System.out.printf("  Connection pool: %d connections, %d client threads%n",
                MAX_CONNECTIONS, CLIENT_THREADS);
        System.out.printf("  Each query holds a connection for ~%d ms%n%n",
                QUERY_DURATION_MS);

        Thread[] clients = new Thread[CLIENT_THREADS];
        for (int i = 0; i < CLIENT_THREADS; i++) {
            int clientId = i + 1;
            clients[i] = new Thread(() -> {
                int qid = queryId.getAndIncrement();
                System.out.printf("  [Client-%d] waiting for connection  (available: %d)%n",
                        clientId, pool.availablePermits());
                try {
                    pool.acquire();
                    System.out.printf("  [Client-%d] ✓ acquired connection   (available: %d) → running query #%d%n",
                            clientId, pool.availablePermits(), qid);
                    Thread.sleep(QUERY_DURATION_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pool.release();
                    System.out.printf("  [Client-%d] released connection     (available: %d)%n",
                            clientId, pool.availablePermits());
                }
            }, "Client-" + clientId);
        }

        long start = System.currentTimeMillis();
        for (Thread t : clients) t.start();
        for (Thread t : clients) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("%n  All %d queries completed in %d ms  " +
                "(sequential would take %d ms)%n",
                CLIENT_THREADS, elapsed, (long) CLIENT_THREADS * QUERY_DURATION_MS);
    }
}
