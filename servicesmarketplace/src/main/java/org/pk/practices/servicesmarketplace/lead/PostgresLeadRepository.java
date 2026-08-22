package org.pk.practices.servicesmarketplace.lead;

import org.pk.practices.servicesmarketplace.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresLeadRepository implements LeadRepository {

    private final Database database;

    public PostgresLeadRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Lead lead) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO leads (lead_id, request_id, pro_id, status, credit_cost, created_at, unlocked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, lead.leadId());
                statement.setString(i++, lead.requestId());
                statement.setString(i++, lead.proId());
                statement.setString(i++, lead.status().name());
                statement.setDouble(i++, lead.creditCost());
                statement.setTimestamp(i++, Timestamp.from(lead.createdAt()));
                if (lead.unlockedAt() == null) {
                    statement.setNull(i++, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                } else {
                    statement.setTimestamp(i++, Timestamp.from(lead.unlockedAt()));
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Lead> find(String leadId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM leads WHERE lead_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, leadId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Lead> findByRequest(String requestId) {
        return findAllBy("request_id", requestId, "created_at ASC");
    }

    @Override
    public List<Lead> findByPro(String proId) {
        return findAllBy("pro_id", proId, "created_at DESC");
    }

    private List<Lead> findAllBy(String column, String value, String orderBy) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM leads WHERE " + column + " = ? ORDER BY " + orderBy;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                List<Lead> results = new ArrayList<>();
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
    public void updateStatus(String leadId, LeadStatus status) {
        database.withTransaction(connection -> {
            String sql = "UPDATE leads SET status = ? WHERE lead_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status.name());
                statement.setString(2, leadId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Lead mapRow(ResultSet rs) throws SQLException {
        Timestamp unlockedAt = rs.getTimestamp("unlocked_at");
        return new Lead(
                rs.getString("lead_id"),
                rs.getString("request_id"),
                rs.getString("pro_id"),
                LeadStatus.valueOf(rs.getString("status")),
                rs.getDouble("credit_cost"),
                rs.getTimestamp("created_at").toInstant(),
                unlockedAt == null ? null : unlockedAt.toInstant()
        );
    }
}
