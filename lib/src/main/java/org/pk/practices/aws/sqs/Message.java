package org.pk.practices.aws.sqs;

/**
 * A message as handed to a consumer by {@link LocalSqsQueue#receiveMessages}.
 * The {@code messageId} is stable for the message's whole lifetime; the
 * {@code receiptHandle} is a fresh, single-use token minted on every
 * receive — it is only valid for {@code deleteMessage}/
 * {@code changeMessageVisibility} until the next redelivery invalidates it.
 */
public record Message(String messageId, String receiptHandle, String body, int receiveCount) {
}
