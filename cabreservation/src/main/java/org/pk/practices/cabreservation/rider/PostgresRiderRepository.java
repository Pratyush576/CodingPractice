package org.pk.practices.cabreservation.rider;

import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public class PostgresRiderRepository implements RiderRepository {

    private final Database database;

    public PostgresRiderRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Rider rider) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO riders (rider_id, name, email, password_hash, default_payment_method_id, rating, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, rider.riderId());
                statement.setString(2, rider.name());
                statement.setString(3, rider.email());
                statement.setString(4, rider.passwordHash());
                statement.setString(5, rider.defaultPaymentMethodId());
                if (rider.rating() == null) {
                    statement.setNull(6, java.sql.Types.NUMERIC);
                } else {
                    statement.setDouble(6, rider.rating());
                }
                statement.setTimestamp(7, Timestamp.from(rider.createdAt()));
                try {
                    statement.executeUpdate();
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) { // unique_violation
                        throw new ConflictException("A rider with this email already exists");
                    }
                    throw e;
                }
            }
            return null;
        });
    }

    @Override
    public Optional<Rider> findById(String riderId) {
        return database.withTransaction(connection -> findBy(connection, "rider_id", riderId));
    }

    @Override
    public Optional<Rider> findByEmail(String email) {
        return database.withTransaction(connection -> findBy(connection, "email", email));
    }

    private Optional<Rider> findBy(java.sql.Connection connection, String column, String value) throws SQLException {
        String sql = "SELECT * FROM riders WHERE " + column + " = ?";
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

    private Rider mapRow(ResultSet rs) throws SQLException {
        double rating = rs.getDouble("rating");
        return new Rider(
                rs.getString("rider_id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("default_payment_method_id"),
                rs.wasNull() ? null : rating,
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
