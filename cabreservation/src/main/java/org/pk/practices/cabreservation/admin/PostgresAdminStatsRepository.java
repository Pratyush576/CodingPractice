package org.pk.practices.cabreservation.admin;

import org.pk.practices.cabreservation.common.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class PostgresAdminStatsRepository implements AdminStatsRepository {

    private final Database database;

    public PostgresAdminStatsRepository(Database database) {
        this.database = database;
    }

    @Override
    public AdminStats compute(Instant since) {
        return database.withTransaction(connection -> {
            Map<String, Long> tripStatusCounts = tripStatusCounts(connection, since);
            long matched = countMatched(connection, since);
            double totalFareValue = sumFareValue(connection, since);
            double[] paymentTotals = sumAndCount(connection, since,
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payments WHERE created_at >= ? AND status = 'CHARGED'");
            double[] payoutTotals = sumAndCount(connection, since,
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payouts WHERE created_at >= ? AND status = 'PAID'");
            double[] declinedPayments = sumAndCount(connection, since,
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payments WHERE created_at >= ? AND status = 'DECLINED'");
            double[] failedPayouts = sumAndCount(connection, since,
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payouts WHERE created_at >= ? AND status = 'FAILED'");

            long requested = tripStatusCounts.values().stream().mapToLong(Long::longValue).sum();
            return new AdminStats(
                    requested,
                    matched,
                    tripStatusCounts.getOrDefault("COMPLETED", 0L),
                    tripStatusCounts.getOrDefault("CANCELLED_BY_RIDER", 0L),
                    tripStatusCounts.getOrDefault("CANCELLED_BY_DRIVER", 0L),
                    tripStatusCounts.getOrDefault("NO_DRIVERS_FOUND", 0L),
                    paymentTotals[1],
                    payoutTotals[1],
                    (long) paymentTotals[0],
                    (long) payoutTotals[0],
                    totalFareValue,
                    declinedPayments[1],
                    (long) declinedPayments[0],
                    failedPayouts[1],
                    (long) failedPayouts[0]
            );
        });
    }

    /** Gross fare across every COMPLETED trip, regardless of whether the charge actually succeeded — the "should have collected" figure totalRevenue is compared against. */
    private double sumFareValue(Connection connection, Instant since) throws SQLException {
        String sql = "SELECT COALESCE(SUM(fare_final), 0) AS total FROM trips WHERE created_at >= ? AND status = 'COMPLETED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getDouble("total");
            }
        }
    }

    private Map<String, Long> tripStatusCounts(Connection connection, Instant since) throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        String sql = "SELECT status, COUNT(*) AS cnt FROM trips WHERE created_at >= ? GROUP BY status";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("status"), rs.getLong("cnt"));
                }
            }
        }
        return counts;
    }

    /** "Ever reached MATCHED" (matched_at IS NOT NULL), not "currently MATCHED" — a later cancellation or completion still counts as having been matched. */
    private long countMatched(Connection connection, Instant since) throws SQLException {
        String sql = "SELECT COUNT(*) AS cnt FROM trips WHERE created_at >= ? AND matched_at IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong("cnt");
            }
        }
    }

    private double[] sumAndCount(Connection connection, Instant since, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new double[] { rs.getLong("cnt"), rs.getDouble("total") };
            }
        }
    }
}
