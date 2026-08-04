package org.pk.practices.supplychain.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Writes to the {@code outbox} table (schema.sql) inside the caller's
 * transaction — the write half of the transactional outbox pattern. A
 * separate {@link org.pk.practices.supplychain.outbox.OutboxRelay} drains it
 * to Kafka afterward. LLD.md §1.3 Consistency Mechanisms.
 */
public class OutboxEventPublisher implements EventPublisher {

    private static final String INSERT_SQL = """
            INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?::jsonb)
            """;

    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Connection connection, DomainEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, event.aggregateType());
            statement.setString(2, event.aggregateId());
            statement.setString(3, event.eventType());
            statement.setString(4, toJson(event));
            statement.executeUpdate();
        }
    }

    private String toJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload for " + event.eventType(), e);
        }
    }
}
