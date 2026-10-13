package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.dto.HealingSpotImprovementView;
import com.example.manage.dto.MemberCreateForm;
import com.example.manage.dto.MeasurementHistoryView;
import com.example.manage.repository.*;
import com.example.manage.service.AccountPasswordService;
import com.example.manage.service.MemberService;
import com.example.manage.service.ScheduleService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test004-analysis;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestParticipantAnalysisTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired MemberRepository members;
    @Autowired AdminRepository admins;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired MemberHealingSpotEffectSummaryRepository summaries;
    @Autowired HealingSpotEffectSummaryRepository overall;
    @Autowired HealingEffectImportBatchRepository batches;
    @Autowired ScheduleRepository schedules;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountPasswordService passwords;
    @Autowired MemberService memberService;
    @Autowired ScheduleService scheduleService;
    MockMvc mvc;

    @BeforeAll void createIsolatedMeasurementSchema() throws Exception {
        for (String sql : Files.readString(Path.of("docs/healing-measurement-schema.sql")).split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
    }

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private record Fixture(Member p004, Member other, Member test, Schedule p004Schedule,
                           Long siteId, Long courseId, Long firstSpotId, Long secondSpotId,
                           String testPassword, String p004Password) {}

    private Fixture fixture() {
        String p004Password = UUID.randomUUID() + "Ab9!";
        String testPassword = UUID.randomUUID() + "Ab9!";
        Member p004 = members.saveAndFlush(new Member(4, 1, "private-p004-" + UUID.randomUUID(),
                encoder.encode(p004Password), "실제 참가자", "01044445555"));
        Member other = members.saveAndFlush(new Member(5, 1, "private-other-" + UUID.randomUUID(),
                encoder.encode(UUID.randomUUID().toString()), "다른 참가자", "01055555555"));
        Member test = new Member(900_004, 0, "test004", encoder.encode(testPassword), "테스트 참가자", null);
        test.configureTestAnalysis(p004.getMemberId());
        test = members.saveAndFlush(test);

        Site site = sites.save(new Site("test-analysis-" + UUID.randomUUID(), "synthetic", 37.0, 127.0, 3));
        HealingCourse course = courses.save(new HealingCourse(site, "HC-A", "회복 코스", null, null, null));
        Long firstSpotId = null;
        Long secondSpotId = null;
        for (int i = 1; i <= 6; i++) {
            HealingSpot spot = spots.save(new HealingSpot(course, "HS" + i, "공간 " + i, 37.0, 127.0));
            if (i == 1) firstSpotId = spot.getSpotId();
            if (i == 2) secondSpotId = spot.getSpotId();
            var overallSummary = new HealingSpotEffectSummary(spot, 2, 2, BigDecimal.TEN,
                    2, 2, BigDecimal.TEN);
            overallSummary.updateImprovementCounts(2, 2, 2, 1, 2, 1);
            overall.save(overallSummary);
            var p004Summary = new MemberHealingSpotEffectSummary(p004, spot, 1, BigDecimal.TEN,
                    1, BigDecimal.TEN);
            p004Summary.updateImprovementCounts(1, 1, 1, 1, 1);
            summaries.save(p004Summary);
            var otherSummary = new MemberHealingSpotEffectSummary(other, spot, 1, BigDecimal.ONE,
                    1, BigDecimal.ONE);
            otherSummary.updateImprovementCounts(1, 1, 0, 1, 0);
            summaries.save(otherSummary);
        }
        batches.saveAndFlush(new HealingEffectImportBatch("b".repeat(64), "synthetic.xlsx",
                LocalDateTime.now(), 6, 12, site.getSiteId()));
        Schedule p004Schedule = schedules.saveAndFlush(new Schedule(p004, LocalDate.of(2026, 8, 14)));
        return new Fixture(p004, other, test, p004Schedule, site.getSiteId(), course.getCourseId(),
                firstSpotId, secondSpotId, testPassword, p004Password);
    }

    private MockHttpSession session(Member member) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginMemberId", member.getMemberId());
        session.setAttribute("loginMemberName", member.getName());
        return session;
    }

    @SuppressWarnings("unchecked")
    @Test void participantAnalysisReadsOwnDataButTest004ReadsP004AndKeepsOwnCalendar() throws Exception {
        Fixture f = fixture();
        var normal = mvc.perform(get("/member").session(session(f.other())))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        var testResult = mvc.perform(get("/member").session(session(f.test())).param("memberId", f.other().getMemberId().toString()))
                .andExpect(status().isOk()).andReturn();
        var test = testResult.getModelAndView().getModel();
        List<HealingSpotImprovementView> normalEffects = (List<HealingSpotImprovementView>) normal.get("spotEffects");
        List<HealingSpotImprovementView> testEffects = (List<HealingSpotImprovementView>) test.get("spotEffects");
        assertThat(normalEffects).hasSize(6);
        assertThat(testEffects).hasSize(6);
        assertThat(normalEffects.getFirst().stress().improvementRateDisplay()).isEqualTo("0.0%");
        assertThat(testEffects.getFirst().stress().improvementRateDisplay()).isEqualTo("100.0%");
        assertThat(test.get("overallImprovement")).isEqualTo(
                mvc.perform(get("/member").session(session(f.p004()))).andReturn()
                        .getModelAndView().getModel().get("overallImprovement"));
        assertThat((List<?>) test.get("schedules")).isEmpty();
        @SuppressWarnings("unchecked")
        List<HealingSpotImprovementView> publicKpi = (List<HealingSpotImprovementView>) mvc.perform(get("/"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("healingEffects");
        assertThat(publicKpi).hasSize(6);
        assertThat(publicKpi.getFirst().participantCount()).isEqualTo(2);
        assertThat(publicKpi.getFirst().totalExperienceCount()).isEqualTo(2);
        assertThat(testResult.getResponse().getContentAsString())
                .contains("테스트 계정", "테스트 참가자")
                .doesNotContain(f.p004().getLoginId(), f.p004().getPhone(), f.p004().getName());
    }

    @Test void loginPasswordAndMyPageStayIndependent() throws Exception {
        Fixture f = fixture();
        MockHttpSession login = new MockHttpSession();
        mvc.perform(post("/member/login").with(csrf()).session(login)
                        .param("loginId", "test004").param("password", f.testPassword()))
                .andExpect(redirectedUrl("/member"));
        assertThat(login.getAttribute("loginMemberId")).isEqualTo(f.test().getMemberId());
        String page = mvc.perform(get("/member/mypage").session(login)
                        .param("memberId", f.p004().getMemberId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("테스트 계정", "테스트 참가자", "test004")
                .doesNotContain(f.p004().getName(), f.p004().getLoginId(), f.p004().getPhone());
        String nextPassword = UUID.randomUUID() + "Ab9!";
        assertThat(passwords.changeMember(f.test().getMemberId(), f.testPassword(), nextPassword, nextPassword))
                .isEqualTo(AccountPasswordService.Result.SUCCESS);
        members.flush();
        assertThat(encoder.matches(nextPassword, members.findById(f.test().getMemberId()).orElseThrow().getPassword())).isTrue();
        assertThat(encoder.matches(f.p004Password(), members.findById(f.p004().getMemberId()).orElseThrow().getPassword())).isTrue();
    }

    @Test void adminAnalysisKeepsSelectedMemberAndOperationalSelectionsExcludeTestAccount() throws Exception {
        Fixture f = fixture();
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("loginAdminId", admins.saveAndFlush(new Admin("test-admin-" + UUID.randomUUID(),
                encoder.encode(UUID.randomUUID().toString()))).getAdminId());
        var adminData = mvc.perform(get("/admin/members/" + f.test().getMemberId() + "/data").session(admin))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) adminData.get("spotEffects")).hasSize(6);
        assertThat(((List<HealingSpotImprovementView>) adminData.get("spotEffects")).getFirst()
                .stress().improvementRateDisplay()).isEqualTo("측정 없음");
        for (String path : List.of("/admin/members", "/admin/schedules/new")) {
            String html = mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain("test004", "테스트 참가자");
        }
    }

    @Test void testAccountCannotReadP004ScheduleAndInvalidReferenceFailsClosed() throws Exception {
        Fixture f = fixture();
        mvc.perform(get("/member/api/schedules/" + f.p004Schedule().getScheduleId())
                .session(session(f.test()))).andExpect(status().isNotFound());
        f.test().configureTestAnalysis(Long.MAX_VALUE);
        members.flush();
        mvc.perform(get("/member").session(session(f.test()))).andExpect(status().isNotFound())
                .andExpect(view().name("member/analysis-unavailable"));
    }

    @Test void realParticipantNumberingSkipsTestAccount() {
        fixture();
        var form = new MemberCreateForm();
        form.setGroupNo(1);
        form.setLoginId("next-real-" + UUID.randomUUID());
        form.setPassword(UUID.randomUUID().toString());
        form.setName("다음 실제 참가자");
        assertThat(memberService.createMember(form).getParticipantNo()).isEqualTo(6);
    }

    @Test void directScheduleRegistrationCannotSelectTestAccount() {
        Fixture f = fixture();
        assertThatThrownBy(() -> scheduleService.createSchedule(f.test().getMemberId(),
                LocalDate.of(2026, 9, 1), f.siteId(), f.courseId(), f.firstSpotId(),
                LocalTime.of(9, 0), f.secondSpotId(), LocalTime.of(10, 0), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schedules.findByMemberMemberIdOrderByScheduleDateAsc(f.test().getMemberId())).isEmpty();
    }

    @Test void referencedP004DeletionIsRejectedWithoutRemovingItsData() throws Exception {
        Fixture f = fixture();
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("loginAdminId", admins.saveAndFlush(new Admin("delete-admin-" + UUID.randomUUID(),
                encoder.encode(UUID.randomUUID().toString()))).getAdminId());
        var result = mvc.perform(post("/admin/members/" + f.p004().getMemberId() + "/delete")
                        .with(csrf()).session(admin))
                .andExpect(redirectedUrl("/admin/members")).andReturn();
        assertThat(result.getFlashMap().get("errorMessage")).asString().contains("참조 대상");
        assertThat(members.findById(f.p004().getMemberId())).isPresent();
        assertThat(schedules.findById(f.p004Schedule().getScheduleId())).isPresent();
        assertThat(summaries.findByMemberMemberIdOrderByHealingSpotCodeAsc(f.p004().getMemberId())).isNotEmpty();
    }

    @Test void deletedSessionAccountRedirectsFromMyPageWithoutServerError() throws Exception {
        Fixture f = fixture();
        MockHttpSession oldSession = session(f.test());
        members.delete(f.test());
        members.flush();
        mvc.perform(get("/member/mypage").session(oldSession))
                .andExpect(redirectedUrl("/member/login"));
    }

    @SuppressWarnings("unchecked")
    @Test void participantPageModelShowsOnlyReferencedP004MeasurementHistory() throws Exception {
        Fixture f = fixture();
        jdbc.update("insert into healing_measurement_import_batch "
                + "(id,source_sha256,source_filename,target_site_id,imported_at,session_count,measurement_count) "
                + "values (1001,'fixture','fixture.xlsx',?,current_timestamp,2,2)", f.siteId());
        jdbc.update("insert into healing_measurement_session "
                + "(id,batch_id,source_row,member_id,course_id,measurement_date,experience,baseline_stress,baseline_emotional) "
                + "values (1001,1001,1,?,?,'2026-08-14','P004 measurement',10,10)",
                f.p004().getMemberId(), f.courseId());
        jdbc.update("insert into healing_spot_measurement "
                + "(id,session_id,spot_id,stress_post,emotional_post) values (1001,1001,?,7,13)", f.firstSpotId());
        jdbc.update("insert into healing_measurement_session "
                + "(id,batch_id,source_row,member_id,course_id,measurement_date,experience,baseline_stress,baseline_emotional) "
                + "values (1002,1001,2,?,?,'2026-08-15','Other measurement',10,10)",
                f.other().getMemberId(), f.courseId());
        jdbc.update("insert into healing_spot_measurement "
                + "(id,session_id,spot_id,stress_post,emotional_post) values (1002,1002,?,2,17)", f.firstSpotId());

        var testResponse = mvc.perform(get("/member").session(session(f.test())))
                .andExpect(status().isOk()).andReturn();
        var normalResponse = mvc.perform(get("/member").session(session(f.other())))
                .andExpect(status().isOk()).andReturn();
        List<MeasurementHistoryView> testHistory = (List<MeasurementHistoryView>)
                testResponse.getModelAndView().getModel().get("measurementHistory");
        List<MeasurementHistoryView> normalHistory = (List<MeasurementHistoryView>)
                normalResponse.getModelAndView().getModel().get("measurementHistory");
        assertThat(testHistory).hasSize(6);
        assertThat(normalHistory).hasSize(6);
        assertThat(testHistory.getFirst().records()).hasSize(1);
        assertThat(testHistory.getFirst().records().getFirst().measurementDate()).isEqualTo("2026-08-14");
        assertThat(testHistory.getFirst().records().getFirst().stress().post()).isEqualByComparingTo("7");
        assertThat(normalHistory.getFirst().records()).hasSize(1);
        assertThat(normalHistory.getFirst().records().getFirst().measurementDate()).isEqualTo("2026-08-15");
        assertThat(normalHistory.getFirst().records().getFirst().stress().post()).isEqualByComparingTo("2");
        assertThat(testResponse.getResponse().getContentAsString())
                .contains("2026-08-14").doesNotContain("2026-08-15", "Other measurement");
        assertThat(normalResponse.getResponse().getContentAsString())
                .contains("2026-08-15").doesNotContain("2026-08-14", "P004 measurement");
    }
}
