package org.pk.practices.design.videoStreaming;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side state for one in-progress chunked upload — tracks which
 * parts have actually arrived (by part number, not arrival order) so an
 * interrupted upload can resume by sending only what's missing, the same
 * way a real S3 multipart upload or the tus protocol works.
 */
public class UploadSession {

    final String uploadId;
    final String title;
    final String contentType;
    final long totalSizeBytes;
    final int chunkSizeBytes;
    final int totalChunks;
    final Map<Integer, byte[]> receivedChunks = new ConcurrentHashMap<>();
    volatile Instant lastActivityAt;

    UploadSession(String uploadId, String title, String contentType, long totalSizeBytes, int chunkSizeBytes) {
        this.uploadId = uploadId;
        this.title = title;
        this.contentType = contentType;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = Math.max(1, (int) Math.ceil((double) totalSizeBytes / chunkSizeBytes));
        this.lastActivityAt = Instant.now();
    }

    boolean isComplete() {
        return receivedChunks.size() == totalChunks;
    }

    List<Integer> receivedPartNumbers() {
        return receivedChunks.keySet().stream().sorted().toList();
    }

    List<Integer> missingParts() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!receivedChunks.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** Concatenates parts strictly in order 0..totalChunks-1 — arrival order (parallel/out-of-order uploads) never matters. */
    byte[] reassemble() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < totalChunks; i++) {
            out.writeBytes(receivedChunks.get(i));
        }
        return out.toByteArray();
    }
}
