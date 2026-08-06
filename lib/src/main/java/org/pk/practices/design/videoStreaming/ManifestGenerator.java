package org.pk.practices.design.videoStreaming;

import java.util.List;
import java.util.Map;

/**
 * Builds a real HLS master playlist (§4.4) from whatever renditions
 * actually succeeded — a partial-failure ladder just produces a manifest
 * missing that rung, rather than blocking on it.
 */
public class ManifestGenerator {

    private static final Map<String, String> DIMENSIONS = Map.of(
            "240p", "426x240",
            "480p", "854x480",
            "720p", "1280x720",
            "1080p", "1920x1080",
            "4K", "3840x2160"
    );

    public static String buildMasterPlaylist(List<ReadyRendition> renditions) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n");
        renditions.stream()
                .sorted((a, b) -> Integer.compare(a.bitrateKbps(), b.bitrateKbps()))
                .forEach(r -> {
                    String dims = DIMENSIONS.getOrDefault(r.resolution(), "unknown");
                    sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(r.bitrateKbps() * 1000)
                      .append(",RESOLUTION=").append(dims).append('\n');
                    sb.append(r.resolution()).append("/index.m3u8\n");
                });
        return sb.toString();
    }
}
