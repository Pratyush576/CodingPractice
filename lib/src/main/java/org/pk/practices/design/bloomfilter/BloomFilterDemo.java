package org.pk.practices.design.bloomfilter;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * End-to-end demonstration of the Bloom filter implementation.
 *
 * <p>Sections covered:
 * <ol>
 *   <li>Configuration math — see how bit count and hash function count scale with n and p</li>
 *   <li>Basic put / mightContain</li>
 *   <li>Empirical false-positive rate measurement</li>
 *   <li>URL deduplicator — web crawler simulation</li>
 *   <li>Serialization round-trip — write to bytes, restore, verify</li>
 *   <li>Filter merge — union of two filters</li>
 * </ol>
 */
public class BloomFilterDemo {

    private static final String LINE = "─".repeat(70);

    public static void main(String[] args) throws IOException {
        section("1. CONFIGURATION MATH");
        demoConfigMath();

        section("2. BASIC PUT / MIGHTCONTAIN");
        demoBasicOps();

        section("3. EMPIRICAL FALSE-POSITIVE RATE");
        demoFalsePositiveRate();

        section("4. URL DEDUPLICATOR — WEB CRAWLER SIMULATION");
        demoUrlDeduplicator();

        section("5. SERIALIZATION ROUND-TRIP");
        demoSerialization();

        section("6. FILTER MERGE");
        demoMerge();
    }

    // ── 1. Config math ────────────────────────────────────────────────────────

    private static void demoConfigMath() {
        System.out.printf("  %-15s %-8s %-15s %-10s %-12s%n",
                "Expected (n)", "FPP", "Bits (m)", "Hashes (k)", "Memory");
        System.out.println("  " + "─".repeat(65));

        long[] ns   = {1_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L};
        double[] ps = {0.01, 0.01, 0.01, 0.01, 0.001};

        for (int i = 0; i < ns.length; i++) {
            BloomFilterConfig cfg = BloomFilterConfig.of(ns[i], ps[i]);
            System.out.printf("  %-15s %-8s %-15s %-10d %-12s%n",
                    String.format("%,d", ns[i]),
                    String.format("%.1f%%", ps[i] * 100),
                    String.format("%,d", cfg.numBits()),
                    cfg.numHashFunctions(),
                    humanBytes(cfg.memoryBytes()));
        }
    }

    // ── 2. Basic ops ──────────────────────────────────────────────────────────

    private static void demoBasicOps() {
        BloomFilter bf = BloomFilter.create(1_000, 0.01);
        bf.put("apple");
        bf.put("banana");
        bf.put("cherry");

        String[] tests = {"apple", "banana", "cherry", "durian", "elderberry", "fig"};
        for (String word : tests) {
            boolean result = bf.mightContain(word);
            System.out.printf("  mightContain(%-12s) → %s%n", "\"" + word + "\"", result);
        }
        System.out.println();
        System.out.println("  " + bf);
    }

    // ── 3. Empirical FPP ─────────────────────────────────────────────────────

    private static void demoFalsePositiveRate() {
        long   n         = 100_000L;
        double targetFpp = 0.01;

        BloomFilter bf = BloomFilter.create(n, targetFpp);
        for (long i = 0; i < n; i++) bf.put("element#" + i);

        // Query elements that were NEVER inserted
        long testCount      = 100_000L;
        long falsePositives = 0;
        for (long i = n; i < n + testCount; i++) {
            if (bf.mightContain("element#" + i)) falsePositives++;
        }

        double empirical = (double) falsePositives / testCount;
        System.out.printf("  Target FPP     : %.2f%%%n", targetFpp * 100);
        System.out.printf("  Estimated FPP  : %.2f%%%n", bf.estimatedFpp() * 100);
        System.out.printf("  Empirical FPP  : %.2f%%  (%,d / %,d false positives)%n",
                empirical * 100, falsePositives, testCount);
        System.out.println("  " + bf);
    }

    // ── 4. URL deduplicator ───────────────────────────────────────────────────

