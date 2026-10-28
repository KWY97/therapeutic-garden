package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Schedule;
import com.example.manage.domain.Site;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.SiteRepository;
import com.example.manage.service.SessionAuthentication;
import com.example.manage.service.SiteService;
import jakarta.servlet.Filter;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:calendar-site-diagnosis;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AdminCalendarSiteQueryDiagnosisTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired ScheduleRepository schedules;
    @Autowired SiteRepository sites;
    @Autowired SessionAuthentication authentication;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean SiteService siteService;

    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        schedules.deleteAllInBatch();
        spots.deleteAllInBatch();
        courses.deleteAllInBatch();
        members.deleteAllInBatch();
        sites.deleteAllInBatch();
        admins.deleteAllInBatch();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Admin account = admins.saveAndFlush(new Admin("query-diagnosis-" + UUID.randomUUID(), "test-hash"));
        admin = new MockHttpSession();
        authentication.authenticateAdmin(admin, account);
        sites.saveAndFlush(new Site("diagnostic-" + UUID.randomUUID(), "address", 37.0, 127.0, 3));
    }

    @Test
    void emptyCalendarRendersOutsideTestTransaction() throws Exception {
        String html = mvc.perform(get("/admin/schedules/calendar").session(admin))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/schedule-calendar"))
                .andExpect(model().attributeExists("schedules"))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"schedule-data\"", "?from=calendar")
                .doesNotContain("class=\"schedule-data-item\"");
    }

    @Test
    void calendarWithScheduleRendersMemberAttributesOutsideTestTransaction() throws Exception {
        Member member = members.saveAndFlush(new Member(1_700_000_001, 1,
                "calendar-diagnosis-" + UUID.randomUUID(), "test-hash"));
        schedules.saveAndFlush(new Schedule(member, LocalDate.of(2026, 10, 28)));

        String html = mvc.perform(get("/admin/schedules/calendar").session(admin))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/schedule-calendar"))
                .andExpect(model().attributeExists("schedules"))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-participant-no=\"1700000001\"", "data-group-no=\"1\"",
                "?from=calendar");
    }

    @Test
    void calendarRendersMultipleMembersOutsideTestTransaction() throws Exception {
        for (int i = 0; i < 3; i++) {
            Member member = members.saveAndFlush(new Member(1_700_000_021 + i, i + 1,
                    "calendar-multiple-" + UUID.randomUUID(), "test-hash"));
            schedules.saveAndFlush(new Schedule(member, LocalDate.of(2026, 10, 28 + i)));
        }
        String html = mvc.perform(get("/admin/schedules/calendar").session(admin))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("schedules"))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andReturn().getResponse().getContentAsString();
        for (int i = 0; i < 3; i++) {
            assertThat(html).contains("data-participant-no=\"" + (1_700_000_021 + i) + "\"",
                    "data-group-no=\"" + (i + 1) + "\"");
        }
    }

    @Test
    void calendarQueryDoesNotLoadOneMemberPerDistinctScheduleOwner() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        long empty = calendarMemberSqlCount(transactions, statistics);
        Member first = members.saveAndFlush(new Member(1_700_000_011, 1,
                "calendar-first-" + UUID.randomUUID(), "test-hash"));
        schedules.saveAndFlush(new Schedule(first, LocalDate.of(2026, 10, 28)));
        long one = calendarMemberSqlCount(transactions, statistics);
        Schedule sameMemberSchedule = schedules.saveAndFlush(new Schedule(first, LocalDate.of(2026, 10, 29)));
        long sameOwner = calendarMemberSqlCount(transactions, statistics);
        schedules.deleteById(sameMemberSchedule.getScheduleId());
        for (int i = 0; i < 2; i++) {
            Member member = members.saveAndFlush(new Member(1_700_000_012 + i, 1,
                    "calendar-more-" + UUID.randomUUID(), "test-hash"));
            schedules.saveAndFlush(new Schedule(member, LocalDate.of(2026, 10, 29 + i)));
        }
        long three = calendarMemberSqlCount(transactions, statistics);
        assertThat(empty).isEqualTo(1);
        assertThat(one).isEqualTo(1);
        assertThat(sameOwner).isEqualTo(1);
        assertThat(three).isEqualTo(1);
    }

    private long calendarMemberSqlCount(TransactionTemplate transactions,
                                        org.hibernate.stat.Statistics statistics) {
        statistics.clear();
        transactions.executeWithoutResult(status -> schedules.findAllForAdminCalendar().forEach(
                schedule -> schedule.getMember().getParticipantNo()));
        return statistics.getPrepareStatementCount();
    }

    @Test
    void calendarRequiresAdministratorSession() throws Exception {
        mvc.perform(get("/admin/schedules/calendar"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        Member member = members.saveAndFlush(new Member(1_700_000_031, 1,
                "calendar-participant-" + UUID.randomUUID(), "test-hash"));
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/schedules/calendar").session(participant))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void onlySiteListStillQueriesSitesAmongUnrelatedRequests() throws Exception {
        clearInvocations(siteService);
        mvc.perform(get("/admin/members").session(admin)).andExpect(status().isOk());
        verify(siteService, times(0)).findAllSites();

        clearInvocations(siteService);
        mvc.perform(get("/admin/sites").session(admin)).andExpect(status().isOk());
        verify(siteService, times(1)).findAllSites();

        clearInvocations(siteService);
        mvc.perform(get("/admin/weather").session(admin)
                .param("date", "2000-01-01").param("time", "09:00")).andExpect(status().isBadRequest());
        verify(siteService, times(0)).findAllSites();
    }

    @Test
    void separatedAdminRequestsOnlyLoadSitesForSelectionForms() throws Exception {
        Site site = sites.saveAndFlush(new Site("diagnostic-detail-" + UUID.randomUUID(),
                "address", 37.0, 127.0, 3));
        HealingCourse course = courses.saveAndFlush(new HealingCourse(site, "HC-D", "course", 37.0, 127.0, 50.0));
        HealingSpot spot = spots.saveAndFlush(new HealingSpot(course, "HS-D", "spot", 37.0, 127.0));

        verifySiteCalls("/admin/courses", 1);
        verifySiteCalls("/admin/courses/" + course.getCourseId(), 0);
        verifySiteCalls("/admin/spots?courseId=" + course.getCourseId(), 0);
        verifySiteCalls("/admin/spots/" + spot.getSpotId(), 0);
        verifySiteCalls("/admin/sites/" + site.getSiteId(), 0);
        verifySiteCalls("/admin/schedules/new", 1);
        verifySiteCalls("/admin/schedules", 0);
        verifySiteCalls("/admin/schedules/calendar", 0);

        clearInvocations(siteService);
        mvc.perform(post("/admin/schedules/new").session(admin).with(csrf()))
                .andExpect(status().isOk());
        verify(siteService, times(1)).findAllSites();

        Member member = members.saveAndFlush(new Member(1_700_000_041, 1,
                "site-query-member-" + UUID.randomUUID(), "test-hash"));
        Schedule schedule = schedules.saveAndFlush(new Schedule(member, LocalDate.of(2026, 10, 28)));
        verifySiteCalls("/admin/schedules/" + schedule.getScheduleId() + "/edit", 1);
        clearInvocations(siteService);
        mvc.perform(post("/admin/schedules/" + schedule.getScheduleId() + "/edit")
                .session(admin).with(csrf())).andExpect(status().isOk());
        verify(siteService, times(1)).findAllSites();

        clearInvocations(siteService);
        mvc.perform(post("/admin/schedules/new").session(admin)).andExpect(status().isForbidden());
        verify(siteService, times(0)).findAllSites();
        clearInvocations(siteService);
        mvc.perform(get("/admin/schedules/new")).andExpect(redirectedUrl("/admin/login"));
        verify(siteService, times(0)).findAllSites();
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        clearInvocations(siteService);
        mvc.perform(get("/admin/schedules/new").session(participant))
                .andExpect(redirectedUrl("/admin/login"));
        verify(siteService, times(0)).findAllSites();
    }

    private void verifySiteCalls(String path, int expected) throws Exception {
        clearInvocations(siteService);
        mvc.perform(get(path).session(admin)).andExpect(status().isOk());
        verify(siteService, times(expected)).findAllSites();
    }
}
