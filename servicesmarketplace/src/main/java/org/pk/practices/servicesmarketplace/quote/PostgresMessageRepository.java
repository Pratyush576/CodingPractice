package org.pk.practices.servicesmarketplace.quote;

import org.pk.practices.servicesmarketplace.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class PostgresMessageRepository implements MessageRepository {

    private final Database database;

    public PostgresMessageRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Message message) {
        database.withTransaction(connection -> {
            String sql = "INSERT INTO messages (message_id, request_id, sender_id, sender_type, body, sent_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, message.messageId());
                statement.setString(2, message.requestId());
                statement.setString(3, message.senderId());
                statement.setString(4, message.senderType());
                statement.setString(5, message.body());
                statement.setTimestamp(6, Timestamp.from(message.sentAt()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Message> findByRequest(String requestId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM messages WHERE request_id = ? ORDER BY sent_at ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, requestId);
                List<Message> results = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
                return results;
            }
        });
    }

    private Message mapRow(ResultSet rs) throws SQLException {
        return new Message(
                rs.getString("message_id"),
                rs.getString("request_id"),
                rs.getString("sender_id"),
                rs.getString("sender_type"),
                rs.getString("body"),
                rs.getTimestamp("sent_at").toInstant()
        );
    }
}
