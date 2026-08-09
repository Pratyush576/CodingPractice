package org.pk.practices.cabreservation.payment;

import org.pk.practices.cabreservation.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresPayoutRepository implements PayoutRepository {

    private final Database database;

    public PostgresPayoutRepository(Database database) {
        this.database = database;
    }

    @Override
    public boolean insert(Payout payout) {
        return database.withTransaction(connection -> {
            String sql = "INSERT INTO payouts (payout_id, trip_id, driver_id, amount, status, provider_reference, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, payout.payoutId());
                statement.setString(2, payout.tripId());
                statement.setString(3, payout.driverId());
                statement.setDouble(4, payout.amount());
                statement.setString(5, payout.status().name());
                statement.setString(6, payout.providerReference());
                statement.setTimestamp(7, Timestamp.from(payout.createdAt()));
                try {
                    statement.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        return false; // payouts.trip_id UNIQUE — already paid out for this trip
                    }
                    throw e;
                }
            }
        });
    }

    @Override
    public Optional<Payout> findByTripId(String tripId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM payouts WHERE trip_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tripId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Payout> findByDriverId(String driverId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM payouts WHERE driver_id = ? ORDER BY created_at DESC";
            List<Payout> payouts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, driverId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        payouts.add(mapRow(rs));
                    }
                }
            }
            return payouts;
        });
    }

    private Payout mapRow(ResultSet rs) throws SQLException {
        return new Payout(
                rs.getString("payout_id"),
                rs.getString("trip_id"),
                rs.getString("driver_id"),
                rs.getDouble("amount"),
                PayoutStatus.valueOf(rs.getString("status")),
                rs.getString("provider_reference"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
