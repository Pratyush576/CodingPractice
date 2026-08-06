package org.pk.practices.aws.sqs;

import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A browser UI for {@link LocalSqsQueue}, shaped like the real AWS SQS
 * console: pick a queue, see its live counts, send a test message (the
 * producer side), poll for messages by hand, or start a real background
 * {@link QueueConsumer} that drains {@code orders-queue} on its own —
 * processing each message and deleting it, or leaving it in flight to
 * redeliver (and eventually dead-letter) if processing fails. There is no
 * AWS account or service behind any of this — it's the same
 * {@code LocalSqsQueue} {@link SqsLocalDemo} drives from a scripted CLI
 * walkthrough, just given a UI and a real consumer thread to click through
 * by hand instead.
 */
public class SqsConsoleServer {

    public static void main(String[] args) {
        LocalSqsQueue deadLetterQueue = new LocalSqsQueue();
        LocalSqsQueue mainQueue = new LocalSqsQueue(3, deadLetterQueue);

        Map<String, LocalSqsQueue> queues = new LinkedHashMap<>();
        queues.put("orders-queue", mainQueue);
        queues.put("orders-queue-dlq", deadLetterQueue);

        ActivityLog activityLog = new ActivityLog();

        // The "processing": a message containing "fail" (any case) always
        // errors out, simulating a poison message — send one from the
        // console and watch it get retried, then dead-lettered, entirely
        // through this background consumer.
        QueueConsumer.MessageHandler handler = message -> {
            Thread.sleep(300);
            if (message.body().toLowerCase().contains("fail")) {
                throw new RuntimeException("simulated processing failure");
            }
        };

        QueueConsumer consumer = new QueueConsumer(
                mainQueue, handler, Duration.ofSeconds(10), Duration.ofMillis(400), 5, activityLog::add);

        Map<String, QueueConsumer> consumers = Map.of("orders-queue", consumer);

        Javalin app = Javalin.create(config -> config.staticFiles.add("/sqs-console", Location.CLASSPATH));

        app.get("/api/queues", ctx -> ctx.json(queueSummaries(queues, consumers)));

        app.get("/api/consumer-events", ctx -> {
            long since = ctx.queryParamAsClass("since", Long.class).getOrDefault(0L);
            ctx.json(activityLog.since(since));
        });

        app.post("/api/queues/{name}/send", ctx -> {
            LocalSqsQueue queue = requireQueue(queues, ctx.pathParam("name"));
            SendRequest req = ctx.bodyAsClass(SendRequest.class);
            String messageId = queue.sendMessage(req.body());
            ctx.json(Map.of("messageId", messageId));
        });

        app.post("/api/queues/{name}/receive", ctx -> {
            LocalSqsQueue queue = requireQueue(queues, ctx.pathParam("name"));
            ReceiveRequest req = ctx.bodyAsClass(ReceiveRequest.class);
            List<Message> messages = queue.receiveMessages(
                    req.maxMessages(), Duration.ofSeconds(req.visibilityTimeoutSeconds()));
            ctx.json(messages);
        });

        app.post("/api/queues/{name}/delete", ctx -> {
            LocalSqsQueue queue = requireQueue(queues, ctx.pathParam("name"));
            ReceiptHandleRequest req = ctx.bodyAsClass(ReceiptHandleRequest.class);
            boolean deleted = queue.deleteMessage(req.receiptHandle());
            ctx.json(Map.of("deleted", deleted));
        });

        app.post("/api/queues/{name}/change-visibility", ctx -> {
            LocalSqsQueue queue = requireQueue(queues, ctx.pathParam("name"));
            ChangeVisibilityRequest req = ctx.bodyAsClass(ChangeVisibilityRequest.class);
            boolean changed = queue.changeMessageVisibility(
                    req.receiptHandle(), Duration.ofSeconds(req.timeoutSeconds()));
            ctx.json(Map.of("changed", changed));
        });

        app.post("/api/queues/{name}/consumer/start", ctx -> {
            QueueConsumer c = requireConsumer(consumers, ctx.pathParam("name"));
            c.start();
            ctx.json(Map.of("running", c.isRunning()));
        });

        app.post("/api/queues/{name}/consumer/stop", ctx -> {
            QueueConsumer c = requireConsumer(consumers, ctx.pathParam("name"));
            c.stop();
            ctx.json(Map.of("running", c.isRunning()));
        });

        int port = 8084;
        app.start(port);
        System.out.println("SQS console: http://localhost:" + port + "/");
    }

    private static LocalSqsQueue requireQueue(Map<String, LocalSqsQueue> queues, String name) {
        LocalSqsQueue queue = queues.get(name);
        if (queue == null) {
            throw new NotFoundResponse("No such queue: " + name);
        }
        return queue;
    }

    private static QueueConsumer requireConsumer(Map<String, QueueConsumer> consumers, String name) {
        QueueConsumer consumer = consumers.get(name);
        if (consumer == null) {
            throw new NotFoundResponse("No consumer configured for queue: " + name);
        }
        return consumer;
    }

    private static List<QueueSummary> queueSummaries(Map<String, LocalSqsQueue> queues, Map<String, QueueConsumer> consumers) {
        return queues.entrySet().stream()
                .map(e -> {
                    QueueConsumer consumer = consumers.get(e.getKey());
                    return new QueueSummary(
                            e.getKey(),
                            e.getValue().approximateAvailableCount(),
                            e.getValue().approximateInFlightCount(),
                            consumer != null,
                            consumer != null && consumer.isRunning());
                })
                .toList();
    }

    /** Bounded, append-only log of consumer events, readable incrementally by ID so pollers never see duplicates. */
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

    private record QueueSummary(String name, int available, int inFlight, boolean hasConsumer, boolean consumerRunning) {
    }

    private record SendRequest(String body) {
    }

    private record ReceiveRequest(int maxMessages, long visibilityTimeoutSeconds) {
    }

    private record ReceiptHandleRequest(String receiptHandle) {
    }

    private record ChangeVisibilityRequest(String receiptHandle, long timeoutSeconds) {
    }
}
