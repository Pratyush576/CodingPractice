package org.pk.practices.design.locking.strategy;

/**
 * Common abstraction over different Java locking mechanisms.
 *
 * <h2>Strategy Pattern</h2>
 * Each implementation encapsulates one locking approach and exposes the same
 * counter-based API. The benchmark and demo code programs against this interface,
 * so any new strategy plugs in without changing callers.
 *
 * <h2>Shared counter as the test harness</h2>
 * A shared long counter is a minimal but sufficient vehicle: increment() is a
 * read-modify-write operation that loses updates under concurrent access unless
 * properly synchronised. Correct strategies produce {@code threads × ops} after
 * all threads finish; incorrect ones produce less.
 *
 * <h2>Implementing a new strategy</h2>
 * <pre>
 *   public class MyLockStrategy implements LockingStrategy {
 *       &#64;Override public String name()        { return "MyLock"; }
 *       &#64;Override public String description() { return "One line summary"; }
 *       &#64;Override public void  increment()   { /* acquire, counter++, release *&#47; }
 *       &#64;Override public long  value()       { /* acquire read lock, return counter *&#47; }
 *       &#64;Override public void  reset()       { /* acquire write lock, counter = 0 *&#47; }
 *   }
 * </pre>
 * Then add it to the list in {@code LockingDemo}.
 */
public interface LockingStrategy {

    /** Short identifier used in benchmark tables (e.g. "ReentrantLock"). */
    String name();

    /** One-line description of the locking mechanism for human-readable output. */
    String description();

    /**
     * Atomically increments the shared counter by 1.
     * Implementations must ensure that under concurrent calls from N threads,
     * exactly N increments are recorded (no lost updates).
     */
    void increment();

    /**
     * Returns the current counter value.
     * At minimum the read must be visible (not stale from a local CPU cache);
     * implementations may use a read lock, volatile read, or atomic get.
     */
    long value();

    /** Resets the counter to 0. Typically called before each benchmark run. */
    void reset();
}
