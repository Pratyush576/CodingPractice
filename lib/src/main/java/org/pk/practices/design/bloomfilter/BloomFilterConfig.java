package org.pk.practices.design.bloomfilter;

/**
 * Immutable configuration for a Bloom filter, computed from two caller-supplied parameters:
 * expected number of insertions <em>n</em> and target false-positive probability <em>p</em>.
 *
 * <h2>Derivation of optimal parameters</h2>
 * The false-positive rate of a Bloom filter with <em>m</em> bits, <em>k</em> hash functions,
 * and <em>n</em> inserted elements is approximately:
 * <pre>
 *   p ≈ (1 − e^(−kn/m))^k
 * </pre>
 * Minimising over <em>k</em> and <em>m</em> independently gives:
 * <pre>
 *   m = ceil( −n · ln(p) / (ln 2)² )     [optimal bit count]
 *   k = round( (m/n) · ln 2 )             [optimal hash function count]
 * </pre>
 * These are the formulas used by {@link #of(long, double)}.
 *
 * <h2>Thread safety</h2>
 * This is an immutable record — all fields are {@code final} and safe to share across threads.
 */
public record BloomFilterConfig(
        long   expectedInsertions,
        double falsePositiveProbability,
        long   numBits,
        int    numHashFunctions) {

    private static final double LN2         = Math.log(2.0);
    private static final double LN2_SQUARED  = LN2 * LN2;
    private static final long   MIN_BITS     = 64L;

    /** Validates all fields eagerly at construction time. */
    public BloomFilterConfig {
        if (expectedInsertions <= 0)
            throw new IllegalArgumentException(
                    "expectedInsertions must be positive, got " + expectedInsertions);
        if (falsePositiveProbability <= 0.0 || falsePositiveProbability >= 1.0)
            throw new IllegalArgumentException(
                    "falsePositiveProbability must be in (0, 1), got " + falsePositiveProbability);
        if (numBits < MIN_BITS)
            throw new IllegalArgumentException("numBits must be >= " + MIN_BITS);
        if (numHashFunctions <= 0)
            throw new IllegalArgumentException("numHashFunctions must be positive");
    }

    /**
     * Factory — derives optimal {@code numBits} and {@code numHashFunctions} from the
     * caller's budget constraints.
     *
     * @param expectedInsertions       estimated number of distinct elements to insert; must be > 0
     * @param falsePositiveProbability acceptable false-positive rate, e.g. {@code 0.01} for 1 %;
     *                                 must be in (0, 1)
     */
    public static BloomFilterConfig of(long expectedInsertions, double falsePositiveProbability) {
        long numBits          = optimalBits(expectedInsertions, falsePositiveProbability);
        int  numHashFunctions = optimalHashes(expectedInsertions, numBits);
        return new BloomFilterConfig(expectedInsertions, falsePositiveProbability, numBits, numHashFunctions);
    }

    // ── Formulae ─────────────────────────────────────────────────────────────

    /** m = max(64, ceil(−n·ln(p) / (ln2)²)) */
    static long optimalBits(long n, double p) {
        return Math.max(MIN_BITS, (long) Math.ceil(-n * Math.log(p) / LN2_SQUARED));
    }

    /** k = max(1, round(m/n · ln2)) */
    static int optimalHashes(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * LN2));
    }

    // ── Derived metrics ───────────────────────────────────────────────────────

    /**
     * Estimates the current false-positive rate given {@code insertions} elements inserted so far.
     * Formula: {@code (1 − e^(−k·n/m))^k}
     */
    public double estimateFpp(long insertions) {
        return Math.pow(1.0 - Math.exp(-(double) numHashFunctions * insertions / numBits),
                numHashFunctions);
    }

    /**
     * Number of {@code long} words needed to hold {@code numBits} bits.
     * Capped at {@link Integer#MAX_VALUE} because {@link java.util.concurrent.atomic.AtomicLongArray}
     * takes an {@code int} index.
     */
    int arrayLength() {
        long len = (numBits + 63) / 64;
        if (len > Integer.MAX_VALUE)
            throw new ArithmeticException(
                    "Filter requires " + len + " longs, which exceeds int-index limit");
        return (int) len;
    }

    /** Approximate heap footprint of the bit array in bytes. */
    public long memoryBytes() {
        return (long) arrayLength() * Long.BYTES;
    }
}
