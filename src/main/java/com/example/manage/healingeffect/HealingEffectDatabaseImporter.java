package com.example.manage.healingeffect;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Explicit one-time utility, deliberately not a Spring component.
 * Owns its connection transaction; never creates or updates the schema.
 */
public final class HealingEffectDatabaseImporter {
    public static final List<String> TABLES = List.of("healing_spot_effect_summary",
            "member_healing_spot_effect_summary", "healing_effect_import_batch");
    private static final List<String> COURSE_CODES = List.of("HC-A", "HC-B", "HC-C");
    private static final List<String> COURSE_NAMES = List.of("회복 코스", "감각 코스", "힐링 코스");

    public record Result(String status, int overallRows, int participantRows, boolean schemaReady,
            List<String> warnings) {
    }

    private record Mapping(Map<String, Long> spots, Map<Integer, Long> members) {
    }

    /** Caller supplies a fresh connection and closes it after return. */
    public Result execute(Connection connection, HealingEffectSource source, long siteId, boolean dryRun)
            throws SQLException {
        if (!connection.getAutoCommit())
            throw new IllegalArgumentException("독립적인 새 connection이 필요합니다.");
        if (source.filename().length() > 255)
            throw invalid("source filename은 255자 이하여야 합니다.");
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setReadOnly(dryRun);
        connection.setAutoCommit(false);
        try {
            if (dryRun && connection.getMetaData().getDatabaseProductName().equals("MySQL")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION READ ONLY");
                }
            }
            // All importer writes share these locks, even if different Sites were selected.
            // Existing Site/Spot edit/delete services take the same Site locks.
            if (!dryRun) {
                try (var statement = connection.prepareStatement("select site_id from site order by site_id for update");
                     var rows = statement.executeQuery()) {
                    while (rows.next()) { /* Acquire all locks before inspecting import state. */ }
                }
            }
            var missingTables = new ArrayList<String>();
            for (String table : TABLES)
                if (!tableExists(connection, table)) missingTables.add(table);
            boolean schemaReady = missingTables.isEmpty();
            if (tableExists(connection, "healing_effect_import_batch")) {
                try (var statement = connection.prepareStatement(
                        "select target_site_id from healing_effect_import_batch where source_sha256 = ?")) {
                    statement.setString(1, source.sha256());
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            long originalSite = rows.getLong(1);
                            if (originalSite != siteId)
                                throw invalid("동일 source가 다른 Site에 already imported: " + originalSite);
                            connection.rollback();
                            return new Result("ALREADY_IMPORTED", 0, 0, schemaReady,
                                    List.of("기존 데이터를 변경하거나 삭제된 summary를 재생성하지 않습니다."));
                        }
                    }
                }
            }
            for (String table : TABLES) {
                if (tableExists(connection, table) && count(connection, table) > 0)
                    throw invalid("기존 데이터가 존재하며 다른 source file이거나 출처 불명 데이터이므로 자동 overwrite하지 않습니다.");
            }
            Mapping mapping = validateMapping(connection, source, siteId, !dryRun);
            if (dryRun) {
                connection.rollback();
                return new Result("DRY_RUN", source.overall().size(), source.participants().size(), schemaReady,
                        schemaReady ? List.of() : List.of("실제 write 전 스키마 준비 필요: " + missingTables));
            }
            if (!schemaReady)
                throw invalid("Schema missing: " + missingTables + ". 스키마를 먼저 준비해야 합니다.");
            persist(connection, source, siteId, mapping);
            connection.commit();
            return new Result("IMPORTED", source.overall().size(), source.participants().size(), true, List.of());
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                ex.addSuppressed(rollback);
            }
            throw ex;
        }
    }

    private Mapping validateMapping(Connection connection, HealingEffectSource source, long siteId, boolean lock)
            throws SQLException {
        var errors = new ArrayList<String>();
        var spots = new HashMap<String, Long>();
        var members = new HashMap<Integer, Long>();
        var courses = new HashMap<String, Long>();
        try (var statement = connection.prepareStatement("select site_id from site where site_id = ?")) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) errors.add("대상 Site가 없습니다: " + siteId);
            }
        }
        try (var statement = connection.prepareStatement(
                "select course_id, code, name from healing_course where site_id = ?")) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String code = rows.getString("code");
                    int index = COURSE_CODES.indexOf(code);
                    if (index < 0 || !COURSE_NAMES.get(index).equals(rows.getString("name")))
                        errors.add("예상하지 않은 HealingCourse code/name: " + code);
                    if (courses.putIfAbsent(code, rows.getLong("course_id")) != null)
                        errors.add("중복 HealingCourse: " + code);
                }
            }
        }
        if (!courses.keySet().equals(new HashSet<>(COURSE_CODES)))
            errors.add("대상 Site에 HC-A/HC-B/HC-C 정확히 필요: " + courses.keySet());
        try (var statement = connection.prepareStatement("""
                select s.spot_id, s.code, s.name, c.code as course_code
                from healing_spot s join healing_course c on c.course_id = s.course_id
                where c.site_id = ?
                """)) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String code = rows.getString("code");
                    int index = HealingEffectExcelParser.CODES.indexOf(code);
                    if (index < 0 || !HealingEffectExcelParser.NAMES.get(index).equals(rows.getString("name"))
                            || !COURSE_CODES.get(index / 2).equals(rows.getString("course_code")))
                        errors.add("HealingSpot course/code/name 불일치: " + code);
                    if (spots.putIfAbsent(code, rows.getLong("spot_id")) != null)
                        errors.add("중복 HealingSpot code: " + code);
                }
            }
        }
        if (!spots.keySet().equals(new HashSet<>(HealingEffectExcelParser.CODES)))
            errors.add("대상 Site에 HS1~HS6 정확히 필요: " + spots.keySet());
        // Locks keep participantNo mapping stable during a write and serialize concurrent deletion.
        try (var statement = connection.prepareStatement(
                "select member_id, participant_no from member order by member_id" + (lock ? " for update" : ""));
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                int no = rows.getInt("participant_no");
                if (members.putIfAbsent(no, rows.getLong("member_id")) != null)
                    errors.add("중복 Member participantNo: " + no);
            }
        }
        source.participants().stream().map(HealingEffectSource.Participant::participant).distinct()
                .filter(code -> !members.containsKey(Integer.parseInt(code.substring(1))))
                .forEach(code -> errors.add("Member 누락: " + code));
        if (!errors.isEmpty())
            throw new EffectImportValidationException(errors);
        return new Mapping(spots, members);
    }

    private void persist(Connection connection, HealingEffectSource source, long siteId, Mapping mapping)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into healing_spot_effect_summary
                (spot_id, stress_participant_count, stress_valid_session_count, stress_reduction_rate,
                 emotional_participant_count, emotional_valid_session_count, emotional_increase_rate)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var row : source.overall()) {
                statement.setLong(1, mapping.spots().get(row.spot()));
                statement.setInt(2, row.stressParticipants());
                statement.setInt(3, row.stressSessions());
                statement.setBigDecimal(4, row.stressRate());
                statement.setInt(5, row.emotionalParticipants());
                statement.setInt(6, row.emotionalSessions());
                statement.setBigDecimal(7, row.emotionalRate());
                statement.executeUpdate();
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into member_healing_spot_effect_summary
                (member_id, spot_id, stress_valid_session_count, stress_reduction_rate,
                 emotional_valid_session_count, emotional_increase_rate)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            for (var row : source.participants()) {
                statement.setLong(1, mapping.members().get(row.participantNo()));
                statement.setLong(2, mapping.spots().get(row.spot()));
                statement.setInt(3, row.stressSessions());
                statement.setBigDecimal(4, row.stressRate());
                statement.setInt(5, row.emotionalSessions());
                statement.setBigDecimal(6, row.emotionalRate());
                statement.executeUpdate();
            }
        }
        try (var statement = connection.prepareStatement("""
                insert into healing_effect_import_batch
                (source_sha256, source_filename, imported_at, overall_summary_count, participant_summary_count, target_site_id)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, source.sha256());
            statement.setString(2, source.filename());
            statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
            statement.setInt(4, source.overall().size());
            statement.setInt(5, source.participants().size());
            statement.setLong(6, siteId);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rows.next())
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private long count(Connection connection, String table) throws SQLException {
        // Table names originate exclusively in TABLES, never from CLI/source input.
        try (var statement = connection.prepareStatement("select count(*) from " + table);
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private EffectImportValidationException invalid(String message) {
        return new EffectImportValidationException(List.of(message));
    }
}
