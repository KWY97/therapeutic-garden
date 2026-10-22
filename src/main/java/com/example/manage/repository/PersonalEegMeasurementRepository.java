package com.example.manage.repository;

import com.example.manage.domain.HealingMeasurementRecord;
import com.example.manage.personaleeg.PersonalEegDatabaseImporter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** This read path is private to dated personal history; no Summary query uses these tables. */
@Repository
@RequiredArgsConstructor
public class PersonalEegMeasurementRepository {
    private final JdbcTemplate jdbc;

    public record ActiveHistory(long siteId, List<HealingMeasurementRecord> rows) {}

    /** Empty Optional means no activated version, so callers may use the legacy history. */
    public Optional<ActiveHistory> findActiveMember(Long memberId) {
        boolean ready = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                PersonalEegDatabaseImporter::schemaReady));
        if (!ready) return Optional.empty();
        var active = jdbc.query("""
                select b.id, b.target_site_id
                from personal_eeg_active_version a
                join personal_eeg_analysis_batch b on b.id=a.batch_id
                where a.id=1
                """, (rs,n) -> new long[]{rs.getLong(1),rs.getLong(2)});
        if (active.isEmpty()) return Optional.empty();
        long batchId=active.getFirst()[0], siteId=active.getFirst()[1];
        var rows=jdbc.query("""
                select x.member_id, p.spot_id, x.confirmed_date, c.code,
                       p.baseline_stress, p.stress_post, p.baseline_emotional, p.emotional_post
                from personal_eeg_analysis_session x
                join personal_eeg_spot_result p on p.session_id=x.id
                join healing_course c on c.course_id=x.course_id
                join healing_spot h on h.spot_id=p.spot_id
                where x.batch_id=? and x.member_id=? and c.site_id=? and h.course_id=x.course_id
                order by x.confirmed_date, x.source_row, p.spot_id
                """, (r,n) -> new HealingMeasurementRecord(r.getLong(1),r.getLong(2),
                        r.getDate(3).toLocalDate(),r.getString(4),r.getBigDecimal(5),r.getBigDecimal(6),
                        r.getBigDecimal(7),r.getBigDecimal(8)),batchId,memberId,siteId);
        return Optional.of(new ActiveHistory(siteId,rows));
    }
}
