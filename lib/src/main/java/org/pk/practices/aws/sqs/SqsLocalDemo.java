package org.pk.practices.aws.sqs;

import java.time.Duration;
import java.util.List;

/**
 * Walks a message through the full SQS lifecycle against {@link LocalSqsQueue}:
 * happy-path send/receive/delete, a "consumer crashed" scenario showing
 * redelivery with an invalidated old receipt handle, and repeated failures
 * ending in dead-letter-queue redirection. Uses a short, real visibility
 * timeout and real {@code Thread.sleep()} so the timing is genuine, not
 * simulated.
 */
public class SqsLocalDemo {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_RECEIVE_COUNT = 3;

    public static void main(String[] args) throws InterruptedException {
        LocalSqsQueue deadLetterQueue = new LocalSqsQueue();
        LocalSqsQueue queue = new LocalSqsQueue(MAX_RECEIVE_COUNT, deadLetterQueue);

        System.out.println("=== 1. Happy path: send, receive, delete ===");
        String id1 = queue.sendMessage("order-1: pack 3 crates");
        queue.sendMessage("order-2: pack 1 pallet");
        System.out.println("Sent order-1 (" + id1 + ") and order-2");
        printCounts(queue);

        List<Message> firstBatch = queue.receiveMessages(1, VISIBILITY_TIMEOUT);
        Message happyPath = firstBatch.get(0);
        System.out.println("Received: " + happyPath);
        boolean deleted = queue.deleteMessage(happyPath.receiptHandle());
        System.out.println("Deleted with its receipt handle -> " + deleted);
        printCounts(queue);

        System.out.println();
        System.out.println("=== 2. Consumer crash: received but never deleted ===");
        List<Message> secondBatch = queue.receiveMessages(1, VISIBILITY_TIMEOUT);
        Message crashed = secondBatch.get(0);
        System.out.println("Received: " + crashed + " (consumer now 'crashes' and never deletes it)");
        printCounts(queue);

        System.out.println("Sleeping past the " + VISIBILITY_TIMEOUT.getSeconds() + "s visibility timeout...");
        Thread.sleep(VISIBILITY_TIMEOUT.plusMillis(200).toMillis());

        boolean staleDeleteWorked = queue.deleteMessage(crashed.receiptHandle());
        System.out.println("Delete with the OLD (now-stale) receipt handle -> " + staleDeleteWorked
                + " (expired the moment it timed out — this must be false)");

        List<Message> redelivered = queue.receiveMessages(1, VISIBILITY_TIMEOUT);
        Message redeliveredMsg = redelivered.get(0);
        System.out.println("Redelivered: " + redeliveredMsg
                + " — same messageId, receiveCount now " + redeliveredMsg.receiveCount()
                + ", brand new receiptHandle");
        printCounts(queue);

        System.out.println();
        System.out.println("=== 3. Repeated failures -> dead-letter queue ===");
        System.out.println("(already timed out twice above; letting it time out until maxReceiveCount=" + MAX_RECEIVE_COUNT + " is reached)");
        Message current = redeliveredMsg;
        while (current.receiveCount() < MAX_RECEIVE_COUNT) {
            Thread.sleep(VISIBILITY_TIMEOUT.plusMillis(200).toMillis());
            List<Message> retry = queue.receiveMessages(1, VISIBILITY_TIMEOUT);
            if (retry.isEmpty()) {
                break;
            }
            current = retry.get(0);
            System.out.println("Receive attempt " + current.receiveCount() + ": " + current + " (still never deleted)");
        }

        System.out.println("Sleeping past the visibility timeout one last time to trigger the DLQ redirect...");
        Thread.sleep(VISIBILITY_TIMEOUT.plusMillis(200).toMillis());
        System.out.println("Main queue available/in-flight: "
                + queue.approximateAvailableCount() + "/" + queue.approximateInFlightCount());
        System.out.println("Dead-letter queue available: " + deadLetterQueue.approximateAvailableCount());

        List<Message> fromDlq = deadLetterQueue.receiveMessages(10, VISIBILITY_TIMEOUT);
        for (Message m : fromDlq) {
            System.out.println("In DLQ: " + m + " (original messageId preserved: "
                    + m.messageId().equals(current.messageId()) + ")");
        }
    }

    private static void printCounts(LocalSqsQueue queue) {
        System.out.println("  available=" + queue.approximateAvailableCount()
                + " inFlight=" + queue.approximateInFlightCount());
    }
}
