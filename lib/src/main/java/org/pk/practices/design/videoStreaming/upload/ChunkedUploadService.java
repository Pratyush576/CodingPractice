package org.pk.practices.design.videoStreaming.upload;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Server-side counterpart to a real resumable/chunked upload protocol
 * (tus-like / S3-multipart-like) — this is what §4.1 actually describes,
 * as opposed to the single "read the whole file in one call" shortcut this
 * practice originally shipped with. Tracks which parts of an upload have
 * arrived so a client can query what's missing and resume instead of
 * restarting from byte zero, verifies a per-part checksum so a corrupted
 * chunk is rejected before ever reaching the assembled file, and sweeps
 * abandoned sessions after a TTL — the same "abort incomplete multipart
 * upload" lifecycle real object stores offer.
 *
 * <p>Deliberately out of scope: parallel/concurrent part uploads (this
 * only tracks state — nothing stops a client from sending parts
 * concurrently, but the reference client in this practice sends them
 * sequentially), authentication on these endpoints, and virus/format
 * validation of the reassembled file.
 */
public class ChunkedUploadService {

    private final UploadService uploadService;
    private final Consumer<String> logSink;
    private final Duration abandonedTtl;
    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public ChunkedUploadService(UploadService uploadService, Consumer<String> logSink, Duration abandonedTtl) {
        this.uploadService = uploadService;
        this.logSink = logSink;
        this.abandonedTtl = abandonedTtl;
    }

    public UploadSession init(String title, String contentType, long totalSizeBytes, int chunkSizeBytes) {
        String uploadId = UUID.randomUUID().toString();
        UploadSession session = new UploadSession(uploadId, title, contentType, totalSizeBytes, chunkSizeBytes);
        sessions.put(uploadId, session);
        logSink.accept("Chunked upload " + uploadId + " initiated: \"" + title + "\" — "
                + totalSizeBytes + " bytes across " + session.totalChunks + " part(s) of up to " + chunkSizeBytes + " bytes each");
        return session;
    }

    public UploadSession session(String uploadId) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw new NoSuchSessionException(uploadId);
        }
        return session;
    }

    public void putChunk(String uploadId, int partNumber, byte[] data, String expectedChecksum) {
        UploadSession session = session(uploadId);
        if (partNumber < 0 || partNumber >= session.totalChunks) {
            throw new IllegalArgumentException("partNumber " + partNumber + " out of range [0, " + session.totalChunks + ")");
        }
        String actualChecksum = checksum(data);
        if (expectedChecksum != null && !expectedChecksum.equals(actualChecksum)) {
            logSink.accept("Chunked upload " + uploadId + ": part " + partNumber + " REJECTED — checksum mismatch"
                    + " (client claimed " + expectedChecksum + ", server computed " + actualChecksum
                    + " over " + data.length + " bytes) — client should retry this part, nothing was written");
            throw new ChecksumMismatchException(partNumber, expectedChecksum, actualChecksum);
        }
        boolean isRetransmit = session.receivedChunks.containsKey(partNumber);
        session.receivedChunks.put(partNumber, data); // idempotent — re-uploading a part just overwrites it
        session.lastActivityAt = Instant.now();
        logSink.accept("Chunked upload " + uploadId + ": part " + partNumber + "/" + (session.totalChunks - 1)
                + " accepted (" + data.length + " bytes, checksum " + actualChecksum + " verified"
                + (isRetransmit ? ", re-transmit of a part already held" : "") + ") — "
                + session.receivedChunks.size() + "/" + session.totalChunks + " parts now held");
    }

    public String complete(String uploadId, double complexity, int durationSeconds,
                            boolean simulateTotalFailure, String simulateResolutionFailure) {
        UploadSession session = session(uploadId);
        if (!session.isComplete()) {
            throw new IncompleteUploadException(session.missingParts());
        }
        byte[] full = session.reassemble();
        logSink.accept("Chunked upload " + uploadId + ": all " + session.totalChunks
                + " part(s) present, reassembled " + full.length
                + " bytes in strict part order — handing off to the ingestion pipeline");
        String videoId = uploadService.beginUpload(session.title, session.contentType);
        uploadService.uploadChunk(videoId, full);
        uploadService.completeUpload(videoId, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure);
        sessions.remove(uploadId);
        return videoId;
    }

    public void abort(String uploadId) {
        UploadSession removed = sessions.remove(uploadId);
        if (removed != null) {
            logSink.accept("Chunked upload " + uploadId + " cancelled — " + removed.receivedChunks.size()
                    + " already-received part(s) discarded, memory released");
        }
    }

    /** Sweeps sessions with no activity within the TTL — mirrors a real object store's abandoned-multipart-upload lifecycle rule. */
    public void sweepAbandoned() {
        Instant cutoff = Instant.now().minus(abandonedTtl);
        sessions.entrySet().removeIf(entry -> {
            boolean abandoned = entry.getValue().lastActivityAt.isBefore(cutoff);
            if (abandoned) {
                logSink.accept("Chunked upload " + entry.getKey() + " swept — abandoned for over " + abandonedTtl.toSeconds()
                        + "s with " + entry.getValue().receivedChunks.size() + "/" + entry.getValue().totalChunks
                        + " part(s) held, releasing memory (a real object store bills you for these until it does the same)");
            }
            return abandoned;
        });
    }

    public static String checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return Long.toHexString(crc.getValue());
    }

    public static class NoSuchSessionException extends RuntimeException {
        public NoSuchSessionException(String uploadId) {
            super("No such upload session: " + uploadId + " (expired, already completed, or the server restarted — all in-memory)");
        }
    }

    public static class ChecksumMismatchException extends RuntimeException {
        public ChecksumMismatchException(int partNumber, String expected, String actual) {
            super("Checksum mismatch for part " + partNumber + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public static class IncompleteUploadException extends RuntimeException {
        public final List<Integer> missingParts;

        public IncompleteUploadException(List<Integer> missingParts) {
            super("Cannot complete upload: missing part(s) " + missingParts);
            this.missingParts = missingParts;
        }
    }
}
