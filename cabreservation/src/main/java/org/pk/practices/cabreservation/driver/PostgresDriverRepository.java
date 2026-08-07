package org.pk.practices.cabreservation.driver;

import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

public class PostgresDriverRepository implements DriverRepository {

    private final Database database;

    public PostgresDriverRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Driver driver, Vehicle vehicle) {
        database.withTransaction(connection -> {
            String driverSql = """
                    INSERT INTO drivers (driver_id, name, email, password_hash, vehicle_id, status, rating,
                        last_lat, last_lng, last_ping_at, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(driverSql)) {
                int i = 1;
                statement.setString(i++, driver.driverId());
                statement.setString(i++, driver.name());
                statement.setString(i++, driver.email());
                statement.setString(i++, driver.passwordHash());
                statement.setString(i++, driver.vehicleId());
                statement.setString(i++, driver.status().name());
                setNullableDouble(statement, i++, driver.rating());
                setNullableDouble(statement, i++, driver.lastLat());
                setNullableDouble(statement, i++, driver.lastLng());
                setNullableTimestamp(statement, i++, driver.lastPingAt());
                statement.setLong(i++, driver.version());
                statement.setTimestamp(i++, Timestamp.from(driver.createdAt()));
                statement.setTimestamp(i++, Timestamp.from(driver.updatedAt()));
                try {
                    statement.executeUpdate();
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        throw new ConflictException("A driver with this email already exists");
                    }
                    throw e;
                }
            }
            String vehicleSql = "INSERT INTO vehicles (vehicle_id, driver_id, plate, make, model, product_type) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(vehicleSql)) {
                statement.setString(1, vehicle.vehicleId());
                statement.setString(2, vehicle.driverId());
                statement.setString(3, vehicle.plate());
                statement.setString(4, vehicle.make());
                statement.setString(5, vehicle.model());
                statement.setString(6, vehicle.productType());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Driver> findById(String driverId) {
        return database.withTransaction(connection -> findBy(connection, "driver_id", driverId));
    }

    @Override
    public Optional<Driver> findByEmail(String email) {
        return database.withTransaction(connection -> findBy(connection, "email", email));
    }

    @Override
    public Optional<Vehicle> findVehicleByDriverId(String driverId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM vehicles WHERE driver_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, driverId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Vehicle(
                            rs.getString("vehicle_id"),
                            rs.getString("driver_id"),
                            rs.getString("plate"),
                            rs.getString("make"),
                            rs.getString("model"),
                            rs.getString("product_type")
                    ));
                }
            }
        });
    }

    @Override
    public boolean compareAndSetStatus(String driverId, DriverStatus expectedStatus, long expectedVersion, DriverStatus newStatus) {
        return database.withTransaction(connection -> {
            String sql = """
                    UPDATE drivers SET status = ?, version = version + 1, updated_at = ?
                    WHERE driver_id = ? AND status = ? AND version = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, newStatus.name());
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                statement.setString(3, driverId);
                statement.setString(4, expectedStatus.name());
                statement.setLong(5, expectedVersion);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void updateLastPing(String driverId, double lat, double lng) {
        database.withTransaction(connection -> {
            String sql = "UPDATE drivers SET last_lat = ?, last_lng = ?, last_ping_at = ? WHERE driver_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDouble(1, lat);
                statement.setDouble(2, lng);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.setString(4, driverId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Driver> findBy(Connection connection, String column, String value) throws SQLException {
        String sql = "SELECT * FROM drivers WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        }
    }

    private Driver mapRow(ResultSet rs) throws SQLException {
        return new Driver(
                rs.getString("driver_id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("vehicle_id"),
                DriverStatus.valueOf(rs.getString("status")),
                nullableDouble(rs, "rating"),
                nullableDouble(rs, "last_lat"),
                nullableDouble(rs, "last_lng"),
                toInstant(rs.getTimestamp("last_ping_at")),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
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
