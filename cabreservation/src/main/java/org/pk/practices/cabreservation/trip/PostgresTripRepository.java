package org.pk.practices.cabreservation.trip;

import org.pk.practices.cabreservation.common.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresTripRepository implements TripRepository {

    private final Database database;

    public PostgresTripRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Trip trip) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO trips (trip_id, rider_id, driver_id, status, pickup_lat, pickup_lng,
                        dropoff_lat, dropoff_lng, offered_driver_id, offer_expires_at, fare_estimate, fare_final,
                        version, created_at, matched_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, trip.tripId());
                statement.setString(i++, trip.riderId());
                statement.setString(i++, trip.driverId());
                statement.setString(i++, trip.status().name());
                statement.setDouble(i++, trip.pickupLat());
                statement.setDouble(i++, trip.pickupLng());
                statement.setDouble(i++, trip.dropoffLat());
                statement.setDouble(i++, trip.dropoffLng());
                statement.setString(i++, trip.offeredDriverId());
                setNullableTimestamp(statement, i++, trip.offerExpiresAt());
                setNullableDouble(statement, i++, trip.fareEstimate());
                setNullableDouble(statement, i++, trip.fareFinal());
                statement.setLong(i++, trip.version());
                statement.setTimestamp(i++, Timestamp.from(trip.createdAt()));
                setNullableTimestamp(statement, i++, trip.matchedAt());
                setNullableTimestamp(statement, i++, trip.completedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Trip> find(String tripId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM trips WHERE trip_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tripId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Trip>empty();
                }
            }
        });
    }

    @Override
    public boolean compareAndSetStatus(Trip previous, TripStatus newStatus) {
        return database.withTransaction(connection -> {
            String sql = "UPDATE trips SET status = ?, version = version + 1 WHERE trip_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, newStatus.name());
                statement.setString(2, previous.tripId());
                statement.setLong(3, previous.version());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean recordOffer(Trip previous, String driverId, Instant expiresAt) {
        return database.withTransaction(connection -> {
            String sql = """
                    UPDATE trips SET offered_driver_id = ?, offer_expires_at = ?, version = version + 1
                    WHERE trip_id = ? AND version = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, driverId);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                statement.setString(3, previous.tripId());
                statement.setLong(4, previous.version());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean recordMatched(Trip previous, String driverId) {
        return database.withTransaction(connection -> {
            String sql = """
                    UPDATE trips SET status = ?, driver_id = ?, matched_at = ?, version = version + 1
                    WHERE trip_id = ? AND version = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, TripStatus.MATCHED.name());
                statement.setString(2, driverId);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.setString(4, previous.tripId());
                statement.setLong(5, previous.version());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean recordCompleted(Trip previous) {
        return database.withTransaction(connection -> {
            String sql = "UPDATE trips SET status = ?, completed_at = ?, version = version + 1 WHERE trip_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, TripStatus.COMPLETED.name());
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                statement.setString(3, previous.tripId());
                statement.setLong(4, previous.version());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<Trip> findExpiredOffers(Instant now) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM trips WHERE status = 'MATCHING' AND offer_expires_at IS NOT NULL AND offer_expires_at < ?";
            List<Trip> trips = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        trips.add(mapRow(rs));
                    }
                }
            }
            return trips;
        });
    }

    @Override
    public Optional<Trip> findActiveForDriver(String driverId) {
        return database.withTransaction(connection -> {
            String sql = """
                    SELECT * FROM trips
                    WHERE (offered_driver_id = ? OR driver_id = ?)
                      AND status NOT IN ('COMPLETED', 'CANCELLED_BY_RIDER', 'CANCELLED_BY_DRIVER', 'NO_DRIVERS_FOUND')
                    ORDER BY created_at DESC LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, driverId);
                statement.setString(2, driverId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Trip>empty();
                }
            }
        });
    }

    @Override
    public List<Trip> findByRiderId(String riderId) {
        return findAllBy(connection -> {
            String sql = "SELECT * FROM trips WHERE rider_id = ? ORDER BY created_at DESC";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, riderId);
            return statement;
        });
    }

    @Override
    public List<Trip> findByDriverId(String driverId) {
        return findAllBy(connection -> {
            String sql = "SELECT * FROM trips WHERE driver_id = ? ORDER BY created_at DESC";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, driverId);
            return statement;
        });
    }

    private interface StatementBuilder {
        PreparedStatement build(Connection connection) throws SQLException;
    }

    private List<Trip> findAllBy(StatementBuilder builder) {
        return database.withTransaction(connection -> {
            List<Trip> trips = new ArrayList<>();
            try (PreparedStatement statement = builder.build(connection);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    trips.add(mapRow(rs));
                }
            }
            return trips;
        });
    }

    private Trip mapRow(ResultSet rs) throws SQLException {
        return new Trip(
                rs.getString("trip_id"),
                rs.getString("rider_id"),
                rs.getString("driver_id"),
                TripStatus.valueOf(rs.getString("status")),
                rs.getDouble("pickup_lat"),
                rs.getDouble("pickup_lng"),
                rs.getDouble("dropoff_lat"),
                rs.getDouble("dropoff_lng"),
                rs.getString("offered_driver_id"),
                toInstant(rs.getTimestamp("offer_expires_at")),
                nullableDouble(rs, "fare_estimate"),
                nullableDouble(rs, "fare_final"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("matched_at")),
                toInstant(rs.getTimestamp("completed_at"))
        );
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
