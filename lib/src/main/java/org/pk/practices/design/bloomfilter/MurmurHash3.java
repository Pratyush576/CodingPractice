package org.pk.practices.design.bloomfilter;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java implementation of MurmurHash3 (x64, 128-bit variant).
 *
 * <h2>Why MurmurHash3?</h2>
 * Bloom filter correctness depends on the hash functions being near-independent and
 * uniformly distributed across the bit array. MurmurHash3 satisfies both requirements:
 * <ul>
 *   <li><b>Speed</b> — processes 16 bytes per iteration using only shifts, multiplies,
 *       and XORs; no branching in the hot path.</li>
 *   <li><b>Distribution</b> — avalanche property: every output bit depends on every input
 *       bit. Empirically passes SMHasher tests with zero collisions in standard test suites.</li>
 *   <li><b>Non-cryptographic</b> — deliberately not resistant to adversarial inputs;
 *       appropriate for Bloom filters where keys are not attacker-controlled.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * Produces a {@code long[2]} containing two independent 64-bit values (h1, h2).
 * Both values feed into the Kirsch-Mitzenmacher double-hashing scheme in
 * {@link BloomFilter}.
 *
 * <h2>Reference</h2>
 * Austin Appleby, MurmurHash3 (2011).
 * {@code https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp}
 */
final class MurmurHash3 {

    // Mixing constants — chosen empirically to maximise avalanche
    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private MurmurHash3() {}

    /**
     * Hashes a UTF-8 string, returning {@code long[]{h1, h2}} (two independent 64-bit hashes).
     * Seed is fixed at 0 for deterministic, reproducible output across JVM instances.
     */
    static long[] hash128(String key) {
        return hash128(key.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("fallthrough")
    static long[] hash128(byte[] data) {
        final int length = data.length;
        long h1 = 0L, h2 = 0L;

        // ── Body: process 16-byte (128-bit) blocks ────────────────────────────
        int nblocks = length >> 4; // length / 16
        for (int i = 0; i < nblocks; i++) {
            long k1 = getLongLE(data, i * 16);
            long k2 = getLongLE(data, i * 16 + 8);

            // Mix k1 into h1
            k1 *= C1;
            k1  = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;

            h1  = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1  = h1 * 5L + 0x52dce729L;

            // Mix k2 into h2
            k2 *= C2;
            k2  = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;

            h2  = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2  = h2 * 5L + 0x38495ab5L;
        }

        // ── Tail: 0–15 remaining bytes ────────────────────────────────────────
        long k1 = 0L, k2 = 0L;
        int tail = nblocks * 16;

        switch (length & 15) {  // length % 16
            case 15: k2 ^= ((long) (data[tail + 14] & 0xff)) << 48; // fall through
            case 14: k2 ^= ((long) (data[tail + 13] & 0xff)) << 40; // fall through
            case 13: k2 ^= ((long) (data[tail + 12] & 0xff)) << 32; // fall through
            case 12: k2 ^= ((long) (data[tail + 11] & 0xff)) << 24; // fall through
            case 11: k2 ^= ((long) (data[tail + 10] & 0xff)) << 16; // fall through
            case 10: k2 ^= ((long) (data[tail +  9] & 0xff)) <<  8; // fall through
            case  9: k2 ^= ((long) (data[tail +  8] & 0xff));
                     k2 *= C2; k2 = Long.rotateLeft(k2, 33); k2 *= C1; h2 ^= k2;
                     // fall through
            case  8: k1 ^= ((long) (data[tail +  7] & 0xff)) << 56; // fall through
            case  7: k1 ^= ((long) (data[tail +  6] & 0xff)) << 48; // fall through
            case  6: k1 ^= ((long) (data[tail +  5] & 0xff)) << 40; // fall through
            case  5: k1 ^= ((long) (data[tail +  4] & 0xff)) << 32; // fall through
            case  4: k1 ^= ((long) (data[tail +  3] & 0xff)) << 24; // fall through
            case  3: k1 ^= ((long) (data[tail +  2] & 0xff)) << 16; // fall through
            case  2: k1 ^= ((long) (data[tail +  1] & 0xff)) <<  8; // fall through
            case  1: k1 ^= ((long) (data[tail      ] & 0xff));
                     k1 *= C1; k1 = Long.rotateLeft(k1, 31); k1 *= C2; h1 ^= k1;
        }

        // ── Finalisation: mix length in, then apply fmix64 to both halves ─────
        h1 ^= length;
        h2 ^= length;

        // Cross-mix
        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        // Final cross-mix ensures both halves carry influence from all input bits
        h1 += h2;
        h2 += h1;

        return new long[]{h1, h2};
    }

    /**
     * Reads 8 bytes from {@code data[offset..offset+7]} as a little-endian {@code long}.
     * Little-endian matches x86/x64 native byte order for which MurmurHash3 was tuned.
     */
    private static long getLongLE(byte[] data, int offset) {
        return ((long) (data[offset    ] & 0xff)      )
             | ((long) (data[offset + 1] & 0xff) <<  8)
             | ((long) (data[offset + 2] & 0xff) << 16)
             | ((long) (data[offset + 3] & 0xff) << 24)
             | ((long) (data[offset + 4] & 0xff) << 32)
             | ((long) (data[offset + 5] & 0xff) << 40)
             | ((long) (data[offset + 6] & 0xff) << 48)
             | ((long) (data[offset + 7] & 0xff) << 56);
    }

    /**
     * 64-bit finalisation mix (fmix64) — ensures every bit of the input affects every
     * bit of the output (avalanche property). Uses three XOR-shift steps with
     * multiply-by-constant to achieve good bit diffusion.
     */
    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
