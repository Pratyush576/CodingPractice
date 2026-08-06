package org.pk.practices.aws.sqs;

import java.time.Duration;
import java.util.List;

/**
 * A background poll-process-delete loop against a {@link LocalSqsQueue}:
 * repeatedly receives a batch, hands each message to a {@link MessageHandler},
 * and deletes it only if the handler returns normally. A handler that
 * throws leaves the message in flight to expire on its own — the same
 * "processing failed, let it come back around" pattern a real SQS consumer
 * relies on, driven by actual business logic instead of an artificial
 * timeout.
 */
public class QueueConsumer {

    @FunctionalInterface
    public interface MessageHandler {
        void handle(Message message) throws Exception;
    }

    @FunctionalInterface
    public interface ActivityListener {
        void onEvent(String event);
    }

    private final LocalSqsQueue queue;
    private final MessageHandler handler;
    private final Duration visibilityTimeout;
    private final Duration pollInterval;
    private final int maxMessagesPerPoll;
    private final ActivityListener activityListener;

    private Thread workerThread;
    private volatile boolean running;

    public QueueConsumer(LocalSqsQueue queue, MessageHandler handler, Duration visibilityTimeout,
                          Duration pollInterval, int maxMessagesPerPoll, ActivityListener activityListener) {
        this.queue = queue;
        this.handler = handler;
        this.visibilityTimeout = visibilityTimeout;
        this.pollInterval = pollInterval;
        this.maxMessagesPerPoll = maxMessagesPerPoll;
        this.activityListener = activityListener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::runLoop, "queue-consumer");
        workerThread.setDaemon(true);
        workerThread.start();
        notifyListener("Consumer started");
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        workerThread.interrupt();
        notifyListener("Consumer stop requested");
    }

    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            List<Message> batch = queue.receiveMessages(maxMessagesPerPoll, visibilityTimeout);
            if (batch.isEmpty()) {
                sleepQuietly(pollInterval);
                continue;
            }
            for (Message message : batch) {
                processOne(message);
            }
        }
        notifyListener("Consumer stopped");
    }

    private void processOne(Message message) {
        try {
            handler.handle(message);
            queue.deleteMessage(message.receiptHandle());
            notifyListener("Processed and deleted " + shortId(message.messageId())
                    + ": \"" + message.body() + "\"");
        } catch (Exception e) {
            notifyListener("Processing FAILED for " + shortId(message.messageId())
                    + " (attempt " + message.receiveCount() + "): " + e.getMessage()
                    + " — left in flight to redeliver");
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    private void notifyListener(String event) {
        if (activityListener != null) {
            activityListener.onEvent(event);
        }
    }
}
