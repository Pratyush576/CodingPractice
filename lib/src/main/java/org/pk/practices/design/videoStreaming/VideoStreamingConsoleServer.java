package org.pk.practices.design.videoStreaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import org.pk.practices.aws.sqs.LocalSqsQueue;
import org.pk.practices.aws.sqs.QueueConsumer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A browser UI for the upload -> transcode -> ABR-manifest pipeline: a
 * form to upload (and optionally inject a simulated failure), a live
 * video list showing the PROCESSING -> READY/FAILED transition, a
 * manifest viewer, and a one-click adaptive-bitrate playback simulation
 * against whichever renditions actually succeeded. Same underlying
 * classes as {@link VideoStreamingDemo} — this just gives them a UI
 * instead of a scripted CLI walkthrough.
 */
public class VideoStreamingConsoleServer {

    public static void main(String[] args) {
        ObjectStore rawStore = new ObjectStore();
        ObjectStore processedStore = new ObjectStore();
        VideoMetadataService metadata = new VideoMetadataService();

        LocalSqsQueue dlq = new LocalSqsQueue();
        LocalSqsQueue transcodeQueue = new LocalSqsQueue(3, dlq);

        ActivityLog activityLog = new ActivityLog();
        TranscodeWorker worker = new TranscodeWorker(rawStore, processedStore, metadata, 3, activityLog::add);
        // Short visibility timeout so the poison-video -> DLQ demo (3 retries)
        // finishes in ~6-8s in the browser instead of a long, silent wait.
        QueueConsumer consumer = new QueueConsumer(
                transcodeQueue, worker::process, Duration.ofSeconds(2), Duration.ofMillis(300), 5,
                activityLog::add);
        consumer.start();

        UploadService uploadService = new UploadService(rawStore, transcodeQueue, metadata);
        ObjectMapper mapper = new ObjectMapper();

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/video-console", Location.CLASSPATH);
            config.http.maxRequestSize = 200_000_000L; // allow real video-sized uploads, not just JSON bodies
        });

        app.get("/api/videos", ctx -> ctx.json(metadata.listAll()));

        app.get("/api/queue-stats", ctx -> ctx.json(Map.of(
                "available", transcodeQueue.approximateAvailableCount(),
                "inFlight", transcodeQueue.approximateInFlightCount(),
                "dlqAvailable", dlq.approximateAvailableCount())));

        app.get("/api/worker-events", ctx -> {
            long since = ctx.queryParamAsClass("since", Long.class).getOrDefault(0L);
            ctx.json(activityLog.since(since));
        });

        app.post("/api/upload", ctx -> {
            // Fake-bytes path — for quickly exercising the pipeline's failure
            // modes (below) without needing a real file each time. Not
            // playable: see /api/upload-file for a real video you can watch.
            UploadRequest req = ctx.bodyAsClass(UploadRequest.class);
            activityLog.add("Upload API: received request for \"" + req.title()
                    + "\" (fake bytes, complexity=" + req.complexity() + ", duration=" + req.durationSeconds() + "s)");
            String videoId = uploadService.beginUpload(req.title(), "video/mp4");
            uploadService.uploadChunk(videoId, ("demo-bytes-" + req.title()).getBytes());
            uploadService.completeUpload(videoId, req.complexity(), req.durationSeconds(),
                    req.simulateTotalFailure(), blankToNull(req.simulateResolutionFailure()));
            activityLog.add("Upload API: wrote raw/" + videoId + ", enqueued transcode job, returned 202 to caller — encoding happens off this request entirely");
            ctx.json(Map.of("videoId", videoId));
        });

        app.post("/api/upload-file", ctx -> {
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                throw new BadRequestResponse("No file provided");
            }
            String title = ctx.formParam("title");
            if (title == null || title.isBlank()) {
                title = file.filename();
            }
            double complexity = ctx.formParamAsClass("complexity", Double.class).getOrDefault(0.3);
            int durationSeconds = ctx.formParamAsClass("durationSeconds", Integer.class).getOrDefault(30);
            boolean simulateTotalFailure = ctx.formParamAsClass("simulateTotalFailure", Boolean.class).getOrDefault(false);
            String simulateResolutionFailure = blankToNull(ctx.formParam("simulateResolutionFailure"));
            String contentType = file.contentType() != null ? file.contentType() : "video/mp4";
            activityLog.add("Upload API: receiving real file \"" + file.filename() + "\" (" + file.size()
                    + " bytes, contentType=" + contentType + ")");

            String videoId = uploadService.beginUpload(title, contentType);
            uploadService.uploadChunk(videoId, file.content().readAllBytes());
            uploadService.completeUpload(videoId, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure);
            activityLog.add("Upload API: wrote raw/" + videoId + " (" + file.size()
                    + " real bytes, this exact key is what /play serves back), enqueued transcode job, returned 202");
            ctx.json(Map.of("videoId", videoId));
        });

        app.get("/api/videos/{id}/play", ctx -> {
            VideoRecord video = metadata.get(ctx.pathParam("id"));
            if (video.status() != VideoStatus.READY) {
                throw new BadRequestResponse("Video is not READY (status=" + video.status() + ")");
            }
            byte[] data = rawStore.get("raw/" + video.id());
            String contentType = video.contentType() != null ? video.contentType() : "application/octet-stream";
            ctx.header("Accept-Ranges", "bytes");

            String range = ctx.header("Range");
            if (range == null) {
                activityLog.add("Play API: full-file request for " + video.id() + " (" + data.length + " bytes) — browser didn't ask for a range");
                ctx.contentType(contentType).result(data);
                return;
            }
            String[] bounds = range.replaceFirst("bytes=", "").split("-");
            int start = Integer.parseInt(bounds[0]);
            int end = (bounds.length > 1 && !bounds[1].isBlank()) ? Integer.parseInt(bounds[1]) : data.length - 1;
            end = Math.min(end, data.length - 1);
            byte[] slice = Arrays.copyOfRange(data, start, end + 1);
            activityLog.add("Play API: Range request for " + video.id() + " -> bytes " + start + "-" + end
                    + "/" + data.length + " (this is how the <video> element seeks/buffers)");
            ctx.status(206)
                    .contentType(contentType)
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
                    .header("Content-Length", String.valueOf(slice.length))
                    .result(slice);
        });

        app.get("/api/storage", ctx -> {
            List<ObjectSummary> raw = rawStore.keys().stream().map(k -> objectSummary(k, rawStore.get(k))).toList();
            List<ObjectSummary> processed = processedStore.keys().stream().map(k -> objectSummary(k, processedStore.get(k))).toList();
            activityLog.add("Storage API: snapshot requested — " + raw.size() + " raw object(s), " + processed.size() + " processed object(s)");
            ctx.json(Map.of("raw", raw, "processed", processed));
        });

        app.get("/api/storage/preview", ctx -> {
            String storeName = ctx.queryParam("store");
            String key = ctx.queryParam("key");
            if (key == null || (!"raw".equals(storeName) && !"processed".equals(storeName))) {
                throw new BadRequestResponse("Provide store=raw|processed and a key");
            }
            ObjectStore store = "raw".equals(storeName) ? rawStore : processedStore;
            byte[] data = store.get(key);
            int previewLength = Math.min(64, data.length);
            byte[] previewBytes = Arrays.copyOf(data, previewLength);
            activityLog.add("Storage API: byte preview requested for " + storeName + ":" + key
                    + " (" + data.length + " bytes total, showing first " + previewLength + ")");
            ctx.json(Map.of(
                    "key", key,
                    "sizeBytes", data.length,
                    "previewHex", HexFormat.ofDelimiter(" ").formatHex(previewBytes),
                    "truncated", data.length > previewLength));
        });

        app.get("/api/videos/{id}/manifest", ctx -> {
            VideoRecord video = metadata.get(ctx.pathParam("id"));
            if (video.status() != VideoStatus.READY) {
                throw new BadRequestResponse("Video is not READY (status=" + video.status() + ")");
            }
            activityLog.add("Manifest API: reading " + video.manifestKey() + " for " + video.id()
                    + " (" + video.readyRenditions().size() + " rendition(s) listed)");
            ctx.json(Map.of("manifest", new String(processedStore.get(video.manifestKey()))));
        });

        app.post("/api/videos/{id}/simulate-playback", ctx -> {
            VideoRecord video = metadata.get(ctx.pathParam("id"));
            if (video.status() != VideoStatus.READY) {
                throw new BadRequestResponse("Video is not READY (status=" + video.status() + ")");
            }
            NetworkSample[] trace = mapper.readValue(ctx.body(), NetworkSample[].class);
            activityLog.add("ABR API: simulating " + trace.length + " network sample(s) against "
                    + video.id() + "'s " + video.readyRenditions().size() + "-rendition ladder (no real network calls — this runs the same AdaptiveBitratePlayer class in-process)");
            AdaptiveBitratePlayer player = new AdaptiveBitratePlayer(video.readyRenditions());
            List<PlaybackDecision> decisions = new ArrayList<>();
            for (NetworkSample sample : trace) {
                ReadyRendition chosen = player.selectNextSegment(sample.throughputKbps(), sample.bufferHealthMs());
                decisions.add(new PlaybackDecision(sample.throughputKbps(), sample.bufferHealthMs(),
                        chosen.resolution(), chosen.bitrateKbps()));
            }
            activityLog.add("ABR API: decisions were " + decisions.stream().map(PlaybackDecision::resolution).toList());
            ctx.json(decisions);
        });

        int port = 8085;
        app.start(port);
        System.out.println("Video streaming console: http://localhost:" + port + "/");
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static ObjectSummary objectSummary(String key, byte[] data) {
        return new ObjectSummary(key, data.length);
    }

    private record ObjectSummary(String key, int sizeBytes) {
    }

    /** Bounded, append-only log of worker events, readable incrementally by ID so pollers never see duplicates. */
    private static final class ActivityLog {
        private final List<Entry> entries = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(1);

        record Entry(long id, String text) {
        }

        synchronized void add(String text) {
            entries.add(new Entry(nextId.getAndIncrement(), text));
            while (entries.size() > 500) {
                entries.remove(0);
            }
        }

        synchronized List<Entry> since(long afterId) {
            return entries.stream().filter(e -> e.id() > afterId).toList();
        }
    }

    private record UploadRequest(String title, double complexity, int durationSeconds,
                                  boolean simulateTotalFailure, String simulateResolutionFailure) {
    }

    private record NetworkSample(int throughputKbps, int bufferHealthMs) {
    }

    private record PlaybackDecision(int throughputKbps, int bufferHealthMs, String resolution, int bitrateKbps) {
    }
}
