package org.pk.practices.servicesmarketplace.pro;

import org.pk.practices.servicesmarketplace.common.ConflictException;
import org.pk.practices.servicesmarketplace.common.Database;

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

public class PostgresProRepository implements ProRepository {

    private static final int MAX_MATCHES = 5;

    private final Database database;

    public PostgresProRepository(Database database) {
        this.database = database;
    }

    @Override
    public void insert(Pro pro, ProProfile profile) {
        database.withTransaction(connection -> {
            String proSql = """
                    INSERT INTO pros (pro_id, business_name, email, password_hash, verification_status, rating, years_in_business, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(proSql)) {
                int i = 1;
                statement.setString(i++, pro.proId());
                statement.setString(i++, pro.businessName());
                statement.setString(i++, pro.email());
                statement.setString(i++, pro.passwordHash());
                statement.setString(i++, pro.verificationStatus().name());
                setNullableDouble(statement, i++, pro.rating());
                setNullableInt(statement, i++, pro.yearsInBusiness());
                statement.setTimestamp(i++, Timestamp.from(pro.createdAt()));
                try {
                    statement.executeUpdate();
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        throw new ConflictException("A pro with this email already exists");
                    }
                    throw e;
                }
            }
            String profileSql = """
                    INSERT INTO pro_profiles (pro_id, category_id, service_area_lat, service_area_lng,
                        service_area_radius_km, starting_price, min_budget, max_job_size, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(profileSql)) {
                int i = 1;
                statement.setString(i++, profile.proId());
                statement.setString(i++, profile.categoryId());
                statement.setDouble(i++, profile.serviceAreaLat());
                statement.setDouble(i++, profile.serviceAreaLng());
                statement.setDouble(i++, profile.serviceAreaRadiusKm());
                setNullableDouble(statement, i++, profile.startingPrice());
                setNullableDouble(statement, i++, profile.minBudget());
                statement.setString(i++, profile.maxJobSize());
                statement.setTimestamp(i++, Timestamp.from(profile.updatedAt()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Pro> findById(String proId) {
        return database.withTransaction(connection -> findBy(connection, "pro_id", proId));
    }

    @Override
    public Optional<Pro> findByEmail(String email) {
        return database.withTransaction(connection -> findBy(connection, "email", email));
    }

    @Override
    public Optional<ProProfile> findProfileByProId(String proId) {
        return database.withTransaction(connection -> {
            String sql = "SELECT * FROM pro_profiles WHERE pro_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, proId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapProfile(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void updateProfile(ProProfile profile) {
        database.withTransaction(connection -> {
            String sql = """
                    UPDATE pro_profiles SET category_id = ?, service_area_lat = ?, service_area_lng = ?,
                        service_area_radius_km = ?, starting_price = ?, min_budget = ?, max_job_size = ?, updated_at = ?
                    WHERE pro_id = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, profile.categoryId());
                statement.setDouble(i++, profile.serviceAreaLat());
                statement.setDouble(i++, profile.serviceAreaLng());
                statement.setDouble(i++, profile.serviceAreaRadiusKm());
                setNullableDouble(statement, i++, profile.startingPrice());
                setNullableDouble(statement, i++, profile.minBudget());
                statement.setString(i++, profile.maxJobSize());
                statement.setTimestamp(i++, Timestamp.from(Instant.now()));
                statement.setString(i++, profile.proId());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ProProfile> findMatchingProfiles(String categoryId, double lat, double lng) {
        return database.withTransaction(connection -> {
            String sql = """
                    SELECT pp.* FROM pro_profiles pp
                    JOIN pros p ON p.pro_id = pp.pro_id
                    WHERE pp.category_id = ?
                      AND 6371 * acos(LEAST(1.0, GREATEST(-1.0,
                            cos(radians(?)) * cos(radians(pp.service_area_lat))
                            * cos(radians(pp.service_area_lng) - radians(?))
                            + sin(radians(?)) * sin(radians(pp.service_area_lat))
                          ))) <= pp.service_area_radius_km
                    ORDER BY p.rating DESC NULLS LAST
                    LIMIT ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setString(i++, categoryId);
                statement.setDouble(i++, lat);
                statement.setDouble(i++, lng);
                statement.setDouble(i++, lat);
                statement.setInt(i++, MAX_MATCHES);
                List<ProProfile> results = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapProfile(rs));
                    }
                }
                return results;
            }
        });
    }

    private Optional<Pro> findBy(Connection connection, String column, String value) throws SQLException {
        String sql = "SELECT * FROM pros WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapPro(rs)) : Optional.empty();
            }
        }
    }

    private Pro mapPro(ResultSet rs) throws SQLException {
        return new Pro(
                rs.getString("pro_id"),
                rs.getString("business_name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                nullableDouble(rs, "rating"),
                nullableInt(rs, "years_in_business"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private ProProfile mapProfile(ResultSet rs) throws SQLException {
        return new ProProfile(
                rs.getString("pro_id"),
                rs.getString("category_id"),
                rs.getDouble("service_area_lat"),
                rs.getDouble("service_area_lng"),
                rs.getDouble("service_area_radius_km"),
                nullableDouble(rs, "starting_price"),
                nullableDouble(rs, "min_budget"),
                rs.getString("max_job_size"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
