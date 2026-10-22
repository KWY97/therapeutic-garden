package com.example.manage.personaleeg;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit, transaction-owned importer. It never updates legacy raw or summary tables. */
public final class PersonalEegDatabaseImporter {
    public static final List<String> TABLES = List.of("personal_eeg_analysis_batch", "personal_eeg_analysis_session",
            "personal_eeg_spot_result", "personal_eeg_active_version");
    public record Result(String status, int sessions, int results, int complete, int missing,
                         int correctedDates, boolean schemaReady) {}

    public Result execute(Connection connection, PersonalEegSource source, long siteId,
                          String versionLabel, boolean dryRun) throws SQLException {
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("새 auto-commit connection 필요");
        if (siteId <= 0 || versionLabel == null || !versionLabel.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Site/버전 식별자 오류");
        connection.setReadOnly(dryRun);
        connection.setAutoCommit(false);
        try {
            if (dryRun && "MySQL".equals(connection.getMetaData().getDatabaseProductName()))
                try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
            boolean ready = schemaReady(connection);
            if (!dryRun && !ready) throw new IllegalArgumentException("개인 EEG 스키마 준비 필요");
            var mapping = mapTarget(connection, source, siteId);
            if (ready) {
                try (var statement = connection.prepareStatement(
                        "select version_label, target_site_id from personal_eeg_analysis_batch where source_sha256=?")) {
                    statement.setString(1, source.sha256());
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            if (!versionLabel.equals(rows.getString(1)) || siteId != rows.getLong(2))
                                throw new IllegalArgumentException("동일 SHA의 버전/Site 불일치");
                            connection.rollback();
                            return counts("ALREADY_IMPORTED", source, true);
                        }
                    }
                }
                try (var statement = connection.prepareStatement(
                        "select count(*) from personal_eeg_analysis_batch where version_label=?")) {
                    statement.setString(1, versionLabel);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) != 0) throw new IllegalArgumentException("이미 사용 중인 분석 버전");
                    }
                }
            }
            if (dryRun) {
                connection.rollback();
                return counts("DRY_RUN", source, ready);
            }
            long batchId = insertBatch(connection, source, siteId, versionLabel);
            for (var session : source.sessions()) {
                long sessionId = insertSession(connection, batchId, session, mapping);
                for (var spot : session.spots()) insertSpot(connection, sessionId, spot, mapping);
            }
            connection.commit();
            return counts("IMPORTED_DRAFT", source, true);
        } catch (SQLException | RuntimeException ex) {
            try { connection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
            throw ex;
        }
    }

    public static boolean schemaReady(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return names.containsAll(TABLES);
    }
    private record Mapping(Map<Integer,Long> members, Map<String,Long> courses, Map<String,Long> spots) {}
    private static Mapping mapTarget(Connection c, PersonalEegSource source, long siteId) throws SQLException {
        try (var s=c.prepareStatement("select count(*) from site where site_id=?")) {
            s.setLong(1,siteId); try(var rows=s.executeQuery()) { rows.next(); if(rows.getLong(1)!=1) throw new IllegalArgumentException("Site 없음"); }
        }
        var members=new HashMap<Integer,Long>();
        try(var s=c.createStatement(); var rows=s.executeQuery("select member_id, participant_no from member")) {
            while(rows.next()) if(members.putIfAbsent(rows.getInt(2),rows.getLong(1))!=null)
                throw new IllegalArgumentException("참가자 번호 중복");
        }
        var courses=new HashMap<String,Long>();
        try(var s=c.prepareStatement("select course_id,code from healing_course where site_id=?")) {
            s.setLong(1,siteId); try(var rows=s.executeQuery()) {
                while(rows.next()) if(courses.putIfAbsent(rows.getString(2),rows.getLong(1))!=null)
                    throw new IllegalArgumentException("코스 코드 중복");
            }
        }
        var spots=new HashMap<String,Long>();
        try(var s=c.prepareStatement("select h.spot_id,h.code,c.code from healing_spot h join healing_course c on c.course_id=h.course_id where c.site_id=?")) {
            s.setLong(1,siteId); try(var rows=s.executeQuery()) {
                while(rows.next()) {
                    String code=rows.getString(2), course=rows.getString(3);
                    if (code.matches("HS[1-6]")) {
                        if (!course.equals("HC-"+(char)('A'+(Integer.parseInt(code.substring(2))-1)/2))
                                || spots.putIfAbsent(code,rows.getLong(1))!=null)
                            throw new IllegalArgumentException("HS/코스 매핑 불일치");
                    }
                }
            }
        }
        if(!spots.keySet().equals(Set.of("HS1","HS2","HS3","HS4","HS5","HS6")))
            throw new IllegalArgumentException("대상 Site의 HS1~HS6 매핑 누락");
        for(var session:source.sessions()) {
            if(!members.containsKey(session.participantNo()) || !courses.containsKey(session.courseCode()))
                throw new IllegalArgumentException("참가자/코스 매핑 누락");
            for(var spot:session.spots()) if(!spots.containsKey(spot.code()))
                throw new IllegalArgumentException("HS 매핑 누락");
        }
        return new Mapping(members,courses,spots);
    }
    private static long insertBatch(Connection c, PersonalEegSource source, long siteId, String version) throws SQLException {
        try(var s=c.prepareStatement("""
                insert into personal_eeg_analysis_batch
                (source_sha256,source_filename,version_label,target_site_id,imported_at,session_count,result_count,complete_count,post_missing_count)
                values (?,?,?,?,?,?,?,?,?)
                """,Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,source.sha256()); s.setString(2,source.filename()); s.setString(3,version);
            s.setLong(4,siteId); s.setTimestamp(5,Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
            s.setInt(6,source.sessions().size()); s.setInt(7,source.resultCount()); s.setInt(8,source.completeCount());
            s.setInt(9,source.postMissingCount()); s.executeUpdate();
            return key(s);
        }
    }
    private static long insertSession(Connection c,long batch,PersonalEegSource.Session row,Mapping m) throws SQLException {
        try(var s=c.prepareStatement("""
                insert into personal_eeg_analysis_session
                (batch_id,source_row,member_id,course_id,source_date,confirmed_date)
                values (?,?,?,?,?,?)
                """,Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1,batch); s.setInt(2,row.sourceRow()); s.setLong(3,m.members().get(row.participantNo()));
            s.setLong(4,m.courses().get(row.courseCode())); s.setObject(5,row.sourceDate());
            s.setObject(6,row.confirmedDate()); s.executeUpdate();
            return key(s);
        }
    }
    private static void insertSpot(Connection c,long session,PersonalEegSource.Spot row,Mapping m) throws SQLException {
        try(var s=c.prepareStatement("""
                insert into personal_eeg_spot_result
                (session_id,spot_id,baseline_stress,stress_post,baseline_emotional,emotional_post)
                values (?,?,?,?,?,?)
                """)) {
            s.setLong(1,session); s.setLong(2,m.spots().get(row.code()));
            s.setBigDecimal(3,row.baselineStress()); s.setBigDecimal(4,row.stressPost());
            s.setBigDecimal(5,row.baselineEmotional()); s.setBigDecimal(6,row.emotionalPost());
            s.executeUpdate();
        }
    }
    private static long key(PreparedStatement statement) throws SQLException {
        try(var rows=statement.getGeneratedKeys()) { if(!rows.next()) throw new SQLException("generated key 없음"); return rows.getLong(1); }
    }
    private static Result counts(String status,PersonalEegSource source,boolean ready) {
        return new Result(status,source.sessions().size(),source.resultCount(),source.completeCount(),
                source.postMissingCount(),source.correctedDateCount(),ready);
    }
}
