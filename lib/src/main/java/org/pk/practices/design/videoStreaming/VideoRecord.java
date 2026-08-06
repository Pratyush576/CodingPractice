package org.pk.practices.design.videoStreaming;

import java.util.List;

/** Immutable snapshot of a video's metadata at a point in time. */
public record VideoRecord(
        String id,
        String title,
        String contentType,
        VideoStatus status,
        List<ReadyRendition> readyRenditions,
        String manifestKey,
        List<String> failedResolutions,
        String failureReason,
        long createdAtMillis
) {
    static VideoRecord processing(String id, String title, String contentType) {
        return new VideoRecord(id, title, contentType, VideoStatus.PROCESSING, List.of(), null, List.of(), null, System.currentTimeMillis());
    }

    VideoRecord withReady(List<ReadyRendition> renditions, String manifestKey, List<String> failedResolutions) {
        return new VideoRecord(id, title, contentType, VideoStatus.READY, renditions, manifestKey, failedResolutions, null, createdAtMillis);
    }

    VideoRecord withFailed(String reason) {
        return new VideoRecord(id, title, contentType, VideoStatus.FAILED, List.of(), null, List.of(), reason, createdAtMillis);
    }
}
