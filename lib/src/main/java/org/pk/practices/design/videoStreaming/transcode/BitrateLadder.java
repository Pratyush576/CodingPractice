package org.pk.practices.design.videoStreaming.transcode;

import java.util.List;

/**
 * Builds the set of renditions to encode for a video. {@link #perTitle}
 * demonstrates the design doc's §4.2 per-title encoding idea: scale each
 * rung's bitrate by a per-source complexity score instead of paying the
 * same bitrate for every title regardless of how much detail it actually
 * needs — a static talking-head video costs less than an action scene at
 * the same resolution.
 */
public class BitrateLadder {

    private record Rung(String resolution, int baseBitrateKbps, String codec) {
    }

    private static final List<Rung> STANDARD = List.of(
            new Rung("240p", 400, "h264"),
            new Rung("480p", 1000, "h264"),
            new Rung("720p", 2500, "h264"),
            new Rung("1080p", 5000, "h264"),
            new Rung("4K", 16000, "h264")
    );

    /** The fixed ladder, ignoring source complexity. */
    public static List<RenditionSpec> standard() {
        return STANDARD.stream()
                .map(r -> new RenditionSpec(r.resolution(), r.baseBitrateKbps(), r.codec()))
                .toList();
    }

    /**
     * Scales every rung's bitrate by {@code complexity} (0.0 = simplest
     * possible source, 1.0 = most complex/high-motion) instead of one
     * fixed ladder for all content.
     */
    public static List<RenditionSpec> perTitle(double complexity) {
        double factor = 0.6 + 0.6 * Math.clamp(complexity, 0.0, 1.0);
        return STANDARD.stream()
                .map(r -> new RenditionSpec(r.resolution(), (int) Math.round(r.baseBitrateKbps() * factor), r.codec()))
                .toList();
    }
}
