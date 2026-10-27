package com.example.manage;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.ScheduleSpotRepository;
import com.example.manage.repository.SiteRepository;
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
class MemberScheduleCreateTests {
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(930_000_000);
    private final LocalDate date = LocalDate.of(2026, 10, 28);

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").with(LegacyAuthenticatedSession.hydrate(context)))
                .addFilters(security).build();
        own = member();
        other = member();
        site = sites.save(new Site("일정 사이트 " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        course = courses.save(new HealingCourse(site, "COURSE", "치유 코스", 37.0, 127.0, 50.0));
        first = spots.save(new HealingSpot(course, "FIRST", "첫 정원", 37.0, 127.0));
        second = spots.save(new HealingSpot(course, "SECOND", "둘째 정원", 37.0, 127.0));
    }

    private Member member() {
        return members.save(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                "schedule-" + UUID.randomUUID(), "password"));
    }

    private MockHttpSession session(Member member) {
        MockHttpSession session = new MockHttpSession();
        authentication.authenticateMember(session, member);
        return session;
    }

    private MockHttpServletRequestBuilder validPost(MockHttpSession session) {
        return post("/member/schedules/new").session(session).with(csrf())
                .param("scheduleDate", date.toString())
                .param("siteId", site.getSiteId().toString())
                .param("courseId", course.getCourseId().toString())
                .param("firstSpotId", first.getSpotId().toString())
                .param("firstStartTime", "09:00")
                .param("secondSpotId", second.getSpotId().toString())
                .param("secondStartTime", "09:10");
    }

    @Test
    void memberFormAndPostUseSessionIdentityAndAppearInListAndCalendar() throws Exception {
        MockHttpSession ownSession = session(own);
        String form = mvc.perform(get("/member/schedules/new").session(ownSession))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-form"))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("action=\"/member/schedules/new\"", "name=\"_csrf\"",
                "날씨 불러오기", "weather-lookup.js", "data-weather-url=\"/member/weather\"",
                "/api/sites/", "첫 번째 스팟", "두 번째 스팟")
                .doesNotContain("name=\"memberId\"", "id=\"memberId\"", other.getLoginId(),
                        "action=\"/admin/schedules/new\"", "data-weather-url=\"/admin/weather\"",
                        "관리자 메인으로");

        mvc.perform(validPost(ownSession).param("memberId", other.getMemberId().toString())
                        .param("scheduleForm.memberId", other.getMemberId().toString()))
                .andExpect(redirectedUrl("/member/schedules"));
        entityManager.flush();
        entityManager.clear();
        var created = schedules.findByMemberMemberIdAndScheduleDate(own.getMemberId(), date);
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().getMember().getMemberId()).isEqualTo(own.getMemberId());
        assertThat(schedules.findByMemberMemberIdAndScheduleDate(other.getMemberId(), date)).isEmpty();
        assertThat(scheduleSpots.findByScheduleScheduleIdOrderBySequenceAsc(created.getFirst().getScheduleId()))
                .hasSize(2);

        String list = mvc.perform(get("/member/schedules").session(ownSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("/member/schedules/" + created.getFirst().getScheduleId(),
                        "href=\"/member/schedules/new\"")
                .doesNotContain(other.getLoginId());
        String calendar = mvc.perform(get("/member").session(ownSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(calendar).contains("data-id=\"" + created.getFirst().getScheduleId() + "\"",
                        "href=\"/member/schedules\"", "일정 목록")
                .doesNotContain("href=\"/member/schedules/new\"", "새 일정 등록");
    }

    @Test
    void duplicatePostShowsErrorAndPreservesSelectionWithoutExtraSpots() throws Exception {
        MockHttpSession ownSession = session(own);
        mvc.perform(validPost(ownSession).param("weather", "맑음"))
                .andExpect(redirectedUrl("/member/schedules"));
        long spotCount = scheduleSpots.count();

        String form = mvc.perform(validPost(ownSession).param("weather", "흐림"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-form"))
                .andExpect(model().attributeHasErrors("scheduleForm"))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("이미 등록되어 있습니다.", "role=\"alert\"",
                "value=\"" + date + "\"", "data-selected=\"" + course.getCourseId() + "\"",
                "data-selected=\"" + first.getSpotId() + "\"");
        assertThat(schedules.findByMemberMemberIdAndScheduleDate(own.getMemberId(), date)).hasSize(1);
        assertThat(scheduleSpots.count()).isEqualTo(spotCount);
    }

    @Test
    void invalidFieldsAndServicePolicyReturnUsableForm() throws Exception {
        MockHttpSession ownSession = session(own);
        String invalid = mvc.perform(post("/member/schedules/new").session(ownSession).with(csrf())
                        .param("scheduleDate", date.toString())
                        .param("siteId", site.getSiteId().toString())
                        .param("courseId", course.getCourseId().toString())
                        .param("firstStartTime", "09:00")
                        .param("secondSpotId", second.getSpotId().toString())
                        .param("secondStartTime", "09:10"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("scheduleForm", "firstSpotId"))
                .andReturn().getResponse().getContentAsString();
        assertThat(invalid).contains("첫 번째 스팟을 선택해 주세요.", "value=\"" + date + "\"",
                "data-selected=\"" + course.getCourseId() + "\"", "action=\"/member/schedules/new\"");
        assertThat(schedules.count()).isZero();

        own.configureTestAnalysis(other.getMemberId());
        String restricted = mvc.perform(validPost(ownSession))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("scheduleForm"))
                .andReturn().getResponse().getContentAsString();
        assertThat(restricted).contains("테스트 계정에는 일정을 등록할 수 없습니다.");
        assertThat(schedules.count()).isZero();
    }

    @Test
    void loginCsrfAndAdminRoutesRemainSeparate() throws Exception {
        MockHttpSession ownSession = session(own);
        mvc.perform(get("/member/schedules/new")).andExpect(redirectedUrl("/member/login"));
        mvc.perform(post("/member/schedules/new").with(csrf()))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(post("/member/schedules/new").session(ownSession).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/member/schedules/new").session(ownSession))
                .andExpect(status().isForbidden());
        mvc.perform(get("/member/schedules/new").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/admin/schedules/new").session(ownSession))
                .andExpect(redirectedUrl("/admin/login"));

        String adminForm = mvc.perform(get("/admin/schedules/new").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminForm).contains("action=\"/admin/schedules/new\"", "name=\"memberId\"",
                "data-weather-url=\"/admin/weather\"", "참가자를 선택해 주세요");
    }
}
