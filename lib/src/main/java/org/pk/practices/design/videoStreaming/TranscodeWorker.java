package org.pk.practices.design.videoStreaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.pk.practices.aws.sqs.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The consumer side of the pipeline (§4.2/§8.3): decodes a
 * {@link TranscodeJob}, "encodes" each requested rendition, and updates
 * metadata. Meant to be wired as an
 * {@link org.pk.practices.aws.sqs.QueueConsumer.MessageHandler} — throwing
 * from {@link #process} leaves the whole job in flight to redeliver (and
 * eventually dead-letter), matching real SQS consumer semantics exactly,
 * since this literally reuses {@code org.pk.practices.aws.sqs}'s queue.
 *
 * <p>Every step is reported through {@code logSink} rather than a hardcoded
 * {@code System.out.println}, so a caller can route this worker's internal
 * play-by-play anywhere — stdout for the CLI demo, or a browser-visible
 * activity log for the console server — without this class knowing which.
 */
public class TranscodeWorker {

    private final ObjectStore rawStore;
    private final ObjectStore processedStore;
    private final VideoMetadataService metadata;
    private final int maxReceiveCount;
    private final Consumer<String> logSink;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscodeWorker(ObjectStore rawStore, ObjectStore processedStore, VideoMetadataService metadata,
                            int maxReceiveCount, Consumer<String> logSink) {
        this.rawStore = rawStore;
        this.processedStore = processedStore;
        this.metadata = metadata;
        this.maxReceiveCount = maxReceiveCount;
        this.logSink = logSink;
    }

    public void process(Message message) throws Exception {
        TranscodeJob job = mapper.readValue(message.body(), TranscodeJob.class);
        log("Picked up " + job.videoId() + ": " + job.renditions().size()
                + " renditions requested, attempt " + message.receiveCount()
                + " (message receiptHandle changes every redelivery — this is a fresh one)");

        if (job.simulateTotalFailure()) {
            // A worker can inspect its own receive count (exposed on every
            // Message) and proactively mark the business-level status FAILED
            // on what it knows is its last attempt — independent of, and
            // slightly ahead of, the queue's own DLQ redirect, which only
            // happens once this attempt's visibility timeout later expires.
            if (message.receiveCount() >= maxReceiveCount) {
                metadata.markFailed(job.videoId(), "exhausted " + maxReceiveCount + " encode attempts");
                log(job.videoId() + " marked FAILED in metadata (about to dead-letter once this attempt's visibility timeout expires)");
            }
            throw new RuntimeException("simulated total encode failure for " + job.videoId());
        }

        log("Reading raw bytes from " + job.rawObjectKey() + " for " + job.videoId());
        byte[] raw = rawStore.get(job.rawObjectKey());
        List<ReadyRendition> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (RenditionSpec spec : job.renditions()) {
            Thread.sleep(80); // simulate per-rendition encode work
            if (spec.resolution().equals(job.simulateResolutionFailure())) {
                failed.add(spec.resolution());
                log("  " + spec.resolution() + " encode FAILED (simulated) — leaving this rung out of the ladder, not failing the whole job");
                continue;
            }
            // Idempotent key: a duplicate delivery of this same job overwrites
            // the same bytes at the same key instead of creating a second
            // copy — the exact mechanism described in the design doc's §8.3.
            String key = "processed/%s/%s".formatted(job.videoId(), spec.resolution());
            byte[] placeholder = simulatedEncodedBytes(raw);
            processedStore.put(key, placeholder);
            succeeded.add(new ReadyRendition(spec.resolution(), spec.bitrateKbps(), key));
            long theoreticalBytes = (long) spec.bitrateKbps() * 1000 / 8 * job.durationSeconds();
            log("  " + spec.resolution() + " (" + spec.bitrateKbps() + " kbps) -> wrote " + key
                    + " [" + placeholder.length + "-byte placeholder; a real encode at this bitrate for "
                    + job.durationSeconds() + "s would be ~" + (theoreticalBytes / 1024) + " KB]");
        }

        if (succeeded.isEmpty()) {
            log(job.videoId() + ": every rendition failed, not just the simulated one — leaving the job in flight to retry");
            throw new RuntimeException("all renditions failed for " + job.videoId());
        }

        String manifestKey = "processed/%s/master.m3u8".formatted(job.videoId());
        processedStore.put(manifestKey, ManifestGenerator.buildMasterPlaylist(succeeded).getBytes());
        log("Wrote HLS master playlist to " + manifestKey + " listing " + succeeded.size() + " rendition(s)");
        metadata.markReady(job.videoId(), succeeded, manifestKey, failed);
        log("Marked " + job.videoId() + " READY in metadata");

        if (!failed.isEmpty()) {
            log(job.videoId() + " is READY with a reduced ladder (missing " + failed
                    + ") — a real system would enqueue a separate backfill job for these; this practice keeps it simple");
        } else {
            log(job.videoId() + " is READY with all " + succeeded.size() + " renditions");
        }
    }

    private byte[] simulatedEncodedBytes(byte[] raw) {
        // Placeholder only — no real codec here. Small and fixed-size so
        // the demo stays fast; see the logged "theoretical bytes" line
        // above for what a real encode at that bitrate would produce.
        byte[] fake = new byte[256];
        for (int i = 0; i < fake.length; i++) {
            fake[i] = raw.length > 0 ? raw[i % raw.length] : (byte) i;
        }
        return fake;
    }

    private void log(String message) {
        logSink.accept(message);
    }
}
