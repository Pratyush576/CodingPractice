package org.pk.practices.servicesmarketplace.credit;

import org.pk.practices.servicesmarketplace.common.ConflictException;
import org.pk.practices.servicesmarketplace.common.Database;
import org.pk.practices.servicesmarketplace.common.DomainException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public class PostgresCreditLedgerRepository implements CreditLedgerRepository {

    private final Database database;

    public PostgresCreditLedgerRepository(Database database) {
        this.database = database;
    }

    @Override
    public void openBalance(String proId) {
        database.withTransaction(connection -> {
            String sql = "INSERT INTO pro_credit_balances (pro_id, balance, version) VALUES (?, 0, 0)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, proId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public double getBalance(String proId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT balance FROM pro_credit_balances WHERE pro_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, proId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new DomainException("NOT_FOUND", "No credit balance for pro " + proId);
                    }
                    return rs.getDouble("balance");
                }
            }
        });
    }

    /**
     * One JDBC transaction spans all three steps below — a thrown exception
     * rolls back everything, including the {@code lead_unlocks} insert, so a
     * failed balance check never leaves a half-applied unlock behind.
     */
    @Override
    public UnlockResult unlockLead(String leadId, String proId, double cost) {
        return database.withTransaction(connection -> {
            String unlockSql = """
                    INSERT INTO lead_unlocks (lead_id, pro_id, credit_cost, unlocked_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (lead_id, pro_id) DO NOTHING
                    """;
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(unlockSql)) {
                statement.setString(1, leadId);
                statement.setString(2, proId);
                statement.setDouble(3, cost);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                return UnlockResult.ALREADY_UNLOCKED;
            }

            // No "AND version = ?" here: the balance check and the arithmetic both
            // happen inside this one UPDATE statement, not a separate read then a
            // separate write, so Postgres's own row lock already makes this atomic
            // — there's no read-modify-write gap for a stale version to catch.
            // version still increments, as a monotonic audit counter.
            String deductSql = """
                    UPDATE pro_credit_balances SET balance = balance - ?, version = version + 1
                    WHERE pro_id = ? AND balance >= ?
                    """;
            int debited;
            try (PreparedStatement statement = connection.prepareStatement(deductSql)) {
                statement.setDouble(1, cost);
                statement.setString(2, proId);
                statement.setDouble(3, cost);
                debited = statement.executeUpdate();
            }
            if (debited == 0) {
                throw new ConflictException("Insufficient credits to unlock this lead");
            }

            String transactionSql = """
                    INSERT INTO credit_transactions (transaction_id, pro_id, type, amount, lead_id, created_at)
                    VALUES (?, ?, 'DEDUCTION', ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(transactionSql)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, proId);
                statement.setDouble(3, -cost);
                statement.setString(4, leadId);
                statement.setTimestamp(5, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }

            String leadSql = "UPDATE leads SET status = 'UNLOCKED', unlocked_at = ? WHERE lead_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(leadSql)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.setString(2, leadId);
                statement.executeUpdate();
            }

            return UnlockResult.UNLOCKED;
        });
    }

    @Override
    public void purchaseCredits(String proId, double amount) {
        database.withTransaction(connection -> {
            String updateSql = "UPDATE pro_credit_balances SET balance = balance + ?, version = version + 1 WHERE pro_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setDouble(1, amount);
                statement.setString(2, proId);
                if (statement.executeUpdate() == 0) {
                    throw new DomainException("NOT_FOUND", "No credit balance for pro " + proId);
                }
            }
            String transactionSql = """
                    INSERT INTO credit_transactions (transaction_id, pro_id, type, amount, lead_id, created_at)
                    VALUES (?, ?, 'PURCHASE', ?, NULL, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(transactionSql)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, proId);
                statement.setDouble(3, amount);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return null;
        });
    }
}
