package org.pk.practices.design.bloomfilter;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe, space-efficient probabilistic set membership filter.
 *
 * <h2>What a Bloom filter guarantees</h2>
 * <ul>
 *   <li><b>No false negatives</b> — if an element was {@link #put} into the filter,
 *       {@link #mightContain} will always return {@code true}.</li>
 *   <li><b>Bounded false positives</b> — {@link #mightContain} may return {@code true}
 *       for an element that was never inserted, with probability ≤ the configured FPP.</li>
 *   <li><b>No deletion</b> — bits can only be set, never cleared. Use a counting Bloom
 *       filter variant if deletion is required.</li>
 * </ul>
 *
 * <h2>Hash strategy: Kirsch-Mitzenmacher double hashing</h2>
 * Two independent 64-bit hashes are produced by {@link MurmurHash3} in a single pass.
 * All <em>k</em> bit positions are derived via:
 * <pre>
 *   bitIndex_i = (h1 + i · h2) mod m,    i = 0, 1, …, k−1
 * </pre>
 * This linear combination is provably asymptotically equivalent to k independent hash
 * functions (Kirsch &amp; Mitzenmacher, ESA 2006) while requiring only one hash evaluation.
 *
 * <h2>Thread safety</h2>
 * The bit array is an {@link AtomicLongArray}. Each {@link #put} uses a CAS
 * (compare-and-swap) loop per bit position — lock-free and safe under high concurrency.
 * The insertion counter is an {@link AtomicLong}.
 *
 * <h2>Serialization</h2>
 * {@link #writeTo(OutputStream)} / {@link #readFrom(InputStream)} persist the filter
 * in a compact binary format (config header + raw long words).
 */
public final class BloomFilter implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final BloomFilterConfig config;
    private final AtomicLongArray   bits;
    private final AtomicLong        insertionCount;

    // ── Construction ──────────────────────────────────────────────────────────

    private BloomFilter(BloomFilterConfig config) {
        this.config         = config;
        this.bits           = new AtomicLongArray(config.arrayLength());
        this.insertionCount = new AtomicLong(0L);
    }

    /**
     * Creates a new, empty Bloom filter sized for the given workload.
     *
     * @param expectedInsertions       estimated number of distinct elements you will insert; must be &gt; 0
     * @param falsePositiveProbability target false-positive rate (e.g. {@code 0.01} = 1 %); must be in (0, 1)
     */
    public static BloomFilter create(long expectedInsertions, double falsePositiveProbability) {
        return new BloomFilter(BloomFilterConfig.of(expectedInsertions, falsePositiveProbability));
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Inserts {@code element} into the filter by setting all k derived bit positions.
     *
     * @return {@code true} if at least one bit position was 0 before this call —
     *         meaning the element was definitely not already in the filter.
     *         {@code false} means all bits were already 1 (element present, or false positive).
     */
    public boolean put(String element) {
        long[] h    = MurmurHash3.hash128(element);
        long   h1   = h[0], h2 = h[1];
        boolean anyNew = false;

        for (int i = 0; i < config.numHashFunctions(); i++) {
            long combinedHash = h1 + (long) i * h2;
            // Map to non-negative index, then reduce modulo numBits
            long bitIndex = (combinedHash & Long.MAX_VALUE) % config.numBits();
            if (setBit(bitIndex)) anyNew = true;
        }

        if (anyNew) insertionCount.incrementAndGet();
        return anyNew;
    }

    /**
     * Returns {@code true} if {@code element} <em>might</em> have been inserted,
     * {@code false} if it was <em>definitely not</em> inserted.
     *
     * <p>A {@code true} return value carries a false-positive probability of
     * approximately {@link #estimatedFpp()} at the current fill level.
     */
    public boolean mightContain(String element) {
        long[] h  = MurmurHash3.hash128(element);
        long   h1 = h[0], h2 = h[1];

        for (int i = 0; i < config.numHashFunctions(); i++) {
            long combinedHash = h1 + (long) i * h2;
            long bitIndex = (combinedHash & Long.MAX_VALUE) % config.numBits();
            if (!getBit(bitIndex)) return false;  // definitive: element absent
        }
        return true;
    }

    /**
     * Merges {@code other} into this filter by ORing their bit arrays.
     * After the merge, {@code this} contains all elements from both filters.
     * The union FPP equals that of a filter that had all elements from both inserted.
     *
     * @throws IllegalArgumentException if the two filters have different {@code numBits}
     *                                  or {@code numHashFunctions}
     */
    public void mergeFrom(BloomFilter other) {
        if (this.config.numBits() != other.config.numBits()
                || this.config.numHashFunctions() != other.config.numHashFunctions()) {
            throw new IllegalArgumentException(
                    "Cannot merge Bloom filters with different configurations: "
                    + this.config + " vs " + other.config);
        }
        for (int i = 0; i < bits.length(); i++) {
            // CAS loop: keep retrying until our OR is applied without race-condition loss
            long prev, next;
            do {
                prev = bits.get(i);
                next = prev | other.bits.get(i);
            } while (!bits.compareAndSet(i, prev, next));
        }
    }

    // ── Bit-array primitives ──────────────────────────────────────────────────

    /**
     * Sets the bit at {@code bitIndex}; returns {@code true} if the bit changed 0 → 1.
     * CAS loop ensures atomicity under concurrent writers without a lock.
     */
    private boolean setBit(long bitIndex) {
        int  longIdx = (int) (bitIndex >>> 6);   // bitIndex / 64
        long mask    = 1L << (bitIndex & 63);    // bitIndex % 64

        long prev, next;
        do {
            prev = bits.get(longIdx);
            if ((prev & mask) != 0) return false; // already set — fast exit
            next = prev | mask;
        } while (!bits.compareAndSet(longIdx, prev, next));
        return true;
    }

    /** Returns {@code true} if the bit at {@code bitIndex} is 1. Volatile read via AtomicLongArray. */
    private boolean getBit(long bitIndex) {
        int  longIdx = (int) (bitIndex >>> 6);
        long mask    = 1L << (bitIndex & 63);
        return (bits.get(longIdx) & mask) != 0;
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /** Number of distinct elements inserted (not counting re-insertions of known elements). */
    public long insertionCount()  { return insertionCount.get(); }

    /** Returns the immutable configuration this filter was created with. */
    public BloomFilterConfig config() { return config; }

    /** Estimates false-positive rate at the current fill level. */
    public double estimatedFpp()  { return config.estimateFpp(insertionCount.get()); }

    /** Number of bits currently set to 1 (linear scan — O(m/64)). */
    public long setBitCount() {
        long count = 0;
        for (int i = 0; i < bits.length(); i++) count += Long.bitCount(bits.get(i));
        return count;
    }

    /** Fraction of bits set: {@code setBitCount() / numBits}. */
    public double fillRatio() { return (double) setBitCount() / config.numBits(); }

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Serializes this filter to {@code out} in a compact binary format:
     * <pre>
     *   [8 B]  expectedInsertions   (long)
     *   [8 B]  falsePositiveProbability (double, as raw bits)
     *   [8 B]  numBits              (long)
     *   [4 B]  numHashFunctions     (int)
     *   [8 B]  insertionCount       (long)
     *   [n×8 B] bit array words      (long[])
     * </pre>
     * Total size ≈ config.memoryBytes() + 36 bytes header.
     */
    public void writeTo(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(config.expectedInsertions());
        dos.writeLong(Double.doubleToRawLongBits(config.falsePositiveProbability()));
        dos.writeLong(config.numBits());
        dos.writeInt(config.numHashFunctions());
        dos.writeLong(insertionCount.get());
        for (int i = 0; i < bits.length(); i++) dos.writeLong(bits.get(i));
        dos.flush();
    }

    /**
     * Deserializes a filter written by {@link #writeTo(OutputStream)}.
     * The restored filter is fully functional and has the same FPP characteristics
     * as when it was serialized.
     */
    public static BloomFilter readFrom(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        long   expectedInsertions      = dis.readLong();
        double falsePositiveProbability = Double.longBitsToDouble(dis.readLong());
        long   numBits                 = dis.readLong();
        int    numHashFunctions        = dis.readInt();
        long   savedInsertionCount     = dis.readLong();

        BloomFilterConfig config = new BloomFilterConfig(
                expectedInsertions, falsePositiveProbability, numBits, numHashFunctions);
        BloomFilter filter = new BloomFilter(config);
        filter.insertionCount.set(savedInsertionCount);
        for (int i = 0; i < filter.bits.length(); i++) filter.bits.set(i, dis.readLong());
        return filter;
    }

    @Override
    public String toString() {
        return String.format(
                "BloomFilter{bits=%,d, k=%d, insertions=%,d, fpp=%.4f%%, fill=%.2f%%, mem=%s}",
                config.numBits(), config.numHashFunctions(),
                insertionCount.get(), estimatedFpp() * 100, fillRatio() * 100,
                humanBytes(config.memoryBytes()));
    }

    private static String humanBytes(long b) {
        if (b < 1_024)           return b + " B";
        if (b < 1_048_576)       return String.format("%.1f KB", b / 1_024.0);
        return                          String.format("%.1f MB", b / 1_048_576.0);
    }
}
