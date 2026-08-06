package org.pk.practices.design.videoStreaming.metadata;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single in-memory stand-in for the design doc's sharded metadata
 * service (§4.3) — enough to demonstrate the status lifecycle
 * (PROCESSING -> READY/FAILED) the rest of this pipeline drives.
 */
public class VideoMetadataService {

    private final Map<String, VideoRecord> videos = new ConcurrentHashMap<>();

    public void createProcessing(String videoId, String title, String contentType) {
        videos.put(videoId, VideoRecord.processing(videoId, title, contentType));
    }

    public void markReady(String videoId, List<ReadyRendition> renditions, String manifestKey, List<String> failedResolutions) {
        videos.compute(videoId, (id, current) -> current.withReady(renditions, manifestKey, failedResolutions));
    }

    public void markFailed(String videoId, String reason) {
        videos.compute(videoId, (id, current) -> current.withFailed(reason));
    }

    public VideoRecord get(String videoId) {
        VideoRecord record = videos.get(videoId);
        if (record == null) {
            throw new IllegalArgumentException("No such video: " + videoId);
        }
        return record;
    }

    public List<VideoRecord> listAll() {
        return videos.values().stream()
                .sorted(Comparator.comparingLong(VideoRecord::createdAtMillis).reversed())
                .toList();
    }
}
