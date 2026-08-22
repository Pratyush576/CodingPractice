package org.pk.practices.servicesmarketplace.request;

import org.pk.practices.servicesmarketplace.common.Database;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresRequestRepository implements RequestRepository {

    private final Database database;

    public PostgresRequestRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Request request) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO requests (request_id, customer_id, category_id, answers, location_lat, location_lng,
                        desired_timing, status, hired_quote_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, request.requestId());
                statement.setString(i++, request.customerId());
                statement.setString(i++, request.categoryId());
                statement.setObject(i++, jsonb(request.answers()));
                statement.setDouble(i++, request.locationLat());
                statement.setDouble(i++, request.locationLng());
                statement.setString(i++, request.desiredTiming());
                statement.setString(i++, request.status().name());
                statement.setString(i++, request.hiredQuoteId());
                statement.setTimestamp(i++, Timestamp.from(request.createdAt()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Request> find(String requestId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM requests WHERE request_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, requestId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Request> findByCustomer(String customerId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM requests WHERE customer_id = ? ORDER BY created_at DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, customerId);
                List<Request> results = new ArrayList<>();
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
    public boolean hire(String requestId, String quoteId) {
        return database.withTransaction(connection -> {
            String sql = "UPDATE requests SET status = 'HIRED', hired_quote_id = ? WHERE request_id = ? AND status = 'OPEN'";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, quoteId);
                statement.setString(2, requestId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private Request mapRow(ResultSet rs) throws SQLException {
        return new Request(
                rs.getString("request_id"),
                rs.getString("customer_id"),
                rs.getString("category_id"),
                rs.getString("answers"),
                rs.getDouble("location_lat"),
                rs.getDouble("location_lng"),
                rs.getString("desired_timing"),
                RequestStatus.valueOf(rs.getString("status")),
                rs.getString("hired_quote_id"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static PGobject jsonb(String json) throws SQLException {
        PGobject object = new PGobject();
        object.setType("jsonb");
        object.setValue(json);
        return object;
    }
}
