package org.pk.practices.design.videoStreaming;

import java.util.List;

/**
 * The JSON payload placed on the transcode job queue. The two
 * {@code simulate*} fields exist only so this practice can exercise the
 * retry/DLQ path ({@link #simulateTotalFailure}) and the partial-rendition-
 * failure path ({@link #simulateResolutionFailure}) on demand — a real job
 * would carry no such fields.
 */
public record TranscodeJob(
        String videoId,
        String rawObjectKey,
        int durationSeconds,
        List<RenditionSpec> renditions,
        boolean simulateTotalFailure,
        String simulateResolutionFailure
) {
}
