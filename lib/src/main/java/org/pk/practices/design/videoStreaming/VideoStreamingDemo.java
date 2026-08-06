package org.pk.practices.design.videoStreaming;

import org.pk.practices.aws.sqs.LocalSqsQueue;
import org.pk.practices.aws.sqs.QueueConsumer;
import org.pk.practices.design.videoStreaming.metadata.ReadyRendition;
import org.pk.practices.design.videoStreaming.metadata.VideoMetadataService;
import org.pk.practices.design.videoStreaming.metadata.VideoRecord;
import org.pk.practices.design.videoStreaming.metadata.VideoStatus;
import org.pk.practices.design.videoStreaming.playback.AdaptiveBitratePlayer;
import org.pk.practices.design.videoStreaming.storage.ObjectStore;
import org.pk.practices.design.videoStreaming.transcode.TranscodeWorker;
import org.pk.practices.design.videoStreaming.upload.UploadService;

import java.time.Duration;

/**
 * Walks the upload -> transcode -> ABR-manifest pipeline end to end:
 * a happy path, a partial-rendition failure that still goes READY with a
 * shorter ladder, and a poison video that exhausts retries into the DLQ.
 * Reuses {@link LocalSqsQueue}/{@link QueueConsumer} from
 * {@code org.pk.practices.aws.sqs} directly for the transcode job queue —
 * the exact connection the design doc's §8.3/§10 describe, not just
 * claimed in prose.
 */
public class VideoStreamingDemo {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_RECEIVE_COUNT = 3;

    public static void main(String[] args) throws InterruptedException {
        ObjectStore rawStore = new ObjectStore();
        ObjectStore processedStore = new ObjectStore();
        VideoMetadataService metadata = new VideoMetadataService();

        LocalSqsQueue dlq = new LocalSqsQueue();
        LocalSqsQueue transcodeQueue = new LocalSqsQueue(MAX_RECEIVE_COUNT, dlq);

        TranscodeWorker worker = new TranscodeWorker(rawStore, processedStore, metadata, MAX_RECEIVE_COUNT,
                msg -> System.out.println("[worker] " + msg));
        QueueConsumer consumer = new QueueConsumer(
                transcodeQueue, worker::process, VISIBILITY_TIMEOUT, Duration.ofMillis(200), 5,
                event -> System.out.println("[queue] " + event));
        consumer.start();

        UploadService uploadService = new UploadService(rawStore, transcodeQueue, metadata);

        System.out.println("=== 1. Happy path: upload -> transcode -> manifest -> ABR playback ===");
        String videoId = uploadService.beginUpload("My Vacation Video", "video/mp4");
        uploadService.uploadChunk(videoId, "chunk-1-bytes".getBytes());
        uploadService.uploadChunk(videoId, "chunk-2-bytes".getBytes());
        uploadService.completeUpload(videoId, /* complexity */ 0.3, /* durationSeconds */ 120, false, null);
        System.out.println("Upload complete -> 202 Accepted, status=" + metadata.get(videoId).status());

        waitForTerminalStatus(metadata, videoId);
        VideoRecord ready = metadata.get(videoId);
        System.out.println("Final status: " + ready.status() + ", renditions=" + ready.readyRenditions().size());
        String manifest = new String(processedStore.get(ready.manifestKey()));
        System.out.println("--- master.m3u8 ---\n" + manifest);

        System.out.println("Simulating playback with a changing network trace:");
        AdaptiveBitratePlayer player = new AdaptiveBitratePlayer(ready.readyRenditions());
        int[][] networkTrace = { // {throughputKbps, bufferHealthMs}
                {6000, 0}, {6000, 3000}, {6000, 9000}, {800, 6000}, {800, 2000}, {6000, 9000}
        };
        for (int[] sample : networkTrace) {
            ReadyRendition chosen = player.selectNextSegment(sample[0], sample[1]);
            System.out.println("  throughput=" + sample[0] + "kbps buffer=" + sample[1]
                    + "ms -> " + chosen.resolution() + " (" + chosen.bitrateKbps() + " kbps)");
        }

        System.out.println();
        System.out.println("=== 2. Partial rendition failure: 4K always fails, rest succeed ===");
        String partialId = uploadService.beginUpload("Action Scene Compilation", "video/mp4");
        uploadService.uploadChunk(partialId, "chunk-bytes".getBytes());
        uploadService.completeUpload(partialId, 0.9, 90, false, "4K");
        waitForTerminalStatus(metadata, partialId);
        VideoRecord partial = metadata.get(partialId);
        System.out.println("Final status: " + partial.status()
                + ", renditions=" + partial.readyRenditions().size()
                + ", missing=" + partial.failedResolutions());

        System.out.println();
        System.out.println("=== 3. Poison video: every rendition fails -> retries exhaust -> DLQ ===");
        String poisonId = uploadService.beginUpload("Corrupted Upload", "video/mp4");
        uploadService.uploadChunk(poisonId, "not-really-a-video".getBytes());
        uploadService.completeUpload(poisonId, 0.5, 60, true, null);
        System.out.println("Waiting through " + MAX_RECEIVE_COUNT + " retries at "
                + VISIBILITY_TIMEOUT.getSeconds() + "s visibility timeout each...");
        Thread.sleep(VISIBILITY_TIMEOUT.toMillis() * (MAX_RECEIVE_COUNT + 1));

        System.out.println("Main queue available/in-flight: "
                + transcodeQueue.approximateAvailableCount() + "/" + transcodeQueue.approximateInFlightCount());
        System.out.println("Dead-letter queue available: " + dlq.approximateAvailableCount());
        System.out.println("Poison video status: " + metadata.get(poisonId).status()
                + " (" + metadata.get(poisonId).failureReason() + ")");

        consumer.stop();
    }

    private static void waitForTerminalStatus(VideoMetadataService metadata, String videoId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (metadata.get(videoId).status() != VideoStatus.PROCESSING) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