    private static void demoUrlDeduplicator() {
        UrlDeduplicator dedup = new UrlDeduplicator(1_000_000L, 0.001);

        List<String> crawlQueue = buildCrawlQueue();
        long crawled = crawlQueue.stream().filter(dedup::shouldCrawl).count();

        System.out.printf("  Crawl queue size : %,d%n", crawlQueue.size());
        System.out.printf("  Unique pages     : %,d%n", crawled);
        dedup.printStats();

        System.out.println();
        System.out.println("  URL normalisation:");
        String[][] examples = {
                {"https://example.com/page/",         "https://example.com/page"},
                {"HTTP://EXAMPLE.COM/page",            "http://example.com/page"},
                {"https://example.com/page#comments",  "https://example.com/page"},
                {"https://example.com:443/page",       "https://example.com/page"},
        };
        for (String[] pair : examples) {
            System.out.printf("    %-45s → %s%n",
                    pair[0], UrlDeduplicator.normalise(pair[0]));
        }
    }

    private static List<String> buildCrawlQueue() {
        String[] domains = {
                "https://news.example.com",
                "https://blog.example.org",
                "https://docs.example.net"
        };
        Random rng = new Random(42);
        List<String> queue = new ArrayList<>(50_000);

        // 10 000 unique article pages
        for (int i = 0; i < 10_000; i++) {
            queue.add(domains[i % domains.length] + "/article/" + i);
        }
        // 40 000 re-crawl attempts (common in BFS-based crawlers)
        for (int i = 0; i < 40_000; i++) {
            queue.add(domains[rng.nextInt(domains.length)] + "/article/" + rng.nextInt(10_000));
        }
        return queue;
    }

    // ── 5. Serialization ─────────────────────────────────────────────────────

    private static void demoSerialization() throws IOException {
        BloomFilter original = BloomFilter.create(10_000L, 0.01);
        for (int i = 0; i < 5_000; i++) original.put("url-" + i);

        // Serialize to byte array (in production: file, network stream, Redis blob, etc.)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.writeTo(baos);
        byte[] payload = baos.toByteArray();

        // Restore
        BloomFilter restored = BloomFilter.readFrom(new ByteArrayInputStream(payload));

        System.out.printf("  Serialized size       : %s%n", humanBytes(payload.length));
        System.out.printf("  url-1    — original: %-5s  restored: %s%n",
                original.mightContain("url-1"), restored.mightContain("url-1"));
        System.out.printf("  url-4999 — original: %-5s  restored: %s%n",
                original.mightContain("url-4999"), restored.mightContain("url-4999"));
        System.out.printf("  url-9999 — original: %-5s  restored: %s  (never inserted)%n",
                original.mightContain("url-9999"), restored.mightContain("url-9999"));
    }

    // ── 6. Merge ─────────────────────────────────────────────────────────────

    private static void demoMerge() {
        // Two crawler shards each building their own filter
        BloomFilter shardA = BloomFilter.create(50_000L, 0.01);
        BloomFilter shardB = BloomFilter.create(50_000L, 0.01);

        for (int i = 0;       i < 10_000; i++) shardA.put("page-a-" + i);
        for (int i = 10_000;  i < 20_000; i++) shardB.put("page-b-" + i);

        // Coordinator merges shard B into shard A to get the union
        shardA.mergeFrom(shardB);

        System.out.printf("  merged.mightContain(page-a-500)   → %s  (from shard A)%n",
                shardA.mightContain("page-a-500"));
        System.out.printf("  merged.mightContain(page-b-15000) → %s  (from shard B)%n",
                shardA.mightContain("page-b-15000"));
        System.out.printf("  merged.mightContain(page-c-1)     → %s  (never inserted)%n",
                shardA.mightContain("page-c-1"));
        System.out.println("  Merged: " + shardA);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void section(String title) {
        System.out.println("\n" + LINE);
        System.out.println("  " + title);
        System.out.println(LINE);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1_024)      return bytes + " B";
        if (bytes < 1_048_576)  return String.format("%.1f KB", bytes / 1_024.0);
        return                         String.format("%.1f MB", bytes / 1_048_576.0);
    }
}
