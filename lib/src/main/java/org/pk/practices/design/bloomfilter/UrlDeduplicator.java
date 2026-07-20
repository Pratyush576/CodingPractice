package org.pk.practices.design.bloomfilter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web-crawler URL deduplicator backed by a {@link BloomFilter}.
 *
 * <h2>Use case</h2>
 * A web crawler must avoid re-crawling pages it has already visited. A hash set of
 * visited URLs consumes O(n) memory proportional to URL string length — easily
 * gigabytes at scale. A Bloom filter stores a <em>fingerprint</em> of each URL,
 * reducing memory by 20-40× at a configurable false-positive cost (a small percentage
 * of unvisited pages are incorrectly skipped). For most crawlers this tradeoff is
 * entirely acceptable.
 *
 * <h2>URL normalisation</h2>
 * Two URLs that point to the same resource must produce the same fingerprint.
 * Normalisation rules applied by {@link #normalise(String)}:
 * <ol>
 *   <li>Scheme and host are lower-cased.</li>
 *   <li>Fragment identifiers ({@code #section}) are stripped — they are client-side
 *       anchors and never sent to the server.</li>
 *   <li>Trailing slashes are stripped from the path (except the root {@code "/"}).</li>
 *   <li>Default ports (80 for HTTP, 443 for HTTPS) are omitted.</li>
 *   <li>Query strings are preserved — they change server-side content.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * All public methods are safe to call concurrently from multiple crawler threads.
 * {@link BloomFilter} is thread-safe; {@link AtomicLong} counters are non-blocking.
 *
 * <h2>Design pattern: Facade</h2>
 * This class hides the probabilistic internals of {@link BloomFilter} behind a
 * domain-specific API ({@link #shouldCrawl}, {@link #hasBeenSeen}) that speaks the
 * language of the crawler rather than the filter.
 */
public final class UrlDeduplicator {

    private final BloomFilter filter;
    private final AtomicLong  totalQueued        = new AtomicLong();
    private final AtomicLong  duplicatesBlocked  = new AtomicLong();

    /**
     * @param expectedUrls       estimated number of distinct URLs the crawler will encounter
     * @param falsePositiveRate  acceptable probability of incorrectly skipping a new URL
     *                           (e.g. {@code 0.001} = 0.1 %)
     */
    public UrlDeduplicator(long expectedUrls, double falsePositiveRate) {
        this.filter = BloomFilter.create(expectedUrls, falsePositiveRate);
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Determines whether {@code url} should be crawled and records it as seen.
     *
     * <p>Normalises the URL before querying the filter, so
     * {@code "HTTP://Example.COM/page/"} and {@code "http://example.com/page"} are
     * treated as the same URL.
     *
     * @return {@code true}  — URL is new; the caller should crawl it.<br>
     *         {@code false} — URL was already seen (or false positive); skip it.
     */
    public boolean shouldCrawl(String url) {
        String normalised = normalise(url);
        if (normalised == null) return false;   // malformed URL — skip

        totalQueued.incrementAndGet();
        boolean isNew = filter.put(normalised);
        if (!isNew) duplicatesBlocked.incrementAndGet();
        return isNew;
    }

    /**
     * Read-only membership test — does not modify the filter.
     * Useful for pre-flight checks before adding a URL to the crawl queue.
     *
     * @return {@code true} if the URL has likely been seen before, {@code false} if definitely not
     */
    public boolean hasBeenSeen(String url) {
        String normalised = normalise(url);
        return normalised != null && filter.mightContain(normalised);
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /** Total URL submissions (including duplicates). */
    public long totalQueued()        { return totalQueued.get(); }

    /** Number of submissions that were recognised as duplicates. */
    public long duplicatesBlocked()  { return duplicatesBlocked.get(); }

    /** Fraction of submissions that were duplicates. */
    public double dedupeRatio() {
        long q = totalQueued.get();
        return q == 0 ? 0.0 : (double) duplicatesBlocked.get() / q;
    }

    /** Returns the underlying filter (for inspecting config, FPP, serialization). */
    public BloomFilter filter() { return filter; }

    public void printStats() {
        System.out.printf("  URLs queued       : %,d%n",    totalQueued.get());
        System.out.printf("  Duplicates blocked: %,d%n",    duplicatesBlocked.get());
        System.out.printf("  Dedupe ratio      : %.1f%%%n", dedupeRatio() * 100);
        System.out.printf("  Estimated FPP     : %.4f%%%n", filter.estimatedFpp() * 100);
        System.out.printf("  Filter            : %s%n",     filter);
    }

    // ── URL normalisation ─────────────────────────────────────────────────────

    /**
     * Normalises a raw URL string into a canonical form for deduplication.
     * Returns {@code null} for malformed or empty input.
     *
     * <p>Exposed as {@code static} with package-level access for testability.
     */
    static String normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = new URI(raw.trim());

            String scheme = uri.getScheme() == null
                    ? "http"
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            String host   = uri.getHost() == null
                    ? ""
                    : uri.getHost().toLowerCase(Locale.ROOT);
            int    port   = uri.getPort();
            String path   = uri.getPath() == null ? "" : uri.getPath();
            String query  = uri.getQuery();
            // Fragment deliberately omitted — it's client-side only

            // Strip trailing slashes, but preserve bare root "/"
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // Omit default ports for cleaner canonical form
            String portPart = "";
            if (port != -1
                    && !(scheme.equals("http")  && port == 80)
                    && !(scheme.equals("https") && port == 443)) {
                portPart = ":" + port;
            }

            return scheme + "://" + host + portPart + path
                    + (query != null ? "?" + query : "");
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
