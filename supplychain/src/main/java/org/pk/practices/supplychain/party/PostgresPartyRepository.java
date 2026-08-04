package org.pk.practices.supplychain.party;

import org.pk.practices.supplychain.common.ConflictException;
import org.pk.practices.supplychain.common.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresPartyRepository implements PartyRepository {

    private static final String POSTGRES_UNIQUE_VIOLATION = "23505";

    private final Database database;

    public PostgresPartyRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Party party) {
        database.withTransaction(connection -> {
            String sql = """
                    INSERT INTO parties (party_id, tenant_id, role, name, email, password_hash, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, party.partyId());
                statement.setString(2, party.tenantId());
                statement.setString(3, party.role().name());
                statement.setString(4, party.name());
                statement.setString(5, party.email());
                statement.setString(6, party.passwordHash());
                statement.setTimestamp(7, Timestamp.from(party.createdAt()));
                statement.executeUpdate();
            } catch (SQLException e) {
                if (POSTGRES_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw new ConflictException("An account already exists for " + party.email());
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public Optional<Party> findByEmail(String email) {
        return database.withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM parties WHERE email = ?")) {
                statement.setString(1, email);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Party>empty();
                }
            }
        });
    }

    @Override
    public Optional<Party> findById(String partyId) {
        return database.withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM parties WHERE party_id = ?")) {
                statement.setString(1, partyId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Party>empty();
                }
            }
        });
    }

    @Override
    public boolean tenantExists(String tenantId) {
        return database.withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM parties WHERE tenant_id = ?)")) {
                statement.setString(1, tenantId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    @Override
    public List<String> listDistinctTenantIds() {
        return database.withTransaction(connection -> {
            List<String> tenantIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT tenant_id FROM parties ORDER BY tenant_id");
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tenantIds.add(rs.getString("tenant_id"));
                }
            }
            return tenantIds;
        });
    }

    private Party mapRow(ResultSet rs) throws SQLException {
        return new Party(
                rs.getString("party_id"),
                rs.getString("tenant_id"),
                PartyRole.valueOf(rs.getString("role")),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
