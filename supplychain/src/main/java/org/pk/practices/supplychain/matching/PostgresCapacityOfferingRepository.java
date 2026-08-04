package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.TransportMode;
import org.pk.practices.supplychain.common.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresCapacityOfferingRepository implements CapacityOfferingRepository {

    private final Database database;

    public PostgresCapacityOfferingRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(CapacityOffering offering) {
        database.withTransaction(connection -> {
            insertOfferingRow(connection, offering);
            insertContainerCapacities(connection, offering);
            return null;
        });
    }

    @Override
    public Optional<CapacityOffering> find(String offeringId) {
        return database.withTransaction(connection -> findInternal(connection, offeringId));
    }

    @Override
    public List<CapacityOffering> findAllForOperator(String operatorId) {
        return database.withTransaction(connection -> {
            List<CapacityOffering> offerings = new ArrayList<>();
            String sql = "SELECT * FROM capacity_offerings WHERE operator_id = ? ORDER BY created_at DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, operatorId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String tenantId = rs.getString("tenant_id");
                        String offeringId = rs.getString("offering_id");
                        offerings.add(mapRow(rs, findContainerCapacities(connection, tenantId, offeringId)));
                    }
                }
            }
            return offerings;
        });
    }

    @Override
    public List<CapacityOffering> findCandidates(TransportMode mode, String originNodeId, String destinationNodeId) {
        return database.withTransaction(connection -> {
            // Deliberately no tenant_id predicate — matching is cross-tenant (LLD.md §4).
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM capacity_offerings WHERE origin_node_id = ? "
                            + "AND destination_node_id = ? AND status = 'ACTIVE'");
            if (mode != null) {
                sql.append(" AND mode = ?");
            }
            List<CapacityOffering> offerings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int i = 1;
                statement.setString(i++, originNodeId);
                statement.setString(i++, destinationNodeId);
                if (mode != null) {
                    statement.setString(i++, mode.name());
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String tenantId = rs.getString("tenant_id");
                        String offeringId = rs.getString("offering_id");
                        offerings.add(mapRow(rs, findContainerCapacities(connection, tenantId, offeringId)));
                    }
                }
            }
            return offerings;
        });
    }

    @Override
    public boolean save(CapacityOffering previous, CapacityOffering updated) {
        return database.withTransaction(connection -> {
            String sql = """
                    UPDATE capacity_offerings SET available_weight_kg = ?, available_volume_cbm = ?,
                        status = ?, version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND offering_id = ? AND version = ?
                    """;
            int rowsUpdated;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                setNullableBigDecimal(statement, i++, updated.availableWeightKg());
                setNullableBigDecimal(statement, i++, updated.availableVolumeCbm());
                statement.setString(i++, updated.status().name());
                statement.setTimestamp(i++, Timestamp.from(updated.updatedAt()));
                statement.setString(i++, previous.tenantId());
                statement.setString(i++, previous.offeringId());
                statement.setLong(i++, previous.version());
                rowsUpdated = statement.executeUpdate();
            }
            if (rowsUpdated == 0) {
                return false;
            }
            for (ContainerCapacity capacity : updated.containerCapacities()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE capacity_offering_containers SET available_quantity = ? "
                                + "WHERE tenant_id = ? AND offering_id = ? AND container_type = ?")) {
                    statement.setInt(1, capacity.availableQuantity());
                    statement.setString(2, updated.tenantId());
                    statement.setString(3, updated.offeringId());
                    statement.setString(4, capacity.containerType());
                    statement.executeUpdate();
                }
            }
            return true;
        });
    }

    private void insertOfferingRow(Connection connection, CapacityOffering offering) throws java.sql.SQLException {
        String sql = """
                INSERT INTO capacity_offerings (tenant_id, offering_id, operator_id, mode, origin_node_id,
                    destination_node_id, total_weight_kg, available_weight_kg, total_volume_cbm, available_volume_cbm,
                    rate_amount, rate_currency, valid_from, valid_until, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, offering.tenantId());
            statement.setString(i++, offering.offeringId());
            statement.setString(i++, offering.operatorId());
            statement.setString(i++, offering.mode().name());
            statement.setString(i++, offering.originNodeId());
            statement.setString(i++, offering.destinationNodeId());
            setNullableBigDecimal(statement, i++, offering.totalWeightKg());
            setNullableBigDecimal(statement, i++, offering.availableWeightKg());
            setNullableBigDecimal(statement, i++, offering.totalVolumeCbm());
            setNullableBigDecimal(statement, i++, offering.availableVolumeCbm());
            statement.setBigDecimal(i++, offering.rateAmount());
            statement.setString(i++, offering.rateCurrency());
            setNullableTimestamp(statement, i++, offering.validFrom());
            setNullableTimestamp(statement, i++, offering.validUntil());
            statement.setString(i++, offering.status().name());
            statement.setLong(i++, offering.version());
            statement.setTimestamp(i++, Timestamp.from(offering.createdAt()));
            statement.setTimestamp(i++, Timestamp.from(offering.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void insertContainerCapacities(Connection connection, CapacityOffering offering) throws java.sql.SQLException {
        if (offering.containerCapacities().isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO capacity_offering_containers (tenant_id, offering_id, container_type, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ContainerCapacity capacity : offering.containerCapacities()) {
                statement.setString(1, offering.tenantId());
                statement.setString(2, offering.offeringId());
                statement.setString(3, capacity.containerType());
                statement.setInt(4, capacity.totalQuantity());
                statement.setInt(5, capacity.availableQuantity());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<CapacityOffering> findInternal(Connection connection, String offeringId) throws java.sql.SQLException {
        // idx_capacity_offerings_offering_id (schema.sql) backs this — reserve() looks up an
        // offering by ID alone, regardless of which tenant created it.
        String sql = "SELECT * FROM capacity_offerings WHERE offering_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, offeringId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String tenantId = rs.getString("tenant_id");
                return Optional.of(mapRow(rs, findContainerCapacities(connection, tenantId, offeringId)));
            }
        }
    }

    private List<ContainerCapacity> findContainerCapacities(Connection connection, String tenantId, String offeringId) throws java.sql.SQLException {
        List<ContainerCapacity> capacities = new ArrayList<>();
        String sql = "SELECT * FROM capacity_offering_containers WHERE tenant_id = ? AND offering_id = ? ORDER BY container_type";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, offeringId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    capacities.add(new ContainerCapacity(
                            rs.getString("container_type"),
                            rs.getInt("total_quantity"),
                            rs.getInt("available_quantity")
                    ));
                }
            }
        }
        return capacities;
    }

    private CapacityOffering mapRow(ResultSet rs, List<ContainerCapacity> containerCapacities) throws java.sql.SQLException {
        return new CapacityOffering(
                rs.getString("tenant_id"),
                rs.getString("offering_id"),
                rs.getString("operator_id"),
                TransportMode.valueOf(rs.getString("mode")),
                rs.getString("origin_node_id"),
                rs.getString("destination_node_id"),
                containerCapacities,
                rs.getBigDecimal("total_weight_kg"),
                rs.getBigDecimal("available_weight_kg"),
                rs.getBigDecimal("total_volume_cbm"),
                rs.getBigDecimal("available_volume_cbm"),
                rs.getBigDecimal("rate_amount"),
                rs.getString("rate_currency"),
                toInstant(rs.getTimestamp("valid_from")),
                toInstant(rs.getTimestamp("valid_until")),
                CapacityOfferingStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static void setNullableBigDecimal(PreparedStatement statement, int index, BigDecimal value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }
}
