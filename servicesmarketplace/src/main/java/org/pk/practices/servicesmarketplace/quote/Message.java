package org.pk.practices.servicesmarketplace.quote;

import java.time.Instant;

/** DESIGN.md §3 Domain Model — threaded per Request, visible to that Request's customer and the specific Pro on each Lead. */
public record Message(String messageId, String requestId, String senderId, String senderType, String body, Instant sentAt) {}
