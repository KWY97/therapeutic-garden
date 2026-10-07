package com.example.manage.healingeffect;

import java.sql.*;
import java.util.*;

/** Shared fail-closed target and rollout-data checks for explicit Production CLIs. */
public final class ProductionDatabaseTargetGuard {
    public static final String JDBC_URL = "HEALING_PROD_JDBC_URL";
    public static final String DB_USER = "HEALING_PROD_DB_USER";
    public static final String DB_PASSWORD = "HEALING_PROD_DB_PASSWORD";
    public static final String EXPECTED_DATABASE = "HEALING_PROD_EXPECTED_DATABASE";
    public static final String EXPECTED_SITE_NAME = "HEALING_PROD_EXPECTED_SITE_NAME";
    public static final String EXPECTED_SOURCE_SHA256 = "HEALING_PROD_EXPECTED_SOURCE_SHA256";
    public static final String TARGET_LABEL = "HEALING_PROD_TARGET_LABEL";
    public static final String EXPECTED_RAW_SESSION_COUNT = "HEALING_PROD_EXPECTED_RAW_SESSION_COUNT";
    public static final String EXPECTED_RAW_MEASUREMENT_COUNT = "HEALING_PROD_EXPECTED_RAW_MEASUREMENT_COUNT";
    public static final String EXPECTED_OVERALL_COUNT = "HEALING_PROD_EXPECTED_OVERALL_COUNT";
    public static final String EXPECTED_MEMBER_SPOT_COUNT = "HEALING_PROD_EXPECTED_MEMBER_SPOT_COUNT";
    public static final String EXPECTED_PARTICIPANT_OVERALL_COUNT =
            "HEALING_PROD_EXPECTED_PARTICIPANT_OVERALL_COUNT";

    public record Config(String jdbcUrl, String user, String password, String expectedDatabase,
            String expectedSiteName, String expectedSourceSha256, String targetLabel) {
        @Override public String toString() {
            return "Config[targetLabel=" + targetLabel + ", expectedDatabase=" + expectedDatabase
                    + ", expectedSiteName=" + expectedSiteName + ", expectedSourceSha256="
                    + expectedSourceSha256 + ", credentials=[REDACTED]]";
        }
    }
    public record RawExpectations(int sessions, int measurements, int overall, int memberSpots) {}
    public record RebuildExpectations(int sessions, int measurements, int overall,
            int memberSpots, int participantOverall) {}
    public record RawState(int batches, int sessions, int measurements, String sourceSha256,
            Long targetSiteId, Integer declaredSessions, Integer declaredMeasurements) {
        public boolean empty() { return batches == 0 && sessions == 0 && measurements == 0; }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(Config config) throws SQLException;
    }

    public Config config(Map<String, String> environment) {
        String url = required(environment, JDBC_URL);
        if (!validRemoteMysql(url))
            throw invalid("Production remote MySQL JDBC URL이 필요합니다.");
        String database = required(environment, EXPECTED_DATABASE);
        if (!database.matches("[A-Za-z0-9_]+")) throw invalid("expected database 형식 오류");
        String sha = required(environment, EXPECTED_SOURCE_SHA256);
        validateSha(sha, "expected source SHA");
        String label = required(environment, TARGET_LABEL);
        if (!label.matches("[A-Za-z0-9._-]{1,100}")) throw invalid("target label 형식 오류");
        String siteName = required(environment, EXPECTED_SITE_NAME);
        if (siteName.chars().anyMatch(Character::isISOControl)) throw invalid("expected Site name 형식 오류");
        return new Config(url, required(environment, DB_USER), required(environment, DB_PASSWORD),
                database, siteName, sha, label);
    }

    public RawExpectations rawExpectations(Map<String, String> environment) {
        return new RawExpectations(positive(environment, EXPECTED_RAW_SESSION_COUNT),
                positive(environment, EXPECTED_RAW_MEASUREMENT_COUNT),
                positive(environment, EXPECTED_OVERALL_COUNT),
                positive(environment, EXPECTED_MEMBER_SPOT_COUNT));
    }

    public RebuildExpectations rebuildExpectations(Map<String, String> environment) {
        return new RebuildExpectations(positive(environment, EXPECTED_RAW_SESSION_COUNT),
                positive(environment, EXPECTED_RAW_MEASUREMENT_COUNT),
                positive(environment, EXPECTED_OVERALL_COUNT),
                positive(environment, EXPECTED_MEMBER_SPOT_COUNT),
                positive(environment, EXPECTED_PARTICIPANT_OVERALL_COUNT));
    }

