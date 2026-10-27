package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.SessionAuthentication;
import jakarta.persistence.EntityManager;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemberScheduleManageTests {
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(940_000_000);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired MemberRepository members;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired EntityManager entityManager;
    @Autowired SessionAuthentication authentication;

    MockMvc mvc;
    Member own;
    Member other;
    Site site;
    HealingCourse course;
    HealingSpot first;
    HealingSpot second;
    Schedule ownSchedule;
    Schedule otherSchedule;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").with(LegacyAuthenticatedSession.hydrate(context)))
                .addFilters(security).build();
        own = member();
        other = member();
        site = sites.save(new Site("수정 사이트 " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        course = courses.save(new HealingCourse(site, "COURSE", "치유 코스", 37.0, 127.0, 50.0));
        first = spots.save(new HealingSpot(course, "FIRST", "첫 정원", 37.0, 127.0));
        second = spots.save(new HealingSpot(course, "SECOND", "둘째 정원", 37.0, 127.0));
        ownSchedule = schedule(own, DATE);
        otherSchedule = schedule(other, DATE);
    }

    private Member member() {
        return members.save(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                "manage-" + UUID.randomUUID(), "password"));
    }

    private Schedule schedule(Member member, LocalDate date) {
        Schedule schedule = schedules.save(new Schedule(member, date));
        schedule.updateWeather("맑음", 20.0, 50.0);
        scheduleSpots.save(new ScheduleSpot(schedule, first, LocalTime.of(9, 0), 1));
        scheduleSpots.save(new ScheduleSpot(schedule, second, LocalTime.of(9, 10), 2));
        return schedule;
    }

    private MockHttpSession session(Member member) {
        MockHttpSession session = new MockHttpSession();
        authentication.authenticateMember(session, member);
        return session;
    }

    private MockHttpServletRequestBuilder edit(Schedule schedule, MockHttpSession session) {
        return edit(schedule.getScheduleId(), session);
    }

    private MockHttpServletRequestBuilder edit(Long scheduleId, MockHttpSession session) {
        return post("/member/schedules/{id}/edit", scheduleId).session(session).with(csrf())
                .param("scheduleDate", DATE.toString())
                .param("siteId", site.getSiteId().toString())
                .param("courseId", course.getCourseId().toString())
                .param("firstSpotId", first.getSpotId().toString())
                .param("firstStartTime", "09:00")
                .param("secondSpotId", second.getSpotId().toString())
                .param("secondStartTime", "09:10");
    }

    private String actions(String html) {
        int start = html.indexOf("<div class=\"detail-action-list\">");
        assertThat(start).isGreaterThanOrEqualTo(0);
        return html.substring(start, html.indexOf("</div>", start));
    }

    private void assertThreeActions(String html, String returnText, String returnHref, String absentText) {
        String actions = actions(html);
        assertThat(actions).containsOnlyOnce("class=\"primary-button\"")
                .containsOnlyOnce("class=\"delete-form\"")
                .containsOnlyOnce("class=\"secondary-link-button\"")
                .contains("일정 수정", "일정 삭제", returnText, "href=\"" + returnHref + "\"")
                .doesNotContain(absentText);
    }

    @Test
    void memberReturnButtonFollowsCalendarOrListAndRejectsUnknownOrigins() throws Exception {
        Long id = ownSchedule.getScheduleId();
        MockHttpSession login = session(own);
        String calendar = mvc.perform(get("/member/schedules/{id}", id).session(login).param("from", "calendar"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThreeActions(calendar, "달력으로", "/member", "일정 목록으로");
        assertThat(actions(calendar)).contains("/edit?from=calendar", "/delete?from=calendar");

        String list = mvc.perform(get("/member/schedules/{id}", id).session(login).param("from", "list"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThreeActions(list, "일정 목록으로", "/member/schedules", "달력으로");
        assertThat(actions(list)).contains("/edit?from=list", "/delete?from=list");

        String unknown = mvc.perform(get("/member/schedules/{id}", id).session(login)
                        .param("from", "https://example.invalid/leave"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThreeActions(unknown, "일정 목록으로", "/member/schedules", "달력으로");
        assertThat(actions(unknown)).doesNotContain("example.invalid");
        String absent = mvc.perform(get("/member/schedules/{id}", id).session(login))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThreeActions(absent, "일정 목록으로", "/member/schedules", "달력으로");
    }

    @Test
    void editingFromCalendarPreservesOriginThroughFormAndRedirect() throws Exception {
        Long id = ownSchedule.getScheduleId();
        MockHttpSession login = session(own);
        String form = mvc.perform(get("/member/schedules/{id}/edit", id).session(login)
                        .param("from", "calendar"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).contains("action=\"/member/schedules/" + id + "/edit?from=calendar\"",
                "href=\"/member/schedules/" + id + "?from=calendar\"");
        mvc.perform(edit(ownSchedule, login).param("from", "calendar"))
                .andExpect(redirectedUrl("/member/schedules/" + id + "?from=calendar"));
        String detail = mvc.perform(get("/member/schedules/{id}", id).session(login)
                        .param("from", "calendar"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThreeActions(detail, "달력으로", "/member", "일정 목록으로");
    }

    @Test
    void memberDetailAndEditShowOnlyMemberActionsAndExistingValues() throws Exception {
        MockHttpSession login = session(own);
        String detail = mvc.perform(get("/member/schedules/{id}", ownSchedule.getScheduleId()).session(login))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("/member/schedules/" + ownSchedule.getScheduleId() + "/edit",
                        "/member/schedules/" + ownSchedule.getScheduleId() + "/delete",
                        "href=\"/member/schedules\"", "name=\"_csrf\"")
                .doesNotContain("/admin/schedules/", other.getLoginId());

        String edit = mvc.perform(get("/member/schedules/{id}/edit", ownSchedule.getScheduleId()).session(login))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-edit"))
                .andReturn().getResponse().getContentAsString();
        assertThat(edit).contains("action=\"/member/schedules/" + ownSchedule.getScheduleId() + "/edit?from=list\"",
                        "data-weather-url=\"/member/weather\"", "weather-lookup.js",
                        "value=\"2026-10-28\"", "data-selected=\"" + course.getCourseId() + "\"",
                        "data-selected=\"" + first.getSpotId() + "\"", "09:00", "맑음")
                .doesNotContain("name=\"memberId\"", "id=\"memberId\"",
                        "/admin/schedules/", "/admin/weather", other.getLoginId());
    }

    @Test
    void memberCanUpdateOwnScheduleWithoutChangingOwnerAndSeeUpdatedDetail() throws Exception {
        HealingCourse nextCourse = courses.save(new HealingCourse(site, "NEXT", "다음 코스", 37.0, 127.0, 50.0));
        HealingSpot nextFirst = spots.save(new HealingSpot(nextCourse, "THIRD", "셋째 정원", 37.0, 127.0));
        HealingSpot nextSecond = spots.save(new HealingSpot(nextCourse, "FOURTH", "넷째 정원", 37.0, 127.0));
        LocalDate nextDate = DATE.plusDays(1);
        mvc.perform(edit(ownSchedule, session(own))
                        .with(request -> {
                            request.setParameter("memberId", other.getMemberId().toString());
                            request.setParameter("scheduleDate", nextDate.toString());
                            request.setParameter("courseId", nextCourse.getCourseId().toString());
                            request.setParameter("firstSpotId", nextFirst.getSpotId().toString());
                            request.setParameter("firstStartTime", "10:30");
                            request.setParameter("secondSpotId", nextSecond.getSpotId().toString());
                            request.setParameter("secondStartTime", "10:40");
                            request.setParameter("weather", "흐림");
                            request.setParameter("temperature", "18.5");
                            request.setParameter("humidity", "67");
                            return request;
                        }))
                .andExpect(redirectedUrl("/member/schedules/" + ownSchedule.getScheduleId() + "?from=list"));
        entityManager.flush();
        entityManager.clear();

        Schedule updated = schedules.findById(ownSchedule.getScheduleId()).orElseThrow();
        assertThat(updated.getMember().getMemberId()).isEqualTo(own.getMemberId());
        assertThat(updated.getScheduleDate()).isEqualTo(nextDate);
        assertThat(updated.getWeather()).isEqualTo("흐림");
        assertThat(updated.getTemperature()).isEqualTo(18.5);
        assertThat(updated.getHumidity()).isEqualTo(67.0);
        var visits = scheduleSpots.findByScheduleScheduleIdOrderBySequenceAsc(updated.getScheduleId());
        assertThat(visits).extracting(visit -> visit.getHealingSpot().getSpotId())
                .containsExactly(nextFirst.getSpotId(), nextSecond.getSpotId());
        assertThat(visits).extracting(ScheduleSpot::getStartTime)
                .containsExactly(LocalTime.of(10, 30), LocalTime.of(10, 40));
        String detail = mvc.perform(get("/member/schedules/{id}", updated.getScheduleId()).session(session(own)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains(nextDate.toString(), "다음 코스", "흐림");
    }

    @Test
    void deletingOwnScheduleRemovesVisitsListAndCalendarEntry() throws Exception {
        Long id = ownSchedule.getScheduleId();
        mvc.perform(post("/member/schedules/{id}/delete", id).session(session(own)).with(csrf()))
                .andExpect(redirectedUrl("/member/schedules"));
        entityManager.flush();
        entityManager.clear();
        assertThat(schedules.findById(id)).isEmpty();
        assertThat(scheduleSpots.findByScheduleScheduleIdOrderBySequenceAsc(id)).isEmpty();
        String list = mvc.perform(get("/member/schedules").session(session(own)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String calendar = mvc.perform(get("/member").session(session(own)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("/member/schedules/" + id);
        assertThat(calendar).doesNotContain("data-id=\"" + id + "\"");
    }

    @Test
    void otherAndMissingSchedulesAre404ForEveryMutationAndStayUnchanged() throws Exception {
        MockHttpSession login = session(own);
        Long otherId = otherSchedule.getScheduleId();
        mvc.perform(get("/member/schedules/{id}/edit", otherId).session(login))
                .andExpect(status().isNotFound());
        mvc.perform(edit(otherSchedule, login).param("memberId", own.getMemberId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/member/schedules/{id}/delete", otherId).session(login).with(csrf()))
                .andExpect(status().isNotFound());
        Long missingId = 999_999_999L;
        mvc.perform(get("/member/schedules/{id}/edit", missingId).session(login))
                .andExpect(status().isNotFound());
        mvc.perform(edit(missingId, login).with(request -> {
                    request.setParameter("firstSpotId", "");
                    return request;
                }))
                .andExpect(status().isNotFound());
        mvc.perform(post("/member/schedules/{id}/delete", missingId).session(login).with(csrf()))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();
        assertThat(schedules.findById(otherId).orElseThrow().getScheduleDate()).isEqualTo(DATE);
        assertThat(scheduleSpots.findByScheduleScheduleIdOrderBySequenceAsc(otherId)).hasSize(2);
    }

    @Test
    void duplicateAndValidationErrorsKeepSubmittedSelectionsWithoutMutation() throws Exception {
        Schedule secondOwn = schedule(own, DATE.plusDays(1));
        String duplicate = mvc.perform(edit(secondOwn, session(own)))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("scheduleForm"))
                .andReturn().getResponse().getContentAsString();
        assertThat(duplicate).contains("이미 등록되어 있습니다.", "value=\"2026-10-28\"");
        assertThat(secondOwn.getScheduleDate()).isEqualTo(DATE.plusDays(1));

        String invalid = mvc.perform(edit(ownSchedule, session(own)).with(request -> {
                    request.setParameter("scheduleDate", DATE.plusDays(2).toString());
                    request.setParameter("firstSpotId", "");
                    request.setParameter("weather", "흐림");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("scheduleForm", "firstSpotId"))
                .andReturn().getResponse().getContentAsString();
        assertThat(invalid).contains("첫 번째 스팟을 선택해 주세요.",
                "value=\"2026-10-30\"", "data-selected=\"" + course.getCourseId() + "\"",
                "value=\"흐림\"");
        assertThat(ownSchedule.getScheduleDate()).isEqualTo(DATE);
        mvc.perform(edit(ownSchedule, session(own)))
                .andExpect(redirectedUrl("/member/schedules/" + ownSchedule.getScheduleId() + "?from=list"));
    }

    @Test
    void loginRolesCsrfAndAdminScreensRemainSeparate() throws Exception {
        Long id = ownSchedule.getScheduleId();
        mvc.perform(get("/member/schedules/{id}/edit", id)).andExpect(redirectedUrl("/member/login"));
        mvc.perform(edit(ownSchedule, session(own)).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(post("/member/schedules/{id}/delete", id).with(csrf()))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/member/schedules/{id}/edit", id).sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/admin/schedules/{id}/edit", id).session(session(own)))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(edit(ownSchedule, session(own)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/member/schedules/{id}/delete", id).session(session(own)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/member/schedules/{id}/delete", id).session(session(own)))
                .andExpect(status().isMethodNotAllowed());

        String admin = mvc.perform(get("/admin/schedules/{id}/edit", id).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(admin).contains("action=\"/admin/schedules/" + id + "/edit?from=\"",
                "name=\"memberId\"", "data-weather-url=\"/admin/weather\"")
                .doesNotContain("/member/schedules/", "/member/weather");
    }
}
