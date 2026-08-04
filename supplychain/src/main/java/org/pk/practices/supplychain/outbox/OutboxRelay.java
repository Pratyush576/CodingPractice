package org.pk.practices.supplychain.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.pk.practices.supplychain.common.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The relay half of the transactional outbox pattern (LLD.md §1.3): drains
 * rows with {@code published_at IS NULL}, publishes each to its mapped Kafka
 * topic ({@code §1.2 Event Topics}), and marks it published — all inside one
 * DB transaction per batch, so a mid-batch Kafka failure rolls the whole
 * batch's {@code published_at} update back rather than losing track of what
 * was actually sent. A row resent on the next poll after a rollback is
 * exactly the "at-least-once, idempotent consumer" contract every consumer
 * in this system already has to honor.
 */
public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            "BookingSubmitted", "booking-events",
            "BookingConfirmed", "booking-events"
    );

    private final Database database;
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-relay");
        t.setDaemon(true);
        return t;
    });

    public OutboxRelay(Database database, KafkaProducer<String, String> producer, ObjectMapper objectMapper) {
        this.database = database;
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    public void start(long pollIntervalMillis) {
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("Outbox relay started, polling every {}ms", pollIntervalMillis);
    }

    /** Package-visible for tests — runs exactly one poll-and-publish batch. */
    void pollOnce() {
        try {
            int published = database.withTransaction(this::publishUnpublishedBatch);
            if (published > 0) {
                log.debug("Outbox relay published {} event(s)", published);
            }
        } catch (Exception e) {
            log.error("Outbox relay poll failed — will retry next interval", e);
        }
    }

    private int publishUnpublishedBatch(Connection connection) throws Exception {
        String selectSql = """
                SELECT id, aggregate_type, aggregate_id, event_type, payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        int count = 0;
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setInt(1, BATCH_SIZE);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String aggregateType = rs.getString("aggregate_type");
                    String aggregateId = rs.getString("aggregate_id");
                    String eventType = rs.getString("event_type");
                    String payloadJson = rs.getString("payload");

                    publishToKafka(id, aggregateType, aggregateId, eventType, payloadJson);
                    markPublished(connection, id);
                    count++;
                }
            }
        }
        return count;
    }

    private void publishToKafka(long outboxId, String aggregateType, String aggregateId, String eventType, String payloadJson) throws Exception {
        String topic = TOPIC_BY_EVENT_TYPE.get(eventType);
        if (topic == null) {
            log.warn("No topic mapping for event type {} (outbox id {}) — dropping, not retrying", eventType, outboxId);
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.set("payload", objectMapper.readTree(payloadJson));

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, aggregateId, objectMapper.writeValueAsString(envelope));
        // Synchronous send: this row's published_at only flips once Kafka has actually
        // acknowledged it, keeping the "both happen or neither does" outbox guarantee end to end.
        producer.send(record).get();
    }

    private void markPublished(Connection connection, long id) throws Exception {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            update.setTimestamp(1, Timestamp.from(Instant.now()));
            update.setLong(2, id);
            update.executeUpdate();
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
