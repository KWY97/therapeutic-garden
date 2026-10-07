package com.example.manage.healingeffect;

import com.example.manage.domain.*;
import com.example.manage.dto.HealingEffectView;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Importer uses independent committed JDBC transactions against Hibernate's actual entity schema. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:effect-test;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealingEffectIntegrationTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired MemberRepository members;
    @Autowired HealingSpotEffectSummaryRepository overall;
    @Autowired MemberHealingSpotEffectSummaryRepository participants;
    @Autowired HealingEffectImportBatchRepository batches;
    @Autowired HealingEffectQueryService query;
    @Autowired MemberService memberService;
    @Autowired HealingSpotService spotService;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @TempDir Path directory;
    Site site;
    Member member;
    List<HealingSpot> spotList;
    HealingEffectSource source;

    @BeforeEach void prepare() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            scheduleSpots.deleteAll(); schedules.deleteAll();
            participants.deleteAll(); overall.deleteAll(); batches.deleteAll();
            spots.deleteAll(); courses.deleteAll(); sites.deleteAll(); members.deleteAll();
            members.flush();
            site = sites.save(new Site("synthetic effect site", "test", 37.0, 127.0, 3));
            member = members.save(new Member(4, 1, "synthetic-member", "unused"));
            spotList = new ArrayList<>();
            var names = List.of("회복 코스", "감각 코스", "힐링 코스");
            for (int c = 0; c < 3; c++) {
                var course = courses.save(new HealingCourse(site, "HC-" + (char) ('A' + c), names.get(c), null, null, null));
                for (int i = c * 2; i < c * 2 + 2; i++)
                    spotList.add(spots.save(new HealingSpot(course, HealingEffectExcelParser.CODES.get(i),
                            HealingEffectExcelParser.NAMES.get(i), 37.0, 127.0)));
            }
        });
        source = new HealingEffectExcelParser().parse(SyntheticEffectWorkbook.create(directory, b -> {}));
    }

    HealingEffectDatabaseImporter.Result run(HealingEffectSource input, boolean dryRun) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return new HealingEffectDatabaseImporter().execute(connection, input, site.getSiteId(), dryRun);
        }
    }

    void assertEmpty() {
        assertThat(overall.count()).isZero(); assertThat(participants.count()).isZero(); assertThat(batches.count()).isZero();
    }

    @Test void dryRunValidatesWithoutWritingAnything() throws Exception {
        var result = run(source, true);
        assertThat(result.status()).isEqualTo("DRY_RUN");
        assertThat(result.schemaReady()).isTrue();
        assertThat(result.overallRows()).isEqualTo(6); assertThat(result.participantRows()).isEqualTo(2);
        assertEmpty();
    }

    @Test void missingSchemaAllowsDryRunButRejectsWritesWithoutCreatingTables() throws Exception {
        jdbc.execute("alter table healing_effect_import_batch rename to synthetic_hidden_batch");
        try {
            var result = run(source, true);
            assertThat(result.status()).isEqualTo("DRY_RUN");
            assertThat(result.schemaReady()).isFalse();
            assertThat(result.warnings()).hasSize(1);
            assertThatThrownBy(() -> run(source, false)).hasMessageContaining("Schema missing");
            assertThat(overall.count()).isZero(); assertThat(participants.count()).isZero();
        } finally { jdbc.execute("alter table synthetic_hidden_batch rename to healing_effect_import_batch"); }
        assertEmpty();
    }

    @Test void concurrentSameFileImportsCommitOnlyOnce() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return run(source, false).status(); });
            var second = executor.submit(() -> { start.await(); return run(source, false).status(); });
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("IMPORTED", "ALREADY_IMPORTED");
        }
        assertThat(overall.count()).isEqualTo(6); assertThat(participants.count()).isEqualTo(2); assertThat(batches.count()).isEqualTo(1);
    }

    @Test void persistRepositoriesQueriesPrecisionAndMissingCombinations() throws Exception {
        assertThat(run(source, false).status()).isEqualTo("IMPORTED");
        assertThat(overall.count()).isEqualTo(6); assertThat(participants.count()).isEqualTo(2);
        var batch = batches.findBySourceSha256(source.sha256()).orElseThrow();
        assertThat(batch.getTargetSiteId()).isEqualTo(site.getSiteId());
        assertThat(batch.getSourceFilename()).isEqualTo("synthetic.xlsx");
        assertThat(batch.getOverallSummaryCount()).isEqualTo(6);
        assertThat(batch.getParticipantSummaryCount()).isEqualTo(2);
        assertThat(batch.getImportedAt()).isNotNull();
        var summaries = query.findOverall(site.getSiteId());
        assertThat(summaries).extracting(HealingEffectView::spotCode).containsExactly("HS1", "HS2", "HS3", "HS4", "HS5", "HS6");
        assertThat(summaries.getFirst().stressReductionDisplay()).isEqualTo("19.5%");
        assertThat(summaries.getFirst().emotionalIncreaseDisplay()).isEqualTo("54.3%");
        assertThat(summaries.getLast().stressReductionDisplay()).isEqualTo("21.2%");
        assertThat(summaries.getLast().emotionalIncreaseDisplay()).isEqualTo("200.4%");
        assertThat(summaries.getFirst().stressReductionRate()).isEqualByComparingTo("19.49");
        var views = query.findMember(site.getSiteId(), member.getMemberId());
        assertThat(views).hasSize(6);
        assertThat(views.getFirst().hasMeasurement()).isTrue();
        assertThat(views.getFirst().stressReductionDisplay()).isEqualTo("0.0%");
        assertThat(views.get(1).hasMeasurement()).isFalse();
        assertThat(views.get(1).stressReductionRate()).isNull();
        assertThat(views.get(1).stressReductionDisplay()).isEqualTo("측정 없음");
        assertThat(participants.findByMemberMemberIdOrderByHealingSpotCodeAsc(member.getMemberId()))
                .extracting(s -> s.getHealingSpot().getCode()).containsExactly("HS1", "HS6");
        assertThat(participants.findByMemberMemberIdAndHealingSpotSpotId(member.getMemberId(), spotList.get(1).getSpotId())).isEmpty();
        assertThat(overall.findByHealingSpotSpotId(spotList.getFirst().getSpotId())).isPresent();
        assertThat(query.findOverall(-1L)).isEmpty();
        assertThatThrownBy(() -> query.findMember(site.getSiteId(), -1L)).isInstanceOf(IllegalArgumentException.class);
        // Public DTO carries no member/participant identifiers.
        assertThat(Arrays.stream(HealingEffectView.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("memberId", "participantNo", "participant", "loginId", "phone", "name");
    }

    @Test void sameShaIsNoOpEvenAfterDeletionAndDifferentShaIsRejected() throws Exception {
        run(source, false);
        var second = run(source, false);
        assertThat(second.status()).isEqualTo("ALREADY_IMPORTED");
        assertThat(second.overallRows()).isZero(); assertThat(second.participantRows()).isZero();
        assertThat(overall.count()).isEqualTo(6); assertThat(participants.count()).isEqualTo(2); assertThat(batches.count()).isEqualTo(1);
        var different = new HealingEffectExcelParser().parse(SyntheticEffectWorkbook.create(directory,
                b -> b.getSheet(HealingEffectExcelParser.OVERALL).getRow(1).getCell(4).setCellValue(77)));
        assertThat(different.sha256()).isNotEqualTo(source.sha256());
        assertThatThrownBy(() -> run(different, false)).hasMessageContaining("overwrite");
        assertThatThrownBy(() -> run(different, true)).hasMessageContaining("overwrite");
        memberService.deleteMember(member.getMemberId());
        assertThat(run(source, false).status()).isEqualTo("ALREADY_IMPORTED");
        assertThat(participants.count()).isZero(); assertThat(overall.count()).isEqualTo(6);
    }

    @Test void improvementViewsUseStoredCountsAndParticipantOverallIsSummedNotStored() throws Exception {
        run(source, false);
        var overallRows = overall.findByHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(site.getSiteId());
        overallRows.forEach(row -> row.updateImprovementCounts(1, 3, 2, 1, 2, 1));
        overall.saveAllAndFlush(overallRows);
        var memberRows = participants.findByMemberMemberIdOrderByHealingSpotCodeAsc(member.getMemberId());
        memberRows.get(0).updateImprovementCounts(3, 2, 1, 2, 1);
        memberRows.get(1).updateImprovementCounts(2, 1, 1, 0, 0);
        participants.saveAllAndFlush(memberRows);

        var spotViews = query.findOverallImprovements(site.getSiteId());
        assertThat(spotViews).hasSize(6);
        assertThat(spotViews.getFirst().stress().improvementRateDisplay()).isEqualTo("50.0%");
        var memberViews = query.findMemberImprovements(site.getSiteId(), member.getMemberId());
        assertThat(memberViews).hasSize(2);
        var participantOverall = query.findParticipantOverallImprovement(site.getSiteId(), member.getMemberId()).orElseThrow();
        assertThat(participantOverall.totalExperienceCount()).isEqualTo(5);
        assertThat(participantOverall.stress().validCount()).isEqualTo(3);
        assertThat(participantOverall.stress().improvedCount()).isEqualTo(2);
        assertThat(participantOverall.emotional().validCount()).isEqualTo(2);
        assertThat(participantOverall.emotional().improvedCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"member", "site", "missingSpot", "duplicateSpot", "spotName", "courseName", "wrongCourse", "duplicateCourse"})
    void rejectsMappingProblemsBeforeWrites(String problem) {
        switch (problem) {
            case "member" -> members.delete(member);
            case "site" -> site = new Site();
            case "missingSpot" -> spots.delete(spotList.getFirst());
            case "duplicateSpot" -> spots.save(new HealingSpot(spotList.getFirst().getHealingCourse(), "HS1", "호스타 정원", 37.0, 127.0));
            case "spotName" -> jdbc.update("update healing_spot set name='wrong' where spot_id=?", spotList.getFirst().getSpotId());
            case "courseName" -> jdbc.update("update healing_course set name='wrong' where site_id=?", site.getSiteId());
            case "wrongCourse" -> jdbc.update("update healing_spot set course_id=? where spot_id=?", spotList.get(2).getHealingCourse().getCourseId(), spotList.getFirst().getSpotId());
            case "duplicateCourse" -> courses.save(new HealingCourse(site, "HC-A", "회복 코스", null, null, null));
        }
        assertThatThrownBy(() -> {
            try (var connection = dataSource.getConnection()) {
                new HealingEffectDatabaseImporter().execute(connection, source, problem.equals("site") ? -1 : site.getSiteId(), false);
            }
        }).isInstanceOf(EffectImportValidationException.class);
        assertEmpty();
    }

    @Test void sameSpotCodesInOtherSiteDoNotMakeTargetAmbiguous() throws Exception {
        var other = sites.save(new Site("other site", "test", 37.0, 127.0, 3));
        var course = courses.save(new HealingCourse(other, "HC-A", "different name", null, null, null));
        spots.save(new HealingSpot(course, "HS1", "different name", 37.0, 127.0));
        assertThat(run(source, true).status()).isEqualTo("DRY_RUN");
        assertEmpty();
    }

    @Test void lateBatchFailureRollsBackBothSummaryTables() throws Exception {
        jdbc.execute("alter table healing_effect_import_batch add constraint synthetic_reject_batch check (overall_summary_count < 0)");
        try {
            assertThatThrownBy(() -> run(source, false)).isInstanceOf(SQLException.class);
            assertEmpty();
        } finally { jdbc.execute("alter table healing_effect_import_batch drop constraint synthetic_reject_batch"); }
        assertThat(run(source, false).status()).isEqualTo("IMPORTED");
    }

    @Test void uniqueConstraintsRejectDuplicates() throws Exception {
        run(source, false);
        var spot = spotList.getFirst();
        var rate = source.overall().getFirst().stressRate();
        assertThatThrownBy(() -> overall.saveAndFlush(new HealingSpotEffectSummary(spot, 1, 2, rate, 1, 2, rate)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> participants.saveAndFlush(new MemberHealingSpotEffectSummary(member, spot, 2, rate, 2, rate)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> batches.saveAndFlush(new HealingEffectImportBatch(source.sha256(), "other.xlsx", LocalDateTime.now(), 6, 2, site.getSiteId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(overall.count()).isEqualTo(6); assertThat(participants.count()).isEqualTo(2); assertThat(batches.count()).isEqualTo(1);
    }

    @Test void refusesExistingRowsWithoutBatch() {
        var rate = source.overall().getFirst().stressRate();
        overall.saveAndFlush(new HealingSpotEffectSummary(spotList.getFirst(), 1, 2, rate, 1, 2, rate));
        assertThatThrownBy(() -> run(source, false)).hasMessageContaining("overwrite");
        assertThat(overall.count()).isEqualTo(1); assertThat(batches.count()).isZero();
    }

    @Test void memberDeletionRemovesOnlyOwnedEffectsAndExistingSchedules() throws Exception {
        run(source, false);
        var other = members.save(new Member(5, 1, "other", "unused"));
        var rate = source.overall().getFirst().stressRate();
        participants.saveAndFlush(new MemberHealingSpotEffectSummary(other, spotList.getFirst(), 1, rate, 1, rate));
        var schedule = schedules.save(new Schedule(member, LocalDate.of(2026, 1, 1)));
        scheduleSpots.save(new ScheduleSpot(schedule, spotList.getFirst(), LocalTime.NOON, 1));
        memberService.deleteMember(member.getMemberId());
        assertThat(members.existsById(member.getMemberId())).isFalse();
        assertThat(participants.count()).isEqualTo(1); assertThat(overall.count()).isEqualTo(6);
        assertThat(schedules.count()).isZero(); assertThat(scheduleSpots.count()).isZero();
        assertThat(batches.count()).isEqualTo(1); assertThat(spots.count()).isEqualTo(6);
    }

    @Test void spotDeletionRemovesEffectsAndKeepsBatchMembersAndOtherSpots() throws Exception {
        run(source, false);
        spotService.deleteHealingSpot(spotList.getFirst().getSpotId());
        assertThat(overall.count()).isEqualTo(5); assertThat(participants.count()).isEqualTo(1);
        assertThat(batches.count()).isEqualTo(1); assertThat(members.count()).isEqualTo(1); assertThat(spots.count()).isEqualTo(5);
        assertThat(run(source, false).status()).isEqualTo("ALREADY_IMPORTED");
    }

    @Test void scheduledSpotDeletionRemainsBlockedAndPreservesSummaries() throws Exception {
        run(source, false);
        var schedule = schedules.save(new Schedule(member, LocalDate.of(2026, 1, 1)));
        scheduleSpots.save(new ScheduleSpot(schedule, spotList.getFirst(), LocalTime.NOON, 1));
        assertThatThrownBy(() -> spotService.deleteHealingSpot(spotList.getFirst().getSpotId())).hasMessageContaining("일정에 사용 중");
        assertThat(overall.count()).isEqualTo(6); assertThat(participants.count()).isEqualTo(2);
    }
}
