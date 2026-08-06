package org.pk.practices.design.videoStreaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.pk.practices.aws.sqs.LocalSqsQueue;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The producer side of the pipeline — §4.1's resumable/chunked upload,
 * simplified to in-process method calls instead of real HTTP multipart.
 * {@link #completeUpload} returns as soon as the job is enqueued, matching
 * the design's 202-Accepted-immediately behavior; encoding happens
 * entirely off this call path.
 */
public class UploadService {

    private final ObjectStore rawStore;
    private final LocalSqsQueue transcodeQueue;
    private final VideoMetadataService metadata;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ByteArrayOutputStream> inProgress = new ConcurrentHashMap<>();
    private final Map<String, String> titles = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

    public UploadService(ObjectStore rawStore, LocalSqsQueue transcodeQueue, VideoMetadataService metadata) {
        this.rawStore = rawStore;
        this.transcodeQueue = transcodeQueue;
        this.metadata = metadata;
    }

    /** @param contentType the MIME type to serve this file back as later, e.g. "video/mp4" */
    public String beginUpload(String title, String contentType) {
        String videoId = UUID.randomUUID().toString();
        inProgress.put(videoId, new ByteArrayOutputStream());
        titles.put(videoId, title);
        contentTypes.put(videoId, contentType);
        return videoId;
    }

    public void uploadChunk(String videoId, byte[] chunk) {
        inProgress.get(videoId).writeBytes(chunk);
    }

    /**
     * Finalizes the upload and enqueues a transcode job. The
     * {@code simulateTotalFailure}/{@code simulateResolutionFailure}
     * parameters exist only so this practice can demonstrate the
     * retry/DLQ and partial-failure paths on demand — see
     * {@link TranscodeJob}.
     */
    public String completeUpload(String videoId, double complexity, int durationSeconds,
                                  boolean simulateTotalFailure, String simulateResolutionFailure) {
        byte[] raw = inProgress.remove(videoId).toByteArray();
        String rawKey = "raw/" + videoId;
        rawStore.put(rawKey, raw);
        metadata.createProcessing(videoId, titles.remove(videoId), contentTypes.remove(videoId));

        TranscodeJob job = new TranscodeJob(videoId, rawKey, durationSeconds,
                BitrateLadder.perTitle(complexity), simulateTotalFailure, simulateResolutionFailure);
        try {
            transcodeQueue.sendMessage(mapper.writeValueAsString(job));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue transcode job", e);
        }
        return videoId; // 202 Accepted, status=PROCESSING — caller never waits for encoding
    }
}
