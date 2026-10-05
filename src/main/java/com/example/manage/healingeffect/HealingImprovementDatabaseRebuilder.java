package com.example.manage.healingeffect;

import com.example.manage.repository.HealingMeasurementRepository;
import java.sql.*;
import java.util.*;

/** Explicit, idempotent Raw -> Summary count backfill. It never creates or deletes schema/data. */
public final class HealingImprovementDatabaseRebuilder {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "healing_spot_effect_summary", Set.of("participant_count", "total_experience_count",
                    "stress_improved_count", "emotional_improved_count"),
            "member_healing_spot_effect_summary", Set.of("total_experience_count",
                    "stress_improved_count", "emotional_improved_count"));

    public record Result(String status, boolean schemaReady, int overallRows,
            int memberSpotRows, int participantOverallRows, List<String> warnings) {
        public Result { warnings = List.copyOf(warnings); }
    }
    private record MemberSpotKey(long memberId, long spotId) {}

    public Result execute(Connection connection, long siteId, boolean dryRun) throws SQLException {
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("독립적인 새 connection이 필요합니다.");
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setReadOnly(dryRun);
        connection.setAutoCommit(false);
        try {
            if (dryRun && connection.getMetaData().getDatabaseProductName().equals("MySQL"))
                try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
            if (!dryRun) lockSites(connection);

            var missing = missingSchema(connection);
            boolean schemaReady = missing.isEmpty();
            if (!RawMeasurementDatabaseImporter.schemaReady(connection))
                throw new IllegalArgumentException("Raw measurement schema 준비 필요");
            validateRawBatchSite(connection, siteId);

            var aggregate = new HealingImprovementAggregator().aggregate(
                    HealingMeasurementRepository.findForSiteAggregation(connection, siteId));
            if (aggregate.overallSpots().isEmpty())
                throw new IllegalArgumentException("집계할 Raw measurement가 없습니다.");

            validateSummaryCoverage(connection, siteId, aggregate, !dryRun);
            if (dryRun) {
                connection.rollback();
                return new Result("DRY_RUN", schemaReady, aggregate.overallSpots().size(),
                        aggregate.memberSpots().size(), aggregate.participantOverall().size(),
                        schemaReady ? List.of() : List.of("실제 rebuild 전 schema 준비 필요: " + missing));
            }
            if (!schemaReady) throw new IllegalArgumentException("Improvement schema 준비 필요: " + missing);

            update(connection, aggregate);
            connection.commit();
            return new Result("REBUILT", true, aggregate.overallSpots().size(),
                    aggregate.memberSpots().size(), aggregate.participantOverall().size(), List.of());
        } catch (SQLException | RuntimeException ex) {
            try { connection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
            throw ex;
        }
    }

    private static void lockSites(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select site_id from site order by site_id for update");
             var rows = statement.executeQuery()) {
            while (rows.next()) { /* Serialize with both existing explicit importers. */ }
        }
    }

    private static void validateRawBatchSite(Connection connection, long siteId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select count(*), min(target_site_id), max(target_site_id) from healing_measurement_import_batch");
             var rows = statement.executeQuery()) {
            rows.next();
            if (rows.getInt(1) != 1 || rows.getLong(2) != siteId || rows.getLong(3) != siteId)
                throw new IllegalArgumentException("대상 Site의 단일 Raw import batch가 필요합니다.");
        }
    }

    private static List<String> missingSchema(Connection connection) throws SQLException {
        var missing = new ArrayList<String>();
        for (var entry : REQUIRED_COLUMNS.entrySet()) {
            var present = new HashSet<String>();
            try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
                while (rows.next()) if (entry.getKey().equalsIgnoreCase(rows.getString("TABLE_NAME")))
                    present.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            for (String column : entry.getValue()) if (!present.contains(column))
                missing.add(entry.getKey() + "." + column);
        }
        return missing;
    }

    private static void validateSummaryCoverage(Connection connection, long siteId,
            HealingImprovementAggregator.Result aggregate, boolean lock) throws SQLException {
        var overall = new HashMap<Long, int[]>();
        try (var statement = connection.prepareStatement("""
                select e.spot_id, e.stress_valid_session_count, e.emotional_valid_session_count
                from healing_spot_effect_summary e
                join healing_spot s on s.spot_id=e.spot_id
                join healing_course c on c.course_id=s.course_id
                where c.site_id=?
                """ + (lock ? " for update" : ""))) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) overall.put(rows.getLong(1), new int[]{rows.getInt(2), rows.getInt(3)});
            }
        }
        var expectedOverall = new HashSet<Long>();
        for (var row : aggregate.overallSpots()) {
            expectedOverall.add(row.spotId());
            var existing = overall.get(row.spotId());
            if (existing == null || existing[0] != row.stress().validCount()
                    || existing[1] != row.emotional().validCount())
                throw new IllegalArgumentException("Overall Summary coverage/valid count 불일치: spotId=" + row.spotId());
        }
        if (!overall.keySet().equals(expectedOverall))
            throw new IllegalArgumentException("Overall Summary row 범위 불일치");

        var memberSpots = new HashMap<MemberSpotKey, int[]>();
        try (var statement = connection.prepareStatement("""
                select e.member_id, e.spot_id, e.stress_valid_session_count, e.emotional_valid_session_count
                from member_healing_spot_effect_summary e
                join healing_spot s on s.spot_id=e.spot_id
                join healing_course c on c.course_id=s.course_id
                where c.site_id=?
                """ + (lock ? " for update" : ""))) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) memberSpots.put(new MemberSpotKey(rows.getLong(1), rows.getLong(2)),
                        new int[]{rows.getInt(3), rows.getInt(4)});
            }
        }
        var expectedMemberSpots = new HashSet<MemberSpotKey>();
        for (var row : aggregate.memberSpots()) {
            var key = new MemberSpotKey(row.memberId(), row.spotId());
            expectedMemberSpots.add(key);
            var existing = memberSpots.get(key);
            if (existing == null || existing[0] != row.stress().validCount()
                    || existing[1] != row.emotional().validCount())
                throw new IllegalArgumentException("Member Summary coverage/valid count 불일치: " + key);
        }
        if (!memberSpots.keySet().equals(expectedMemberSpots))
            throw new IllegalArgumentException("Member Summary row 범위 불일치");
    }

    private static void update(Connection connection, HealingImprovementAggregator.Result aggregate)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                update healing_spot_effect_summary
                set participant_count=?, total_experience_count=?, stress_valid_session_count=?,
                    stress_improved_count=?, emotional_valid_session_count=?, emotional_improved_count=?
                where spot_id=?
                """)) {
            for (var row : aggregate.overallSpots()) {
                statement.setInt(1, row.participantCount());
                statement.setInt(2, row.totalExperienceCount());
                statement.setInt(3, row.stress().validCount());
                statement.setInt(4, row.stress().improvedCount());
                statement.setInt(5, row.emotional().validCount());
                statement.setInt(6, row.emotional().improvedCount());
                statement.setLong(7, row.spotId());
                if (statement.executeUpdate() != 1) throw new SQLException("Overall Summary update count 불일치");
            }
        }
        try (var statement = connection.prepareStatement("""
                update member_healing_spot_effect_summary
                set total_experience_count=?, stress_valid_session_count=?, stress_improved_count=?,
                    emotional_valid_session_count=?, emotional_improved_count=?
                where member_id=? and spot_id=?
                """)) {
            for (var row : aggregate.memberSpots()) {
                statement.setInt(1, row.totalExperienceCount());
                statement.setInt(2, row.stress().validCount());
                statement.setInt(3, row.stress().improvedCount());
                statement.setInt(4, row.emotional().validCount());
                statement.setInt(5, row.emotional().improvedCount());
                statement.setLong(6, row.memberId());
                statement.setLong(7, row.spotId());
                if (statement.executeUpdate() != 1) throw new SQLException("Member Summary update count 불일치");
            }
        }
    }
}
