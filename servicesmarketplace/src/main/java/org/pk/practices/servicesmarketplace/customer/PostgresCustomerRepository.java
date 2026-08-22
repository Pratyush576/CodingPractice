package org.pk.practices.servicesmarketplace.customer;

import org.pk.practices.servicesmarketplace.common.ConflictException;
import org.pk.practices.servicesmarketplace.common.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

public class PostgresCustomerRepository implements CustomerRepository {

    private final Database database;

    public PostgresCustomerRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Customer customer) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO customers (customer_id, name, email, password_hash, default_payment_method_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, customer.customerId());
                statement.setString(2, customer.name());
                statement.setString(3, customer.email());
                statement.setString(4, customer.passwordHash());
                statement.setString(5, customer.defaultPaymentMethodId());
                statement.setTimestamp(6, Timestamp.from(customer.createdAt()));
                try {
                    statement.executeUpdate();
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        throw new ConflictException("A customer with this email already exists");
                    }
                    throw e;
                }
            }
            return null;
        });
    }

    @Override
    public Optional<Customer> findById(String customerId) {
        return database.withTransaction(connection -> findBy(connection, "customer_id", customerId));
    }

    @Override
    public Optional<Customer> findByEmail(String email) {
        return database.withTransaction(connection -> findBy(connection, "email", email));
    }

    private Optional<Customer> findBy(Connection connection, String column, String value) throws SQLException {
        String sql = "SELECT * FROM customers WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(
                        rs.getString("customer_id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("default_payment_method_id"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        }
    }
}
