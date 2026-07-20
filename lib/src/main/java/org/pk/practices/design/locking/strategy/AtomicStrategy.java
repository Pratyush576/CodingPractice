package org.pk.practices.design.locking.strategy;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free synchronisation via {@link AtomicLong} and hardware CAS (Compare-And-Swap).
 *
 * <h2>What CAS means</h2>
 * CAS is a single hardware instruction ({@code LOCK CMPXCHG} on x86) that:
 * <ol>
 *   <li>Reads the current value of a memory location.</li>
 *   <li>Compares it with an expected value.</li>
 *   <li>If equal, writes the new value — atomically. Otherwise does nothing.</li>
 * </ol>
 * {@code AtomicLong.incrementAndGet()} is implemented as:
 * <pre>
 *   do {
 *       current = get();        // volatile read
 *       next    = current + 1;
 *   } while (!compareAndSet(current, next));  // retry if another thread changed it
 * </pre>
 *
 * <h2>Lock-free vs. wait-free</h2>
 * <ul>
 *   <li><b>Lock-free</b> — system-wide progress is guaranteed (at least one thread
 *       makes progress per step), but individual threads may retry many times under
 *       high contention.</li>
 *   <li><b>Wait-free</b> — every thread completes in a bounded number of steps
 *       regardless of others (stronger guarantee, harder to implement).</li>
 * </ul>
 * {@code AtomicLong} is lock-free. Under heavy write contention many threads spin in
 * the CAS loop simultaneously, which can be slower than a mutex (threads sleep while
 * waiting for a lock, freeing the CPU; spinners do not).
 *
 * <h2>Performance profile</h2>
 * <pre>
 *   Low contention  (few threads): very fast — usually 0 retries
 *   High contention (many threads, same counter): slower — many CAS retries
 *   Compare to: volatile long    — no atomicity, race conditions
 *               synchronized     — OS-mediated sleep, no spin waste
 * </pre>
 *
 * <h2>When to use</h2>
 * Counters, statistics, sequence numbers, reference counts, and any case where
 * contention is expected to be low or where you can tolerate spin retries in
 * exchange for avoiding kernel-mode lock overhead.
 */
public final class AtomicStrategy implements LockingStrategy {

    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public String name() { return "AtomicLong"; }

    @Override
    public String description() {
        return "Lock-free CAS via AtomicLong — no OS lock, hardware instruction level";
    }

    /** Single hardware CAS instruction — no lock acquired, no thread blocked. */
    @Override
    public void increment() {
        counter.incrementAndGet();
    }

    /** Volatile read — always sees the latest written value without a lock. */
    @Override
    public long value() {
        return counter.get();
    }

    @Override
    public void reset() {
        counter.set(0);
    }
}
