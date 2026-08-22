package org.pk.practices.servicesmarketplace.quote;

import org.pk.practices.servicesmarketplace.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresQuoteRepository implements QuoteRepository {

    private final Database database;

    public PostgresQuoteRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Quote quote) {
        database.withTransaction(connection -> {
            String sql = "INSERT INTO quotes (quote_id, lead_id, price, message, status, sent_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, quote.quoteId());
                statement.setString(2, quote.leadId());
                statement.setDouble(3, quote.price());
                statement.setString(4, quote.message());
                statement.setString(5, quote.status().name());
                statement.setTimestamp(6, Timestamp.from(quote.sentAt()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Quote> find(String quoteId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM quotes WHERE quote_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, quoteId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<Quote> findByLead(String leadId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM quotes WHERE lead_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, leadId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Quote> findByRequest(String requestId) {
        return database.withTransaction(connection -> {
            String sql = """
                    SELECT q.* FROM quotes q JOIN leads l ON l.lead_id = q.lead_id
                    WHERE l.request_id = ? ORDER BY q.sent_at ASC
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, requestId);
                List<Quote> results = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
                return results;
            }
        });
    }

    @Override
    public void updateStatus(String quoteId, QuoteStatus status) {
        database.withTransaction(connection -> {
            String sql = "UPDATE quotes SET status = ? WHERE quote_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status.name());
                statement.setString(2, quoteId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void declineOthersOnSameRequest(String winningQuoteId, String requestId) {
        database.withTransaction(connection -> {
            String sql = """
                    UPDATE quotes SET status = 'DECLINED'
                    WHERE quote_id != ? AND status = 'PENDING'
                      AND lead_id IN (SELECT lead_id FROM leads WHERE request_id = ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, winningQuoteId);
                statement.setString(2, requestId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Quote mapRow(ResultSet rs) throws SQLException {
        return new Quote(
                rs.getString("quote_id"),
                rs.getString("lead_id"),
                rs.getDouble("price"),
                rs.getString("message"),
                QuoteStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("sent_at").toInstant()
        );
    }
}
