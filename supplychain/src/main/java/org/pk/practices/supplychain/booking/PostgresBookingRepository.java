package org.pk.practices.supplychain.booking;

import org.pk.practices.supplychain.common.Database;
import org.pk.practices.supplychain.common.DomainEvent;
import org.pk.practices.supplychain.common.EventPublisher;

import java.math.BigDecimal;
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

public class PostgresBookingRepository implements BookingRepository {

    private final Database database;
    private final EventPublisher eventPublisher;

    public PostgresBookingRepository(Database database, EventPublisher eventPublisher) {
        this.database = database;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void insertDraft(Booking booking) {
        database.withTransaction(connection -> {
            insertBookingRow(connection, booking);
            replaceCargoLineItems(connection, booking);
            replaceContainerRequirements(connection, booking);
            return null;
        });
    }

    @Override
    public Optional<Booking> find(String bookingId) {
        return database.withTransaction(connection -> findInternal(connection, bookingId));
    }

    @Override
    public List<Booking> findAll(BookingStatus statusFilter, String shipperIdFilter) {
        return database.withTransaction(connection -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM bookings");
            List<String> conditions = new ArrayList<>();
            if (statusFilter != null) {
                conditions.add("status = ?");
            }
            if (shipperIdFilter != null) {
                conditions.add("shipper_id = ?");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }
            sql.append(" ORDER BY created_at DESC");

            List<Booking> bookings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int i = 1;
                if (statusFilter != null) {
                    statement.setString(i++, statusFilter.name());
                }
                if (shipperIdFilter != null) {
                    statement.setString(i++, shipperIdFilter);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    // One extra pair of queries per row for cargo lines/container requirements —
                    // fine at the row counts an Operator's (now tenant-wide) view actually needs.
                    while (rs.next()) {
                        String tenantId = rs.getString("tenant_id");
                        String bookingId = rs.getString("booking_id");
                        List<CargoLineItem> cargoLineItems = findCargoLineItems(connection, tenantId, bookingId);
                        List<ContainerRequirement> containerRequirements = findContainerRequirements(connection, tenantId, bookingId);
                        bookings.add(mapRow(rs, cargoLineItems, containerRequirements));
                    }
                }
            }
            return bookings;
        });
    }

    @Override
    public boolean save(Booking previous, Booking updated, DomainEvent event) {
        return database.withTransaction(connection -> {
            String sql = """
                    UPDATE bookings SET status=?, mode_preference=?, incoterm=?, load_type=?,
                        origin_node_id=?, destination_node_id=?, shipper_id=?, consignee_id=?,
                        notify_party_id=?, contract_id=?, required_pickup_by=?, required_delivery_by=?,
                        total_weight_kg=?, total_volume_cbm=?, capacity_offering_id=?, version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND booking_id = ? AND version = ?
                    """;
            int rowsUpdated;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, updated.status().name());
                statement.setString(i++, updated.modePreference().name());
                statement.setString(i++, updated.incoterm().name());
                statement.setString(i++, updated.loadType().name());
                statement.setString(i++, updated.originNodeId());
                statement.setString(i++, updated.destinationNodeId());
                statement.setString(i++, updated.shipperId());
                statement.setString(i++, updated.consigneeId());
                setNullableString(statement, i++, updated.notifyPartyId());
                setNullableString(statement, i++, updated.contractId());
                setNullableTimestamp(statement, i++, updated.requiredPickupBy());
                setNullableTimestamp(statement, i++, updated.requiredDeliveryBy());
                setNullableBigDecimal(statement, i++, updated.totalWeightKg());
                setNullableBigDecimal(statement, i++, updated.totalVolumeCbm());
                setNullableString(statement, i++, updated.capacityOfferingId());
                statement.setTimestamp(i++, Timestamp.from(updated.updatedAt()));
                statement.setString(i++, previous.tenantId());
                statement.setString(i++, previous.bookingId());
                statement.setLong(i++, previous.version());
                rowsUpdated = statement.executeUpdate();
            }
            if (rowsUpdated == 0) {
                return false;
            }
            replaceCargoLineItems(connection, updated);
            replaceContainerRequirements(connection, updated);
            if (event != null) {
                eventPublisher.publish(connection, event);
            }
            return true;
        });
    }

    private void insertBookingRow(Connection connection, Booking booking) throws SQLException {
        String sql = """
                INSERT INTO bookings (tenant_id, booking_id, status, mode_preference, incoterm, load_type,
                    origin_node_id, destination_node_id, shipper_id, consignee_id, notify_party_id, contract_id,
                    required_pickup_by, required_delivery_by, total_weight_kg, total_volume_cbm, capacity_offering_id,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, booking.tenantId());
            statement.setString(i++, booking.bookingId());
            statement.setString(i++, booking.status().name());
            statement.setString(i++, booking.modePreference().name());
            statement.setString(i++, booking.incoterm().name());
            statement.setString(i++, booking.loadType().name());
            statement.setString(i++, booking.originNodeId());
            statement.setString(i++, booking.destinationNodeId());
            statement.setString(i++, booking.shipperId());
            statement.setString(i++, booking.consigneeId());
            setNullableString(statement, i++, booking.notifyPartyId());
            setNullableString(statement, i++, booking.contractId());
            setNullableTimestamp(statement, i++, booking.requiredPickupBy());
            setNullableTimestamp(statement, i++, booking.requiredDeliveryBy());
            setNullableBigDecimal(statement, i++, booking.totalWeightKg());
            setNullableBigDecimal(statement, i++, booking.totalVolumeCbm());
            setNullableString(statement, i++, booking.capacityOfferingId());
            statement.setLong(i++, booking.version());
            statement.setTimestamp(i++, Timestamp.from(booking.createdAt()));
            statement.setTimestamp(i++, Timestamp.from(booking.updatedAt()));
            statement.executeUpdate();
        }
    }

    private Optional<Booking> findInternal(Connection connection, String bookingId) throws SQLException {
        // Not scoped by tenant_id — idx_bookings_booking_id (schema.sql) backs this lookup;
        // an Operator resolves any booking regardless of which tenant created it.
        String sql = "SELECT * FROM bookings WHERE booking_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bookingId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                // The row's own tenant_id, not a caller-supplied one — scopes the child-record
                // lookups below to the same (tenant_id, booking_id) their FK actually uses.
                String tenantId = rs.getString("tenant_id");
                List<CargoLineItem> cargoLineItems = findCargoLineItems(connection, tenantId, bookingId);
                List<ContainerRequirement> containerRequirements = findContainerRequirements(connection, tenantId, bookingId);
                return Optional.of(mapRow(rs, cargoLineItems, containerRequirements));
            }
        }
    }

    private Booking mapRow(ResultSet rs, List<CargoLineItem> cargoLineItems, List<ContainerRequirement> containerRequirements) throws SQLException {
        return new Booking(
                rs.getString("tenant_id"),
                rs.getString("booking_id"),
                BookingStatus.valueOf(rs.getString("status")),
                TransportMode.valueOf(rs.getString("mode_preference")),
                Incoterm.valueOf(rs.getString("incoterm")),
                LoadType.valueOf(rs.getString("load_type")),
                rs.getString("origin_node_id"),
                rs.getString("destination_node_id"),
                rs.getString("shipper_id"),
                rs.getString("consignee_id"),
                rs.getString("notify_party_id"),
                rs.getString("contract_id"),
                toInstant(rs.getTimestamp("required_pickup_by")),
                toInstant(rs.getTimestamp("required_delivery_by")),
                rs.getBigDecimal("total_weight_kg"),
                rs.getBigDecimal("total_volume_cbm"),
                containerRequirements,
                cargoLineItems,
                rs.getString("capacity_offering_id"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private void replaceCargoLineItems(Connection connection, Booking booking) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM cargo_line_items WHERE tenant_id = ? AND booking_id = ?")) {
            delete.setString(1, booking.tenantId());
            delete.setString(2, booking.bookingId());
            delete.executeUpdate();
        }
        String insertSql = """
                INSERT INTO cargo_line_items (tenant_id, booking_id, line_id, hs_code, description,
                    country_of_origin, quantity, unit_of_measure, line_weight_kg, line_value_amount,
                    line_value_currency, dg_class, un_number, packing_group)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (CargoLineItem item : booking.cargoLineItems()) {
                int i = 1;
                insert.setString(i++, booking.tenantId());
                insert.setString(i++, booking.bookingId());
                insert.setString(i++, item.lineId());
                setNullableString(insert, i++, item.hsCode());
                insert.setString(i++, item.description());
                setNullableString(insert, i++, item.countryOfOrigin());
                insert.setBigDecimal(i++, item.quantity());
                insert.setString(i++, item.unitOfMeasure());
                setNullableBigDecimal(insert, i++, item.lineWeightKg());
                setNullableBigDecimal(insert, i++, item.lineValueAmount());
                setNullableString(insert, i++, item.lineValueCurrency());
                setNullableString(insert, i++, item.dgClass());
                setNullableString(insert, i++, item.unNumber());
                setNullableString(insert, i++, item.packingGroup());
                insert.addBatch();
            }
            if (!booking.cargoLineItems().isEmpty()) {
                insert.executeBatch();
            }
        }
    }

    private List<CargoLineItem> findCargoLineItems(Connection connection, String tenantId, String bookingId) throws SQLException {
        List<CargoLineItem> items = new ArrayList<>();
        String sql = "SELECT * FROM cargo_line_items WHERE tenant_id = ? AND booking_id = ? ORDER BY line_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, bookingId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(new CargoLineItem(
                            rs.getString("line_id"),
                            rs.getString("hs_code"),
                            rs.getString("description"),
                            rs.getString("country_of_origin"),
                            rs.getBigDecimal("quantity"),
                            rs.getString("unit_of_measure"),
                            rs.getBigDecimal("line_weight_kg"),
                            rs.getBigDecimal("line_value_amount"),
                            rs.getString("line_value_currency"),
                            rs.getString("dg_class"),
                            rs.getString("un_number"),
                            rs.getString("packing_group")
                    ));
                }
            }
        }
        return items;
    }

    private void replaceContainerRequirements(Connection connection, Booking booking) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM container_requirements WHERE tenant_id = ? AND booking_id = ?")) {
            delete.setString(1, booking.tenantId());
            delete.setString(2, booking.bookingId());
            delete.executeUpdate();
        }
        String insertSql = """
                INSERT INTO container_requirements (tenant_id, booking_id, container_type, quantity)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (ContainerRequirement requirement : booking.containerRequirements()) {
                insert.setString(1, booking.tenantId());
                insert.setString(2, booking.bookingId());
                insert.setString(3, requirement.containerType());
                insert.setInt(4, requirement.quantity());
                insert.addBatch();
            }
            if (!booking.containerRequirements().isEmpty()) {
                insert.executeBatch();
            }
        }
    }

    private List<ContainerRequirement> findContainerRequirements(Connection connection, String tenantId, String bookingId) throws SQLException {
        List<ContainerRequirement> requirements = new ArrayList<>();
        String sql = "SELECT * FROM container_requirements WHERE tenant_id = ? AND booking_id = ? ORDER BY container_type";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, bookingId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    requirements.add(new ContainerRequirement(rs.getString("container_type"), rs.getInt("quantity")));
                }
            }
        }
        return requirements;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static void setNullableBigDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }
}
