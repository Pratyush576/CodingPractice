package org.pk.practices.cabreservation.payment;

import org.pk.practices.cabreservation.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

public class PostgresPaymentRepository implements PaymentRepository {

    private final Database database;

    public PostgresPaymentRepository(Database database) {
        this.database = database;
    }

    @Override
    public boolean insert(Payment payment) {
        return database.withTransaction(connection -> {
            String sql = "INSERT INTO payments (payment_id, trip_id, amount, status, gateway_reference, created_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, payment.paymentId());
                statement.setString(2, payment.tripId());
                statement.setDouble(3, payment.amount());
                statement.setString(4, payment.status().name());
                statement.setString(5, payment.gatewayReference());
                statement.setTimestamp(6, Timestamp.from(payment.createdAt()));
                try {
                    statement.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        return false; // payments.trip_id UNIQUE — already charged for this trip, not an error
                    }
                    throw e;
                }
            }
        });
    }

    @Override
    public Optional<Payment> findByTripId(String tripId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM payments WHERE trip_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tripId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Payment(
                            rs.getString("payment_id"),
                            rs.getString("trip_id"),
                            rs.getDouble("amount"),
                            PaymentStatus.valueOf(rs.getString("status")),
                            rs.getString("gateway_reference"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        });
    }
}
