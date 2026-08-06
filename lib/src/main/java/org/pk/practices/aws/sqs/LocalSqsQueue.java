package org.pk.practices.aws.sqs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A from-scratch, in-memory stand-in for an SQS standard queue: a message is
 * either {@code available} (waiting to be received) or {@code inFlight}
 * (received, invisible to other consumers until its visibility timeout
 * expires or it is deleted). No network, no AWS SDK — just the state
 * machine that makes SQS behave the way it does.
 *
 * <p>Deliberately out of scope: FIFO ordering/dedup, batching, and exactly
 * a real network boundary — see the README's "Going Further" section.
 */
public class LocalSqsQueue {

    private final Deque<InternalMessage> available = new ArrayDeque<>();
    private final Map<String, InFlightEntry> inFlight = new HashMap<>();
    private final Object lock = new Object();

    private final int maxReceiveCount;
    private final LocalSqsQueue deadLetterQueue;

    /**
     * @param maxReceiveCount number of times a message may be received (and
     *                        time out without being deleted) before it is
     *                        redirected to {@code deadLetterQueue} instead
     *                        of being made available again. Ignored if
     *                        {@code deadLetterQueue} is null.
     * @param deadLetterQueue queue to redirect exhausted messages to, or
     *                        null to disable dead-lettering (messages are
     *                        redelivered forever).
     */
    public LocalSqsQueue(int maxReceiveCount, LocalSqsQueue deadLetterQueue) {
        this.maxReceiveCount = maxReceiveCount;
        this.deadLetterQueue = deadLetterQueue;
    }

    public LocalSqsQueue() {
        this(Integer.MAX_VALUE, null);
    }

    /** Enqueues a new message. Always succeeds — there's no size limit here. */
    public String sendMessage(String body) {
        synchronized (lock) {
            InternalMessage message = new InternalMessage(UUID.randomUUID().toString(), body);
            available.addLast(message);
            return message.messageId;
        }
    }

    /**
     * Receives up to {@code maxMessages}, making each invisible to other
     * consumers for {@code visibilityTimeout}. Every call mints brand new
     * receipt handles — handles from a prior receive of the same message
     * (if it timed out and came back around) no longer work.
     */
    public List<Message> receiveMessages(int maxMessages, Duration visibilityTimeout) {
        synchronized (lock) {
            sweepExpired();
            List<Message> received = new ArrayList<>();
            for (int i = 0; i < maxMessages && !available.isEmpty(); i++) {
                InternalMessage message = available.pollFirst();
                message.receiveCount++;
                String receiptHandle = UUID.randomUUID().toString();
                inFlight.put(receiptHandle, new InFlightEntry(message, Instant.now().plus(visibilityTimeout)));
                received.add(new Message(message.messageId, receiptHandle, message.body, message.receiveCount));
            }
            return received;
        }
    }

    /**
     * Permanently removes the message behind {@code receiptHandle}. Returns
     * false if that handle is stale — already deleted, or invalidated by a
     * redelivery since it was issued — mirroring real SQS, which silently
     * ignores deletes with an expired receipt handle.
     */
    public boolean deleteMessage(String receiptHandle) {
        synchronized (lock) {
            sweepExpired();
            return inFlight.remove(receiptHandle) != null;
        }
    }

    /** Extends (or shortens) how long the message stays invisible. */
    public boolean changeMessageVisibility(String receiptHandle, Duration newTimeout) {
        synchronized (lock) {
            sweepExpired();
            InFlightEntry entry = inFlight.get(receiptHandle);
            if (entry == null) {
                return false;
            }
            entry.visibleAt = Instant.now().plus(newTimeout);
            return true;
        }
    }

    /** Number of messages currently waiting to be received. */
    public int approximateAvailableCount() {
        synchronized (lock) {
            sweepExpired();
            return available.size();
        }
    }

    /** Number of messages currently in flight (received, not yet deleted or expired). */
    public int approximateInFlightCount() {
        synchronized (lock) {
            sweepExpired();
            return inFlight.size();
        }
    }

    /**
     * Moves any in-flight message whose visibility timeout has elapsed back
     * to {@code available} — real redelivery, not a fresh message — unless
     * it has already hit {@code maxReceiveCount}, in which case it goes to
     * the dead-letter queue (keeping its original message ID) instead.
     *
     * <p>Real SQS doesn't run a background timer either: a timed-out message
     * simply becomes visible again the next time anything looks at the
     * queue. Calling this lazily on every public operation reproduces that
     * without a scheduler.
     */
    private void sweepExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, InFlightEntry>> it = inFlight.entrySet().iterator();
        while (it.hasNext()) {
            InFlightEntry entry = it.next().getValue();
            if (now.isBefore(entry.visibleAt)) {
                continue;
            }
            it.remove();
            InternalMessage message = entry.message;
            if (deadLetterQueue != null && message.receiveCount >= maxReceiveCount) {
                deadLetterQueue.enqueueDirectly(message);
            } else {
                available.addLast(message);
            }
        }
    }

    /** Re-enqueues an existing message as-is (same ID, same receive history) — used for DLQ redirection only. */
    private void enqueueDirectly(InternalMessage message) {
        synchronized (lock) {
            available.addLast(message);
        }
    }

    private static final class InternalMessage {
        final String messageId;
        final String body;
        int receiveCount;

        InternalMessage(String messageId, String body) {
            this.messageId = messageId;
            this.body = body;
            this.receiveCount = 0;
        }
    }

    private static final class InFlightEntry {
        final InternalMessage message;
        Instant visibleAt;

        InFlightEntry(InternalMessage message, Instant visibleAt) {
            this.message = message;
            this.visibleAt = visibleAt;
        }
    }
}
