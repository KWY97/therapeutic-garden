package com.example.manage.healingeffect;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit JDBC utility: no schema creation, startup hook, or Summary writes. */
public final class RawMeasurementDatabaseImporter {
    public static final List<String> TABLES = List.of("healing_measurement_import_batch", "healing_measurement_session", "healing_spot_measurement");
    public record Result(String status, boolean schemaReady, int sessions, int measurements) {
    }

    public static boolean schemaReady(Connection c) throws SQLException {
        Set<String> present = new HashSet<>();
        try (var rows = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rows.next())
                present.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return present.containsAll(TABLES);
    }

    public Result execute(Connection c, RawMeasurementSource source, long siteId, boolean dryRun) throws SQLException {
        if (!c.getAutoCommit())
            throw new IllegalArgumentException("새 connection 필요");
        c.setReadOnly(dryRun);
        c.setAutoCommit(false);
        try {
            if (dryRun && c.getMetaData().getDatabaseProductName().equals("MySQL"))
                try (var s = c.createStatement()) {
                    s.execute("SET TRANSACTION READ ONLY");
                }
            if (!dryRun)
                try (var s = c.createStatement(); var rows = s.executeQuery("select site_id from site order by site_id for update")) {
                    while (rows.next()) { /* Same lock as existing importer / Site membership edits. */ }
                }
            var members = new HashMap<Integer, Long>();
            try (var s = c.createStatement(); var rows = s.executeQuery("select member_id, participant_no from member order by member_id" + (dryRun ? "" : " for update"))) {
                while (rows.next())
                    if (members.put(rows.getInt(2), rows.getLong(1)) != null)
                        throw invalid("중복 Participant mapping");
            }
            var courses = new HashMap<String, Long>();
            var spots = new HashMap<String, Long>();
            try (var s = c.prepareStatement("select course_id, code from healing_course where site_id = ?")) {
                s.setLong(1, siteId);
                try (var rows = s.executeQuery()) {
                    while (rows.next())
                        if (courses.put(rows.getString(2), rows.getLong(1)) != null)
                            throw invalid("중복 Course mapping");
                }
            }
            try (var s = c.prepareStatement("select s.spot_id, s.code, s.name, h.code from healing_spot s join healing_course h on h.course_id=s.course_id where h.site_id=?")) {
                s.setLong(1, siteId);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        String code = rows.getString(2);
                        int index = HealingEffectExcelParser.CODES.indexOf(code);
                        if (index < 0)
                            continue;
                        if (!HealingEffectExcelParser.NAMES.get(index).equals(rows.getString(3)) || !("HC-" + (char) ('A' + index / 2)).equals(rows.getString(4)))
                            throw invalid("Spot name/Course mapping 불일치");
                        if (spots.put(code, rows.getLong(1)) != null)
                            throw invalid("중복 Spot mapping");
                    }
                }
            }
            for (var row : source.sessions()) {
                if (!members.containsKey(row.participantNo()) || !courses.containsKey(row.courseCode()))
                    throw invalid("Member/Course mapping 누락");
                for (var spot : row.spots())
                    if (!spots.containsKey(spot.code()))
                        throw invalid("Spot mapping 누락");
            }
            boolean ready = schemaReady(c);
            if (ready) {
                try (var s = c.prepareStatement("select target_site_id from healing_measurement_import_batch where source_sha256=?")) {
                    s.setString(1, source.sha256());
                    try (var rows = s.executeQuery()) {
                        if (rows.next()) {
                            if (rows.getLong(1) != siteId)
                                throw invalid("동일 source 다른 Site import 거부");
                            c.rollback();
                            return new Result("ALREADY_IMPORTED", true, 0, 0);
                        }
                    }
                }
                for (String table : TABLES)
                    try (var s = c.createStatement(); var rows = s.executeQuery("select count(*) from " + table)) {
                        rows.next();
                        if (rows.getLong(1) > 0)
                            throw invalid("기존 raw 데이터 존재: overwrite/다른 source 자동 추가 거부");
                    }
            }
            if (dryRun) {
                c.rollback();
                return new Result("DRY_RUN", ready, source.sessions().size(), source.measurementCount());
            }
            if (!ready)
                throw invalid("Raw schema 준비 필요");
            long batch;
            try (var s = c.prepareStatement("insert into healing_measurement_import_batch (source_sha256,source_filename,target_site_id,imported_at,session_count,measurement_count) values (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, source.sha256());
                s.setString(2, source.filename());
                s.setLong(3, siteId);
                s.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
                s.setInt(5, source.sessions().size());
                s.setInt(6, source.measurementCount());
                s.executeUpdate();
                batch = key(s);
            }
            for (var row : source.sessions()) {
                long session;
                try (var s = c.prepareStatement("insert into healing_measurement_session (batch_id,source_row,member_id,course_id,measurement_date,experience,baseline_stress,baseline_emotional) values (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    s.setLong(1, batch);
                    s.setInt(2, row.sourceRow());
                    s.setLong(3, members.get(row.participantNo()));
                    s.setLong(4, courses.get(row.courseCode()));
                    s.setObject(5, row.date());
                    s.setString(6, row.experience());
                    s.setBigDecimal(7, row.baselineStress());
                    s.setBigDecimal(8, row.baselineEmotional());
                    s.executeUpdate();
                    session = key(s);
                }
                for (var spot : row.spots())
                    try (var s = c.prepareStatement("insert into healing_spot_measurement (session_id,spot_id,stress_post,emotional_post) values (?,?,?,?)")) {
                        s.setLong(1, session);
                        s.setLong(2, spots.get(spot.code()));
                        s.setBigDecimal(3, spot.stress());
                        s.setBigDecimal(4, spot.emotional());
                        s.executeUpdate();
                    }
            }
            c.commit();
            return new Result("IMPORTED", true, source.sessions().size(), source.measurementCount());
        } catch (SQLException | RuntimeException ex) {
            c.rollback();
            throw ex;
        }
    }

    private static long key(PreparedStatement s) throws SQLException {
        try (var rows = s.getGeneratedKeys()) {
            if (!rows.next())
                throw new SQLException("Generated key missing");
            return rows.getLong(1);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
