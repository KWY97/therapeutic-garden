package com.example.manage.personaleeg;

import java.sql.*;

/** Explicit local/test activation primitive. Imports never call it. */
public final class PersonalEegVersionStore {
    public void activate(Connection c, long batchId) throws SQLException {
        if (!c.getAutoCommit()) throw new IllegalArgumentException("새 connection 필요");
        if (batchId <= 0) throw new IllegalArgumentException("배치 ID 오류");
        c.setAutoCommit(false);
        try {
            int expectedSessions, expectedResults, expectedComplete, expectedMissing;
            try(var s=c.prepareStatement("select session_count,result_count,complete_count,post_missing_count from personal_eeg_analysis_batch where id=?")) {
                s.setLong(1,batchId); try(var r=s.executeQuery()) {
                    if(!r.next()) throw new IllegalArgumentException("배치 없음");
                    expectedSessions=r.getInt(1); expectedResults=r.getInt(2);
                    expectedComplete=r.getInt(3); expectedMissing=r.getInt(4);
                }
            }
            if (expectedResults != expectedSessions * 2 || expectedComplete + expectedMissing != expectedResults)
                throw new IllegalArgumentException("배치 집계 불일치");
            requireCount(c, "select count(*) from personal_eeg_analysis_session where batch_id=?",
                    batchId, expectedSessions, "세션 건수 불일치");
            String resultFrom = " from personal_eeg_spot_result p join personal_eeg_analysis_session x on x.id=p.session_id";
            requireCount(c, "select count(*)" + resultFrom + " where x.batch_id=?",
                    batchId, expectedResults, "HS 결과 건수 불일치");
            requireCount(c, "select count(*)" + resultFrom
                    + " where x.batch_id=? and p.stress_post is null and p.emotional_post is null",
                    batchId, expectedMissing, "Post 결측 건수 불일치");
            requireCount(c, "select count(*)" + resultFrom
                    + " where x.batch_id=? and p.stress_post is not null and p.emotional_post is not null",
                    batchId, expectedComplete, "완비 결과 건수 불일치");
            requireCount(c, "select count(*)" + resultFrom
                    + " where x.batch_id=? and ((p.stress_post is null and p.emotional_post is not null)"
                    + " or (p.stress_post is not null and p.emotional_post is null))",
                    batchId, 0, "Post 지표 쌍 불일치");
            requireCount(c, "select count(*) from personal_eeg_analysis_session x"
                    + " where x.batch_id=? and (select count(*) from personal_eeg_spot_result p where p.session_id=x.id)<>2",
                    batchId, 0, "세션별 HS 결과 건수 불일치");
            requireCount(c, "select count(*)" + resultFrom
                    + " join healing_spot h on h.spot_id=p.spot_id where x.batch_id=? and h.course_id<>x.course_id",
                    batchId, 0, "HS/코스 매핑 불일치");
            requireCount(c, "select count(*) from personal_eeg_analysis_session x"
                    + " join member m on m.member_id=x.member_id"
                    + " join healing_course hc on hc.course_id=x.course_id"
                    + " where x.batch_id=? and x.source_date<>x.confirmed_date"
                    + " and not (m.participant_no=8 and hc.code='HC-B'"
                    + " and x.source_date='2026-01-26' and x.confirmed_date='2026-09-04')",
                    batchId, 0, "허용되지 않은 날짜 정정");
            int changed;
            try(var s=c.prepareStatement("update personal_eeg_active_version set batch_id=?,activated_at=current_timestamp where id=1")) {
                s.setLong(1,batchId); changed=s.executeUpdate();
            }
            if(changed==0) try(var s=c.prepareStatement("insert into personal_eeg_active_version (id,batch_id,activated_at) values (1,?,current_timestamp)")) {
                s.setLong(1,batchId); s.executeUpdate();
            }
            c.commit();
        } catch(SQLException | RuntimeException ex) { c.rollback(); throw ex; }
    }
    public void returnToLegacy(Connection c) throws SQLException {
        if(!c.getAutoCommit()) throw new IllegalArgumentException("새 connection 필요");
        c.setAutoCommit(false);
        try(var s=c.createStatement()) {
            s.executeUpdate("update personal_eeg_active_version set batch_id=null,activated_at=current_timestamp where id=1");
            c.commit();
        } catch(SQLException | RuntimeException ex) { c.rollback(); throw ex; }
    }
    private static void requireCount(Connection c, String sql, long batchId, int expected,
                                     String error) throws SQLException {
        try(var s=c.prepareStatement(sql)) {
            s.setLong(1,batchId);
            try(var r=s.executeQuery()) {
                r.next();
                if(r.getLong(1)!=expected) throw new IllegalArgumentException(error);
            }
        }
    }
}
