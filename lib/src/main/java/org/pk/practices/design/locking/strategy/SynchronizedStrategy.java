package org.pk.practices.design.locking.strategy;

/**
 * Locking via Java's built-in {@code synchronized} keyword (intrinsic / monitor lock).
 *
 * <h2>Mechanism</h2>
 * Every Java object has an associated monitor. A {@code synchronized} block acquires
 * that monitor on entry and releases it on exit — even if an exception is thrown.
 * Only one thread can hold a given monitor at a time; all others block in BLOCKED state.
 *
 * <h2>Reentrancy</h2>
 * The same thread can re-enter a {@code synchronized} block guarded by a monitor it
 * already holds. The JVM keeps a hold count; the monitor is released only when the
 * outermost synchronized block exits.
 *
 * <h2>Limitations vs. explicit locks</h2>
 * <ul>
 *   <li>Cannot try to acquire with a timeout ({@code tryLock(timeout)}).</li>
 *   <li>Cannot be interrupted while waiting ({@code lockInterruptibly()}).</li>
 *   <li>Cannot be used as a read/write pair.</li>
 *   <li>Fairness is not guaranteed — the JVM decides which blocked thread gets the lock.</li>
 * </ul>
 *
 * <h2>When to use</h2>
 * Simple, low-contention critical sections where the limitations above are not a
 * concern. The {@code synchronized} keyword is the most readable option and has been
 * JIT-optimised heavily since Java 6 (biased locking, lock elision, lock coarsening).
 */
public final class SynchronizedStrategy implements LockingStrategy {

    private final Object monitor = new Object();
    private long counter = 0;

    @Override
    public String name() { return "synchronized"; }

    @Override
    public String description() { return "Intrinsic monitor lock (JVM built-in keyword)"; }

    @Override
    public void increment() {
        synchronized (monitor) {
            counter++;
        }
    }

    @Override
    public long value() {
        synchronized (monitor) {
            return counter;
        }
    }

    @Override
    public void reset() {
        synchronized (monitor) {
            counter = 0;
        }
    }
}