    public void validateTarget(Connection connection, Config config, long siteId) throws SQLException {
        if (siteId <= 0) throw invalid("Site ID 오류");
        try (var statement = connection.prepareStatement("select database()");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !config.expectedDatabase().equals(rows.getString(1)) || rows.next())
                throw invalid("실제 database가 expected database와 일치하지 않습니다.");
        }
        try (var statement = connection.prepareStatement("select name from site where site_id=?")) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !config.expectedSiteName().equals(rows.getString(1)) || rows.next())
                    throw invalid("대상 Site ID/name이 expected Site와 일치하지 않습니다.");
            }
        }
    }

    public void requireRawSchema(Connection connection) throws SQLException {
        if (!RawMeasurementDatabaseImporter.schemaReady(connection))
            throw invalid("Raw measurement schema 준비 필요");
    }

    public RawState rawState(Connection connection) throws SQLException {
        requireRawSchema(connection);
        int sessions = count(connection, "healing_measurement_session");
        int measurements = count(connection, "healing_spot_measurement");
        try (var statement = connection.prepareStatement("""
                select source_sha256, target_site_id, session_count, measurement_count
                from healing_measurement_import_batch order by id
                """); var rows = statement.executeQuery()) {
            int batches = 0;
            String sha = null;
            Long siteId = null;
            Integer declaredSessions = null;
            Integer declaredMeasurements = null;
            while (rows.next()) {
                batches++;
                if (batches == 1) {
                    sha = rows.getString(1);
                    siteId = rows.getLong(2);
                    declaredSessions = rows.getInt(3);
                    declaredMeasurements = rows.getInt(4);
                }
            }
            return new RawState(batches, sessions, measurements, sha, siteId,
                    declaredSessions, declaredMeasurements);
        }
    }

    /** Allows only an empty first-import state or a complete idempotent import of the same source. */
    public void validateInitialOrSameRaw(RawState state, Config config, long siteId,
            int expectedSessions, int expectedMeasurements) {
        if (state.empty()) return;
        boolean sameComplete = state.batches() == 1
                && config.expectedSourceSha256().equals(state.sourceSha256())
                && Objects.equals(siteId, state.targetSiteId())
                && Objects.equals(expectedSessions, state.declaredSessions())
                && Objects.equals(expectedMeasurements, state.declaredMeasurements())
                && state.sessions() == expectedSessions
                && state.measurements() == expectedMeasurements;
        if (!sameComplete)
            throw invalid("Raw 데이터가 empty 또는 동일 source의 완전한 import 상태가 아닙니다.");
    }

    public void validateStoredRaw(RawState state, Config config, long siteId,
            int expectedSessions, int expectedMeasurements) {
        boolean complete = state.batches() == 1
                && config.expectedSourceSha256().equals(state.sourceSha256())
                && Objects.equals(siteId, state.targetSiteId())
                && Objects.equals(expectedSessions, state.declaredSessions())
                && Objects.equals(expectedMeasurements, state.declaredMeasurements())
                && state.sessions() == expectedSessions
                && state.measurements() == expectedMeasurements;
        if (!complete) throw invalid("단일 expected Raw import batch/count/SHA가 필요합니다.");
    }

    public void validateSummaryCounts(Connection connection, long siteId,
            int expectedOverall, int expectedMemberSpots) throws SQLException {
        int overall = scopedCount(connection, """
                select count(*) from healing_spot_effect_summary e
                join healing_spot s on s.spot_id=e.spot_id
                join healing_course c on c.course_id=s.course_id where c.site_id=?
                """, siteId);
        int memberSpots = scopedCount(connection, """
                select count(*) from member_healing_spot_effect_summary e
                join healing_spot s on s.spot_id=e.spot_id
                join healing_course c on c.course_id=s.course_id where c.site_id=?
                """, siteId);
        if (overall != expectedOverall || memberSpots != expectedMemberSpots)
            throw invalid("기존 Summary row coverage가 expected count와 일치하지 않습니다.");
    }

    public static void validateSha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(label + " 형식 오류");
    }

    private static boolean validRemoteMysql(String url) {
        if (!url.startsWith("jdbc:mysql://") || url.chars().anyMatch(Character::isWhitespace)) return false;
        String remainder = url.substring("jdbc:mysql://".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) return false;
        String authority = remainder.substring(0, slash).toLowerCase(Locale.ROOT);
        String database = remainder.substring(slash + 1).split("\\?", 2)[0];
        String lower = url.toLowerCase(Locale.ROOT);
        if (authority.contains("@") || lower.contains("user=") || lower.contains("password=")
                || !database.matches("[A-Za-z0-9_]+")) return false;
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return false;
            host = authority.substring(0, close + 1);
        } else {
            host = authority.split(":", 2)[0];
        }
        return !host.isBlank() && !host.equals("localhost") && !host.equals("127.0.0.1")
                && !host.equals("[::1]");
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw invalid("환경변수 필요: " + key);
        return value;
    }

    private static int positive(Map<String, String> environment, String key) {
        try {
            int value = Integer.parseInt(required(environment, key));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw invalid("양의 정수 환경변수 필요: " + key);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("select count(*) from " + table);
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static int scopedCount(Connection connection, String sql, long siteId) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, siteId);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
