package org.pk.practices.design.videoStreaming.playback;

import org.pk.practices.design.videoStreaming.metadata.ReadyRendition;

import java.util.List;

/**
 * Simulates the client-side ABR decision from §4.4: the lowest rendition
 * on the very first segment regardless of measured bandwidth (fast
 * startup), then a hybrid throughput+buffer algorithm with damped (one
 * rung at a time) switching so it doesn't oscillate.
 */
public class AdaptiveBitratePlayer {

    private static final int LOW_BUFFER_MS = 4000;
    private static final int COMFORTABLE_BUFFER_MS = 8000;
    private static final double THROUGHPUT_SAFETY_MARGIN = 0.8;

    private final List<ReadyRendition> ladder; // ascending by bitrate
    private int currentIndex = -1;

    public AdaptiveBitratePlayer(List<ReadyRendition> renditions) {
        this.ladder = renditions.stream()
                .sorted((a, b) -> Integer.compare(a.bitrateKbps(), b.bitrateKbps()))
                .toList();
    }

    /** Picks the rendition for the next segment given current network conditions. */
    public ReadyRendition selectNextSegment(int measuredThroughputKbps, int bufferHealthMs) {
        if (currentIndex == -1) {
            currentIndex = 0; // fast startup: always begin at the lowest rendition
            return ladder.get(currentIndex);
        }

        int sustainableIndex = 0;
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).bitrateKbps() <= measuredThroughputKbps * THROUGHPUT_SAFETY_MARGIN) {
                sustainableIndex = i;
            }
        }

        if (bufferHealthMs < LOW_BUFFER_MS) {
            currentIndex = Math.max(0, currentIndex - 1); // safety net overrides throughput
        } else if (sustainableIndex > currentIndex && bufferHealthMs > COMFORTABLE_BUFFER_MS) {
            currentIndex = Math.min(ladder.size() - 1, currentIndex + 1); // step up one rung at a time
        } else if (sustainableIndex < currentIndex) {
            currentIndex = Math.max(0, currentIndex - 1); // step down one rung at a time
        }
        return ladder.get(currentIndex);
    }
}
