package com.example.manage.personaleeg;

import java.sql.*;
import java.util.*;

/** Read-only batch and active-pointer inspection shared by activation paths. */
public final class PersonalEegBatchIntegrity {
    public record ActiveVersion(Long batchId, String versionLabel) {
        public boolean legacy() {
            return batchId == null;
        }
    }

    public record BatchStatus(long batchId, String versionLabel, String sourceSha256, long siteId,
            int declaredSessions, int declaredResults, int declaredComplete, int declaredMissing,
            int actualSessions, int actualResults, int actualComplete, int actualMissing,
            List<String> issues) {
        public BatchStatus {
            issues = List.copyOf(issues);
        }

        public boolean valid() {
            return issues.isEmpty();
        }
    }

    public ActiveVersion active(Connection connection) throws SQLException {
        return active(connection, false);
    }

    ActiveVersion activeForUpdate(Connection connection) throws SQLException {
        return active(connection, true);
    }

    public List<BatchStatus> list(Connection connection) throws SQLException {
        var result = new ArrayList<BatchStatus>();
        try (var statement = connection.prepareStatement("""
                select id,version_label,source_sha256,target_site_id,
                       session_count,result_count,complete_count,post_missing_count
                  from personal_eeg_analysis_batch order by id
                """); var rows = statement.executeQuery()) {
            while (rows.next())
                result.add(inspect(connection, rows.getLong(1), rows.getString(2),
                    rows.getString(3), rows.getLong(4), rows.getInt(5), rows.getInt(6),
                    rows.getInt(7), rows.getInt(8)));
        }
        return List.copyOf(result);
    }

    public BatchStatus requireValid(Connection connection, long batchId, Long expectedSiteId)
            throws SQLException {
        BatchStatus status = list(connection).stream().filter(row -> row.batchId() == batchId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("배치 없음: " + batchId));
        if (expectedSiteId != null && status.siteId() != expectedSiteId)
            throw new IllegalArgumentException("배치 Site가 expected Site와 일치하지 않습니다.");
        if (!status.valid()) throw new IllegalArgumentException(
                "배치 무결성 불일치: " + String.join(",", status.issues()));
        return status;
    }

    private ActiveVersion active(Connection connection, boolean lock) throws SQLException {
        if (lock) {
            try (var statement = connection.prepareStatement(
                    "select batch_id from personal_eeg_active_version where id=1 for update");
                 var rows = statement.executeQuery()) {
                if (!rows.next()) return new ActiveVersion(null, null);
                long value = rows.getLong(1);
                Long batchId = rows.wasNull() ? null : value;
                if (rows.next()) throw new IllegalArgumentException("활성 버전 singleton 불일치");
                return batchId == null ? new ActiveVersion(null, null)
                        : new ActiveVersion(batchId, versionLabel(connection, batchId));
            }
        }
        String sql = "select a.batch_id,b.version_label from personal_eeg_active_version a "
                + "left join personal_eeg_analysis_batch b on b.id=a.batch_id where a.id=1";
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            if (!rows.next()) return new ActiveVersion(null, null);
            long value = rows.getLong(1);
            Long batchId = rows.wasNull() ? null : value;
            String version = rows.getString(2);
            if (rows.next()) throw new IllegalArgumentException("활성 버전 singleton 불일치");
            if (batchId != null && version == null)
                throw new IllegalArgumentException("활성 배치 참조 불일치");
            return new ActiveVersion(batchId, version);
        }
    }

    private static String versionLabel(Connection connection, long batchId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select version_label from personal_eeg_analysis_batch where id=?")) {
            statement.setLong(1, batchId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("활성 배치 참조 불일치");
                String version = rows.getString(1);
                if (version == null || rows.next()) throw new IllegalArgumentException("활성 배치 참조 불일치");
                return version;
            }
        }
    }

    private BatchStatus inspect(Connection connection, long batchId, String version, String sha,
            long siteId, int sessions, int results, int complete, int missing) throws SQLException {
        String resultFrom = " from personal_eeg_spot_result p "
                + "join personal_eeg_analysis_session x on x.id=p.session_id ";
        int actualSessions = count(connection,
                "select count(*) from personal_eeg_analysis_session where batch_id=?", batchId);
        int actualResults = count(connection,
                "select count(*)" + resultFrom + "where x.batch_id=?", batchId);
        int actualMissing = count(connection, "select count(*)" + resultFrom
                + "where x.batch_id=? and p.stress_post is null and p.emotional_post is null", batchId);
        int actualComplete = count(connection, "select count(*)" + resultFrom
                + "where x.batch_id=? and p.stress_post is not null and p.emotional_post is not null", batchId);
        int pairMismatch = count(connection, "select count(*)" + resultFrom
                + "where x.batch_id=? and ((p.stress_post is null and p.emotional_post is not null)"
                + " or (p.stress_post is not null and p.emotional_post is null))", batchId);
        int wrongSpotCount = count(connection, "select count(*) from personal_eeg_analysis_session x"
                + " where x.batch_id=? and (select count(*) from personal_eeg_spot_result p"
                + " where p.session_id=x.id)<>2", batchId);
        int courseMismatch = count(connection, "select count(*)" + resultFrom
                + "join healing_spot h on h.spot_id=p.spot_id"
                + " where x.batch_id=? and h.course_id<>x.course_id", batchId);
        int invalidDateCorrection = count(connection, "select count(*) from personal_eeg_analysis_session x"
                + " join member m on m.member_id=x.member_id"
                + " join healing_course hc on hc.course_id=x.course_id"
                + " where x.batch_id=? and x.source_date<>x.confirmed_date"
                + " and not (m.participant_no=8 and hc.code='HC-B'"
                + " and x.source_date='2026-01-26' and x.confirmed_date='2026-09-04')", batchId);

        var issues = new ArrayList<String>();
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) issues.add("version");
        if (sha == null || !sha.matches("[0-9a-f]{64}")) issues.add("sha256");
        if (results != sessions * 2) issues.add("declared-results");
        if (complete + missing != results) issues.add("declared-completeness");
        if (actualSessions != sessions) issues.add("session-count");
        if (actualResults != results) issues.add("result-count");
        if (actualComplete != complete) issues.add("complete-count");
        if (actualMissing != missing) issues.add("missing-count");
        if (pairMismatch != 0) issues.add("post-pair");
        if (wrongSpotCount != 0) issues.add("spots-per-session");
        if (courseMismatch != 0) issues.add("spot-course");
        if (invalidDateCorrection != 0) issues.add("date-correction");
        return new BatchStatus(batchId, version, sha, siteId, sessions, results, complete, missing,
                actualSessions, actualResults, actualComplete, actualMissing, issues);
    }

    private static int count(Connection connection, String sql, long batchId) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, batchId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
