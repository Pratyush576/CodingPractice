package org.pk.practices.design.locking.strategy;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Locking via {@link ReentrantReadWriteLock} — separate read and write lock paths.
 *
 * <h2>Core rule</h2>
 * <ul>
 *   <li><b>Multiple readers</b> may hold the read lock simultaneously — reads are shared.</li>
 *   <li><b>Only one writer</b> may hold the write lock — writes are exclusive.</li>
 *   <li>A writer <em>blocks</em> until all active readers release; readers block while
 *       a writer holds the lock.</li>
 * </ul>
 *
 * <h2>Performance profile</h2>
 * <pre>
 *   Read-heavy workload (90% reads, 10% writes):
 *     synchronized / ReentrantLock : readers block each other → serialised
 *     ReadWriteLock                : readers proceed concurrently → 9× throughput gain
 *
 *   Write-heavy workload (all writes):
 *     ReadWriteLock ≈ ReentrantLock (all threads contend on the write lock)
 * </pre>
 * This is why the benchmark shows two modes: write-heavy (no advantage) and read-heavy
 * (dramatic advantage over plain locks).
 *
 * <h2>Watch-out: write starvation</h2>
 * By default a continuous stream of readers can starve a waiting writer indefinitely.
 * Pass {@code true} (fair mode) to the constructor to enforce FIFO — the next waiting
 * writer blocks new reader acquisitions.
 *
 * <h2>When to use</h2>
 * Caches, configuration stores, directory lookups, and any shared state where reads
 * vastly outnumber writes and reads are not negligibly fast (if reads are nanoseconds,
 * the lock overhead itself dominates — use {@link StampedLockStrategy} instead).
 */
public final class ReadWriteLockStrategy implements LockingStrategy {

    private final ReentrantReadWriteLock rwLock;
    private final ReentrantReadWriteLock.ReadLock  readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    private long counter = 0;

    public ReadWriteLockStrategy() {
        this(false);
    }

    public ReadWriteLockStrategy(boolean fair) {
        this.rwLock    = new ReentrantReadWriteLock(fair);
        this.readLock  = rwLock.readLock();
        this.writeLock = rwLock.writeLock();
    }

    @Override
    public String name() { return "ReadWriteLock"; }

    @Override
    public String description() {
        return "Shared read / exclusive write — concurrent reads, serialised writes";
    }

    /** Write operation — acquires the exclusive write lock. Blocks all readers and other writers. */
    @Override
    public void increment() {
        writeLock.lock();
        try {
            counter++;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read operation — acquires the shared read lock.
     * Multiple threads can call {@code value()} simultaneously without blocking each other,
     * as long as no thread holds the write lock.
     */
    @Override
    public long value() {
        readLock.lock();
        try {
            return counter;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void reset() {
        writeLock.lock();
        try {
            counter = 0;
        } finally {
            writeLock.unlock();
        }
    }

    /** Returns the number of threads currently holding the read lock (diagnostic). */
    public int activeReaderCount() {
        return rwLock.getReadLockCount();
    }

    /** Returns whether any thread currently holds the write lock (diagnostic). */
    public boolean isWriteLocked() {
        return rwLock.isWriteLocked();
    }
}
