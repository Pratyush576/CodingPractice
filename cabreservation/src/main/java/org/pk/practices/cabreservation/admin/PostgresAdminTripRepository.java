package org.pk.practices.cabreservation.admin;

import org.pk.practices.cabreservation.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PostgresAdminTripRepository implements AdminTripRepository {

    private final Database database;

    public PostgresAdminTripRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<AdminTripView> list(Instant since) {
        return database.withTransaction(connection -> {
            String sql = """
                    SELECT t.trip_id, t.status, t.created_at, t.fare_estimate, t.fare_final,
                           r.name AS rider_name, d.name AS driver_name,
                           p.status AS payment_status, p.amount AS payment_amount,
                           po.status AS payout_status, po.amount AS payout_amount
                    FROM trips t
                    LEFT JOIN riders r ON r.rider_id = t.rider_id
                    LEFT JOIN drivers d ON d.driver_id = t.driver_id
                    LEFT JOIN payments p ON p.trip_id = t.trip_id
                    LEFT JOIN payouts po ON po.trip_id = t.trip_id
                    WHERE t.created_at >= ?
                    ORDER BY t.created_at DESC
                    """;
            List<AdminTripView> views = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(since));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        views.add(new AdminTripView(
                                rs.getString("trip_id"),
                                rs.getString("status"),
                                rs.getString("rider_name"),
                                rs.getString("driver_name"),
                                rs.getTimestamp("created_at").toInstant(),
                                nullableDouble(rs, "fare_estimate"),
                                nullableDouble(rs, "fare_final"),
                                rs.getString("payment_status"),
                                nullableDouble(rs, "payment_amount"),
                                rs.getString("payout_status"),
                                nullableDouble(rs, "payout_amount")
                        ));
                    }
                }
            }
            return views;
        });
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
