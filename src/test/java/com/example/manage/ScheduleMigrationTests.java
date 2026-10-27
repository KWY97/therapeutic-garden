package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.ScheduleService;
import com.example.manage.service.MemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScheduleMigrationTests {

    @Autowired WebApplicationContext context;
    @Autowired ScheduleService service;
    @Autowired MemberService memberService;
    @Autowired MemberRepository members;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired EntityManager entityManager;

    private Member member;
    private Site site;
    private HealingCourse course;
    private HealingSpot first;
    private HealingSpot second;
    private final LocalDate date = LocalDate.of(2026, 9, 10);
    private final LocalTime time = LocalTime.of(9, 0);

    @BeforeEach
    void setUp() {
        member = members.save(new Member(100, 1, "test-member", "password"));
        site = sites.save(new Site("테스트 사이트", "주소", 37.0, 127.0, 3));
        course = courses.save(new HealingCourse(site, "COURSE-X", "새 코스", 37.0, 127.0, 50.0));
        first = spots.save(new HealingSpot(course, "SPOT-X", "첫 정원", 37.0, 127.0));
        second = spots.save(new HealingSpot(course, "SPOT-Y", "둘째 정원", 37.0, 127.0));
    }

    private Long create() {
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), "맑음", 25.0, 50.0);
        entityManager.flush();
        return schedules.findAll().get(0).getScheduleId();
    }

    @Test
    void createReadUpdateAndDeleteKeepMasterData() {
        Long id = create();
        entityManager.clear();
        var response = service.findMemberScheduleDetail(id, member.getMemberId());
        assertThat(response.getCourseCode()).isEqualTo("COURSE-X");
        assertThat(response.getSiteName()).isEqualTo("테스트 사이트");
        assertThat(response.getSpots()).extracting("code").containsExactly("SPOT-X", "SPOT-Y");
        assertThat(service.findAllSchedules("date").get(0).getCourseId()).isEqualTo(course.getCourseId());
        assertThat(service.findScheduleByMemberId(member.getMemberId())).hasSize(1);
        assertThat(service.findMemberScheduleDetail(id, -1L)).isNull();

        HealingCourse otherCourse = courses.save(new HealingCourse(site, "OTHER", "다른 코스", 37.0, 127.0, 50.0));
        HealingSpot otherFirst = spots.save(new HealingSpot(otherCourse, "OTHER-1", "정원 1", 37.0, 127.0));
        HealingSpot otherSecond = spots.save(new HealingSpot(otherCourse, "OTHER-2", "정원 2", 37.0, 127.0));
        service.updateSchedule(id, member.getMemberId(), date.plusDays(1), site.getSiteId(), otherCourse.getCourseId(),
                otherSecond.getSpotId(), time, otherFirst.getSpotId(), time.plusMinutes(10), null, null, null);
        entityManager.flush();
        entityManager.clear();
        var updated = service.findScheduleResponse(id);
        assertThat(updated.getCourseCode()).isEqualTo("OTHER");
        assertThat(updated.getSpots()).extracting("code").containsExactly("OTHER-2", "OTHER-1");
        assertThat(updated.getScheduleDate()).isEqualTo(date.plusDays(1));
        service.deleteSchedule(id);
        entityManager.flush();
        assertThat(scheduleSpots.count()).isZero();
        assertThat(schedules.count()).isZero();
        assertThat(spots.count()).isEqualTo(4);
        assertThat(courses.count()).isEqualTo(2);
        assertThat(sites.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownSpotAndMismatchedCourseOrSiteBeforeSaving() {
        assertThatThrownBy(() -> service.createSchedule(member.getMemberId(), date,
                site.getSiteId(), course.getCourseId(), -1L, time, second.getSpotId(), time, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createSchedule(member.getMemberId(), date,
                site.getSiteId(), -1L, first.getSpotId(), time, second.getSpotId(), time, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createSchedule(member.getMemberId(), date,
                -1L, course.getCourseId(), first.getSpotId(), time, second.getSpotId(), time, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schedules.count()).isZero();
    }

    @Test
    void duplicatePolicyPreservesDistinctVisitsAndDoesNotSaveExtraSpots() {
        Long originalId = create();
        long initialSpots = scheduleSpots.count();

        assertThatThrownBy(() -> service.createSchedule(member.getMemberId(), date, site.getSiteId(),
                course.getCourseId(), first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10),
                "비", 12.0, 80.0))
                .isInstanceOf(ScheduleService.DuplicateScheduleException.class);
        assertThat(schedules.count()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(initialSpots);

        HealingCourse otherCourse = courses.save(new HealingCourse(site, "OTHER", "다른 코스", 37.0, 127.0, 50.0));
        HealingSpot otherFirst = spots.save(new HealingSpot(otherCourse, "OTHER-1", "다른 정원 1", 37.0, 127.0));
        HealingSpot otherSecond = spots.save(new HealingSpot(otherCourse, "OTHER-2", "다른 정원 2", 37.0, 127.0));
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), otherCourse.getCourseId(),
                otherFirst.getSpotId(), time, otherSecond.getSpotId(), time.plusMinutes(10), null, null, null);

        HealingSpot third = spots.save(new HealingSpot(course, "SPOT-Z", "셋째 정원", 37.0, 127.0));
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, third.getSpotId(), time.plusMinutes(10), null, null, null);
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                second.getSpotId(), time.plusMinutes(10), first.getSpotId(), time, null, null, null);
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time.plusHours(1), second.getSpotId(), time.plusHours(1).plusMinutes(10),
                null, null, null);

        Member anotherMember = members.save(new Member(101, 2, "another-member", "password"));
        service.createSchedule(anotherMember.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), null, null, null);
        assertThat(schedules.count()).isEqualTo(6);
        assertThat(scheduleSpots.count()).isEqualTo(12);
        assertThat(service.findScheduleResponse(originalId)).isNotNull();
    }

    @Test
    void updateExcludesItselfButCannotBecomeAnotherScheduleDuplicate() {
        Long originalId = create();
        service.updateSchedule(originalId, member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), "흐림", 20.0, 70.0);
        service.createSchedule(member.getMemberId(), date.plusDays(1), site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), null, null, null);
        Long laterId = schedules.findByMemberMemberIdOrderByScheduleDateAsc(member.getMemberId()).get(1).getScheduleId();

        assertThatThrownBy(() -> service.updateSchedule(laterId, member.getMemberId(), date,
                site.getSiteId(), course.getCourseId(), first.getSpotId(), time,
                second.getSpotId(), time.plusMinutes(10), null, null, null))
                .isInstanceOf(ScheduleService.DuplicateScheduleException.class);
        assertThat(service.findScheduleResponse(laterId).getScheduleDate()).isEqualTo(date.plusDays(1));
        assertThat(scheduleSpots.count()).isEqualTo(4);
    }

    @Test
    void duplicatePostReturnsReadableFormError() throws Exception {
        create();
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var duplicate = post("/admin/schedules/new").sessionAttr("loginAdminId", 1L)
                .param("memberId", member.getMemberId().toString())
                .param("scheduleDate", date.toString())
                .param("siteId", site.getSiteId().toString())
                .param("courseId", course.getCourseId().toString())
                .param("firstSpotId", first.getSpotId().toString())
                .param("secondSpotId", second.getSpotId().toString())
                .param("firstStartTime", "09:00").param("secondStartTime", "09:10")
                .param("weather", "비");
        String html = mvc.perform(duplicate).andExpect(status().isOk())
                .andExpect(view().name("admin/schedule-form"))
                .andExpect(model().attributeHasErrors("scheduleForm"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("이미 등록되어 있습니다.", "role=\"alert\"", "저장 중...",
                "scheduleForm.dataset.submitting");
        assertThat(schedules.count()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(2);

        service.createSchedule(member.getMemberId(), date.plusDays(1), site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), null, null, null);
        Long laterId = schedules.findByMemberMemberIdOrderByScheduleDateAsc(member.getMemberId()).get(1).getScheduleId();
        String editHtml = mvc.perform(post("/admin/schedules/" + laterId + "/edit")
                        .sessionAttr("loginAdminId", 1L)
                        .param("memberId", member.getMemberId().toString())
                        .param("scheduleDate", date.toString())
                        .param("siteId", site.getSiteId().toString())
                        .param("courseId", course.getCourseId().toString())
                        .param("firstSpotId", first.getSpotId().toString())
                        .param("secondSpotId", second.getSpotId().toString())
                        .param("firstStartTime", "09:00").param("secondStartTime", "09:10"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-edit"))
                .andExpect(model().attributeHasErrors("scheduleForm"))
                .andReturn().getResponse().getContentAsString();
        assertThat(editHtml).contains("이미 등록되어 있습니다.", "role=\"alert\"");
        assertThat(service.findScheduleResponse(laterId).getScheduleDate()).isEqualTo(date.plusDays(1));
    }

    @Test
    void firstAdminPostCreatesScheduleAndExistingDeleteStillWorks() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post("/admin/schedules/new").sessionAttr("loginAdminId", 1L)
                        .param("memberId", member.getMemberId().toString())
                        .param("scheduleDate", date.toString())
                        .param("siteId", site.getSiteId().toString())
                        .param("courseId", course.getCourseId().toString())
                        .param("firstSpotId", first.getSpotId().toString())
                        .param("secondSpotId", second.getSpotId().toString())
                        .param("firstStartTime", "09:00").param("secondStartTime", "09:10"))
                .andExpect(redirectedUrl("/admin"));
        assertThat(schedules.count()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(2);

        Long scheduleId = schedules.findByMemberMemberIdAndScheduleDate(member.getMemberId(), date)
                .getFirst().getScheduleId();
        mvc.perform(post("/admin/schedules/" + scheduleId + "/delete")
                        .sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/schedules"));
        assertThat(schedules.count()).isZero();
        assertThat(scheduleSpots.count()).isZero();
    }

    @Test
    void memberDeletionKeepsMasterData() {
        create();
        memberService.deleteMember(member.getMemberId());
        entityManager.flush();
        assertThat(scheduleSpots.count()).isZero();
        assertThat(schedules.count()).isZero();
        assertThat(spots.count()).isEqualTo(2);
        assertThat(courses.count()).isEqualTo(1);
        assertThat(sites.count()).isEqualTo(1);
    }
    @Test
    void rendersSchedulePagesAndRestoresInvalidForm() throws Exception {
        Long id = create();
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String path : new String[]{"/admin/schedules", "/admin/schedules/new",
                "/admin/schedules/" + id, "/admin/schedules/" + id + "/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk());
        }
        mvc.perform(get("/member").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk());
        mvc.perform(get("/member/api/schedules/" + id).sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseCode").value("COURSE-X"))
                .andExpect(jsonPath("$.spots[0].code").value("SPOT-X"))
                .andExpect(jsonPath("$.course").doesNotExist())
                .andExpect(jsonPath("$.spots[0].spotNo").doesNotExist());
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/spots"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].courseId").value(course.getCourseId()));
        for (String path : new String[]{"/admin/schedules/new", "/admin/schedules/" + id + "/edit"}) {
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L)
                            .param("memberId", member.getMemberId().toString())
                            .param("scheduleDate", date.toString())
                            .param("siteId", site.getSiteId().toString())
                            .param("courseId", course.getCourseId().toString())
                            .param("firstSpotId", "-1")
                            .param("secondSpotId", second.getSpotId().toString())
                            .param("firstStartTime", "09:00").param("secondStartTime", "09:10"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("scheduleForm", "firstSpotId"))
                    .andExpect(model().attributeExists("sites", "members"));
        }
    }

    @Test
    void memberPagesShowOnlyOwnSchedulesAndKeepAdminPages() throws Exception {
        Long ownId = create();
        Member other = members.save(new Member(101, 2, "other-member", "password"));
        service.createSchedule(other.getMemberId(), date.plusDays(1), site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), "흐림", 20.0, 60.0);
        entityManager.flush();
        Long otherId = schedules.findByMemberMemberIdOrderByScheduleDateAsc(other.getMemberId())
                .getFirst().getScheduleId();
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();

        String calendar = mvc.perform(get("/member").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(calendar).contains("eventClick", "window.location.assign(`/member/schedules/${scheduleId}`)",
                        "data-id=\"" + ownId + "\"", "href=\"/member/schedules\"")
                .doesNotContain("data-id=\"" + otherId + "\"");

        String list = mvc.perform(get("/member/schedules").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-list"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("href=\"/member/schedules/" + ownId + "\"", "달력으로 돌아가기",
                        "href=\"/member/schedules/new\"")
                .doesNotContain("/member/schedules/" + otherId, "other-member", "/admin/schedules");

        String detail = mvc.perform(get("/member/schedules/" + ownId)
                        .sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-detail"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("일정 상세 정보", "기온(℃)", "습도(%)", "스팟 일정",
                        "SPOT-X 첫 정원", "SPOT-Y 둘째 정원", "href=\"/member/schedules\"", "달력으로 돌아가기")
                .doesNotContain("/admin/schedules", "일정 수정", "일정 삭제");

        mvc.perform(get("/member/schedules/" + otherId).sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/member/schedules/-1").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/member/api/schedules/" + otherId).sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/member/schedules")).andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/member/schedules/" + ownId)).andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/member/schedules").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/admin/schedules").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/schedules/" + ownId).sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(redirectedUrl("/admin/login"));
        String adminList = mvc.perform(get("/admin/schedules").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-list"))
                .andReturn().getResponse().getContentAsString();
        assertThat(adminList).contains("/admin/schedules/" + ownId, "/admin/schedules/" + otherId,
                "새 일정 등록", "other-member");
        String adminDetail = mvc.perform(get("/admin/schedules/" + otherId).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-detail"))
                .andReturn().getResponse().getContentAsString();
        assertThat(adminDetail).contains("/admin/schedules/" + otherId + "/edit",
                "/admin/schedules/" + otherId + "/delete");
    }

    @Test
    void memberWithNoSchedulesSeesEmptyList() throws Exception {
        Member other = members.save(new Member(101, 2, "other-member", "password"));
        service.createSchedule(other.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), time, second.getSpotId(), time.plusMinutes(10), null, null, null);
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String list = mvc.perform(get("/member/schedules").sessionAttr("loginMemberId", member.getMemberId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("등록된 일정이 없습니다.", "달력으로 돌아가기",
                        "href=\"/member/schedules/new\"")
                .doesNotContain("/admin/schedules", "other-member");
    }

}
