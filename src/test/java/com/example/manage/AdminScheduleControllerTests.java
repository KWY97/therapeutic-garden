package com.example.manage;

import com.example.manage.controller.AdminController;
import com.example.manage.controller.AdminScheduleController;
import com.example.manage.domain.Admin;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.dto.ScheduleForm;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.ScheduleSpotRepository;
import com.example.manage.repository.SiteRepository;
import com.example.manage.service.ScheduleService;
import com.example.manage.service.SessionAuthentication;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminScheduleControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired ScheduleService service;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;

    MockMvc mvc;
    MockHttpSession admin;
    Member member, otherMember;
    Site site;
    HealingCourse course;
    HealingSpot first, second;
    LocalDate date = LocalDate.of(2026, 10, 28);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Admin account = admins.saveAndFlush(new Admin("schedule-controller-" + UUID.randomUUID(), encoder.encode("secret")));
        admin = new MockHttpSession();
        authentication.authenticateAdmin(admin, account);
        member = members.saveAndFlush(new Member(1_800_000_010, 1,
                "schedule-member-" + UUID.randomUUID(), encoder.encode("secret")));
        otherMember = members.saveAndFlush(new Member(1_800_000_011, 2,
                "schedule-other-" + UUID.randomUUID(), encoder.encode("secret")));
        site = sites.saveAndFlush(new Site("Schedule 사이트 " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        course = courses.saveAndFlush(new HealingCourse(site, "HC-A", "코스", 37.0, 127.0, 50.0));
        first = spots.saveAndFlush(new HealingSpot(course, "HS-A", "첫 스팟", 37.1, 127.1));
        second = spots.saveAndFlush(new HealingSpot(course, "HS-B", "둘째 스팟", 37.2, 127.2));
    }

    private MockHttpServletRequestBuilder form(String path, Member owner, LocalDate day) {
        return post(path).session(admin).with(csrf())
                .param("memberId", owner.getMemberId().toString())
                .param("scheduleDate", day.toString())
                .param("siteId", site.getSiteId().toString())
                .param("courseId", course.getCourseId().toString())
                .param("firstSpotId", first.getSpotId().toString())
                .param("firstStartTime", "09:00")
                .param("secondSpotId", second.getSpotId().toString())
                .param("secondStartTime", "09:10")
                .param("weather", "맑음").param("temperature", "24.5").param("humidity", "61.0");
    }

    private Long createSchedule() {
        service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                first.getSpotId(), LocalTime.of(9, 0), second.getSpotId(), LocalTime.of(9, 10),
                "맑음", 24.5, 61.0);
        return schedules.findByMemberMemberIdAndScheduleDate(member.getMemberId(), date).getFirst().getScheduleId();
    }

    @Test
    void listCalendarAndNewFormKeepModelsAndAdminLinks() throws Exception {
        Long id = createSchedule();
        mvc.perform(get("/admin/schedules").session(admin).param("sort", "group"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-list"))
                .andExpect(model().attribute("sort", "group"))
                .andExpect(model().attribute("schedules", hasSize(1)))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andExpect(content().string(containsString("/admin/schedules/" + id)));
        mvc.perform(get("/admin/schedules/calendar").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-calendar"))
                .andExpect(model().attribute("schedules", hasSize(1)))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andExpect(content().string(containsString("?from=calendar")));
        mvc.perform(get("/admin/schedules/new").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-form"))
                .andExpect(model().attribute("sites", hasSize(1)))
                .andExpect(model().attribute("members", hasSize(2)))
                .andExpect(model().attributeExists("scheduleForm"))
                .andExpect(content().string(containsString("data-weather-url=\"/admin/weather\"")))
                .andExpect(content().string(containsString("action=\"/admin/schedules/new\"")));
    }

    @Test
    void createDuplicateAndValidationKeepFormAndData() throws Exception {
        mvc.perform(post("/admin/schedules/new").session(admin).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-form"))
                .andExpect(model().attributeHasFieldErrors("scheduleForm", "memberId", "scheduleDate", "siteId",
                        "courseId", "firstSpotId", "firstStartTime", "secondSpotId", "secondStartTime"))
                .andExpect(model().attributeExists("sites", "members", "scheduleForm"));
        mvc.perform(form("/admin/schedules/new", member, date))
                .andExpect(redirectedUrl("/admin"));
        assertThat(schedules.count()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(2);
        var saved = service.findAllSchedules("date").getFirst();
        assertThat(saved.getScheduleDate()).isEqualTo(date);
        assertThat(saved.getWeather()).isEqualTo("맑음");
        assertThat(saved.getTemperature()).isEqualTo(24.5);
        assertThat(saved.getHumidity()).isEqualTo(61.0);
        mvc.perform(form("/admin/schedules/new", member, date))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-form"))
                .andExpect(model().attributeHasErrors("scheduleForm"))
                .andExpect(model().attributeExists("sites", "members", "scheduleForm"))
                .andExpect(content().string(containsString("이미 등록되어 있습니다.")));
        assertThat(schedules.count()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(2);
    }

    @Test
    void detailEditReassignmentAndCalendarReturnStayIntact() throws Exception {
        Long id = createSchedule();
        mvc.perform(get("/admin/schedules/" + id).session(admin).param("from", "calendar"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-detail"))
                .andExpect(model().attribute("from", "calendar"))
                .andExpect(model().attribute("scheduleSpots", hasSize(2)))
                .andExpect(model().attributeExists("schedule"))
                .andExpect(model().attributeDoesNotExist("sites"))
                .andExpect(content().string(containsString("href=\"/admin/schedules/calendar\"")));
        mvc.perform(get("/admin/schedules/" + id + "/edit").session(admin).param("from", "calendar"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-edit"))
                .andExpect(model().attribute("from", "calendar"))
                .andExpect(model().attribute("scheduleId", id))
                .andExpect(model().attribute("members", hasSize(2)))
                .andExpect(model().attribute("sites", hasSize(1)))
                .andExpect(result -> assertThat(((ScheduleForm) result.getModelAndView().getModel()
                        .get("scheduleForm")).getMemberId()).isEqualTo(member.getMemberId()));
        mvc.perform(post("/admin/schedules/" + id + "/edit").session(admin).with(csrf())
                        .param("from", "calendar"))
                .andExpect(status().isOk()).andExpect(view().name("admin/schedule-edit"))
                .andExpect(model().attributeHasErrors("scheduleForm"))
                .andExpect(model().attribute("from", "calendar"))
                .andExpect(model().attributeExists("sites", "members", "scheduleId", "scheduleForm"));
        mvc.perform(form("/admin/schedules/" + id + "/edit", otherMember, date.plusDays(1))
                        .param("from", "calendar"))
                .andExpect(redirectedUrl("/admin/schedules/" + id + "?from=calendar"));
        var updated = service.findScheduleResponse(id);
        assertThat(updated.getMember().getMemberId()).isEqualTo(otherMember.getMemberId());
        assertThat(updated.getScheduleDate()).isEqualTo(date.plusDays(1));
        assertThat(updated.getSpots()).hasSize(2);
    }

    @Test
    void deleteAndMissingScheduleKeepRedirects() throws Exception {
        for (String path : new String[]{"/admin/schedules/-1", "/admin/schedules/-1/edit"}) {
            mvc.perform(get(path).session(admin)).andExpect(redirectedUrl("/admin/schedules"));
        }
        mvc.perform(post("/admin/schedules/-1/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/schedules"));
        Long id = createSchedule();
        mvc.perform(post("/admin/schedules/" + id + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/schedules"));
        assertThat(schedules.existsById(id)).isFalse();
        assertThat(scheduleSpots.count()).isZero();
    }

    @Test
    void adminBoundaryCsrfWeatherAndMappingsStayIntact() throws Exception {
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/schedules")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/schedules/new").session(participant)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/schedules/new").session(participant).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/weather").session(participant)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isUnauthorized());
        for (String path : new String[]{"/admin/schedules/new", "/admin/schedules/1/edit",
                "/admin/schedules/1/delete"}) {
            mvc.perform(post(path).session(admin)).andExpect(status().isForbidden());
        }
        assertThat(schedules.count()).isZero();
        assertThat(mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().equals(AdminScheduleController.class))).hasSize(9);
        assertThat(mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(AdminController.class))
                .noneMatch(entry -> entry.getKey().toString().contains("/admin/schedules")
                        || entry.getKey().toString().contains("/admin/weather"))).isTrue();
    }
}
