package org.pk.practices.design.locking.strategy;

import java.util.concurrent.locks.StampedLock;

/**
 * Locking via {@link StampedLock} with an optimistic read path.
 *
 * <h2>Three modes</h2>
 * {@code StampedLock} extends the read/write model with a third, lock-free mode:
 * <pre>
 *   1. Write lock    — exclusive, like ReadWriteLock's write lock.
 *   2. Read lock     — shared, like ReadWriteLock's read lock.
 *   3. Optimistic read — NO lock acquired; reads proceed without blocking.
 *                        After reading, call validate(stamp) to check whether a
 *                        writer sneaked in. If validation fails, fall back to
 *                        a real read lock.
 * </pre>
 *
 * <h2>Optimistic read: how it works</h2>
 * <pre>
 *   long stamp = lock.tryOptimisticRead();   // reads a version counter, no lock
 *   long snapshot = counter;                 // read without holding any lock
 *   if (!lock.validate(stamp)) {             // was a write interleaved?
 *       stamp = lock.readLock();             // yes → fall back to real read
 *       try { snapshot = counter; }
 *       finally { lock.unlockRead(stamp); }
 *   }
 *   return snapshot;                         // guaranteed consistent
 * </pre>
 * In a read-dominated workload with rare writes, {@code validate} almost always succeeds,
 * so reads are essentially free (just a volatile read of the version counter).
 *
 * <h2>StampedLock vs. ReadWriteLock</h2>
 * <pre>
 *   Metric             ReadWriteLock      StampedLock
 *   ─────────────────────────────────────────────────
 *   Concurrent reads   Yes                Yes (+ lock-free reads)
 *   Reentrant          Yes                NO — a thread must not re-acquire
 *   Condition vars     Yes                NO
 *   Read → Write       No (downgrade)     Yes (upgrade with tryConvertToWriteLock)
 *   Throughput (reads) High               Very high
 * </pre>
 *
 * <h2>Critical limitations</h2>
 * <ul>
 *   <li><b>Not reentrant</b> — a thread that holds a stamp must not try to acquire again;
 *       it will deadlock.</li>
 *   <li><b>No Condition support</b> — cannot use {@code await}/{@code signal} patterns.</li>
 *   <li>Stamps are {@code long} values — must be passed through the call chain explicitly.</li>
 * </ul>
 *
 * <h2>When to use</h2>
 * High-frequency reads with rare writes where neither reentrancy nor Conditions are
 * needed: rate tables, route caches, configuration snapshots.
 */
public final class StampedLockStrategy implements LockingStrategy {

    private final StampedLock lock = new StampedLock();
    private long counter = 0;

    @Override
    public String name() { return "StampedLock"; }

    @Override
    public String description() {
        return "Optimistic read (lock-free) + write lock; highest read throughput";
    }

    /** Acquires the exclusive write lock; increments; releases. */
    @Override
    public void increment() {
        long stamp = lock.writeLock();
        try {
            counter++;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Attempts an optimistic (lock-free) read first.
     * Falls back to a real shared read lock only if a writer was concurrent.
     * In a write-rare workload this path executes without acquiring any lock.
     */
    @Override
    public long value() {
        // ── Optimistic path (no lock) ─────────────────────────────────────────
        long stamp = lock.tryOptimisticRead();
        long snapshot = counter;                    // unsynchronised read — may be stale

        if (lock.validate(stamp)) {
            return snapshot;                        // fast path: no writer was concurrent
        }

        // ── Fallback: real read lock ──────────────────────────────────────────
        stamp = lock.readLock();
        try {
            return counter;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    @Override
    public void reset() {
        long stamp = lock.writeLock();
        try {
            counter = 0;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
