package org.pk.practices.design.locking.strategy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Locking via {@link ReentrantLock} — the explicit, feature-rich alternative to {@code synchronized}.
 *
 * <h2>Advantages over synchronized</h2>
 * <ul>
 *   <li><b>tryLock(timeout)</b> — attempt to acquire without blocking forever:
 *       {@code if (lock.tryLock(500, MILLISECONDS)) { ... }}</li>
 *   <li><b>lockInterruptibly()</b> — a waiting thread can be woken via
 *       {@code thread.interrupt()} instead of waiting indefinitely.</li>
 *   <li><b>Fairness</b> — {@code new ReentrantLock(true)} uses FIFO ordering so no
 *       thread starves; default (unfair) is faster on average.</li>
 *   <li><b>Condition variables</b> — multiple {@link java.util.concurrent.locks.Condition}
 *       objects per lock (vs. a single wait-set for {@code synchronized}).</li>
 *   <li><b>Explicit unlock visibility</b> — the lock/unlock structure is visible in code,
 *       useful when lock and unlock span different methods.</li>
 * </ul>
 *
 * <h2>Mandatory pattern</h2>
 * Always release in {@code finally} to prevent lock leaks:
 * <pre>
 *   lock.lock();
 *   try {
 *       // critical section
 *   } finally {
 *       lock.unlock();
 *   }
 * </pre>
 *
 * <h2>When to use</h2>
 * Any scenario requiring timed acquisition, interruptible waiting, fairness guarantees,
 * or multiple condition variables per guarded object.
 */
public final class ReentrantLockStrategy implements LockingStrategy {

    private final ReentrantLock lock;
    private long counter = 0;

    /** Creates a strategy with the given fairness policy. */
    public ReentrantLockStrategy(boolean fair) {
        this.lock = new ReentrantLock(fair);
    }

    /** Creates an unfair (default, higher throughput) strategy. */
    public ReentrantLockStrategy() {
        this(false);
    }

    @Override
    public String name() {
        return lock.isFair() ? "ReentrantLock(fair)" : "ReentrantLock";
    }

    @Override
    public String description() {
        return lock.isFair()
                ? "Explicit reentrant lock with FIFO fairness (prevents starvation)"
                : "Explicit reentrant lock, unfair mode (higher throughput)";
    }

    @Override
    public void increment() {
        lock.lock();
        try {
            counter++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long value() {
        lock.lock();
        try {
            return counter;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            counter = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Demonstrates {@code tryLock} — returns true only if the lock was acquired immediately.
     * Returns false (and does NOT increment) if another thread holds the lock right now.
     * Useful for "best effort" operations where skipping is acceptable.
     */
    public boolean tryIncrement() {
        if (lock.tryLock()) {
            try {
                counter++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;
    }

    /**
     * Demonstrates {@code tryLock(timeout)} — waits up to the given duration for the lock.
     * Returns false if the timeout elapses without acquiring.
     */
    public boolean tryIncrementWithTimeout(long timeoutMs) throws InterruptedException {
        if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            try {
                counter++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;
    }

    /**
     * Demonstrates {@code lockInterruptibly()} — the calling thread can be woken by
     * {@code Thread.interrupt()} while it is waiting. Throws {@link InterruptedException}
     * in that case, whereas plain {@code lock()} ignores interrupts until it acquires.
     */
    public void interruptibleIncrement() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            counter++;
        } finally {
            lock.unlock();
        }
    }
}
