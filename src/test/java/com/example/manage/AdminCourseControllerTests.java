package com.example.manage;

import com.example.manage.controller.AdminController;
import com.example.manage.controller.AdminCourseController;
import com.example.manage.domain.Admin;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.dto.HealingCourseForm;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.HealingCourseService;
import com.example.manage.service.SessionAuthentication;
import com.example.manage.service.SiteService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminCourseControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;
    @Autowired SiteService sites;
    @Autowired HealingCourseService courses;
    @Autowired HealingCourseRepository courseRepository;
    @Value("${kakao.maps.javascript-key}") String mapsKey;

    MockMvc mvc;
    MockHttpSession admin;
    Site site, other;
    HealingCourse course;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Admin account = admins.saveAndFlush(new Admin("course-controller-" + UUID.randomUUID(), encoder.encode("secret")));
        admin = new MockHttpSession();
        authentication.authenticateAdmin(admin, account);
        site = sites.createSite("Course 기준 사이트", "주소", 37.0, 127.0, 3);
        other = sites.createSite("Course 다른 사이트", "주소", 38.0, 128.0, 5);
        course = courses.createHealingCourse(site.getSiteId(), "HC-A", "기준 코스", 37.1, 127.1, 50.0);
    }

    private MockHttpServletRequestBuilder form(String path, Long siteId, String code) {
        return post(path).session(admin).with(csrf())
                .param("siteId", siteId.toString()).param("code", code).param("name", "변경 코스")
                .param("centerLatitude", "37.5").param("centerLongitude", "127.5").param("radius", "25.5");
    }

    @Test
    void listAndNewFormPreserveSiteFilterAndModel() throws Exception {
        courses.createHealingCourse(other.getSiteId(), "HC-B", "다른 코스", null, null, null);
        mvc.perform(get("/admin/courses").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/course-list"))
                .andExpect(model().attribute("courseSites", hasSize(2)))
                .andExpect(model().attributeDoesNotExist("sites"));
        mvc.perform(get("/admin/courses").session(admin).param("siteId", site.getSiteId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("admin/course-list"))
                .andExpect(model().attributeExists("selectedSite"))
                .andExpect(model().attribute("courses", hasSize(1)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HC-A")));
        mvc.perform(get("/admin/courses/new").session(admin).param("siteId", site.getSiteId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("admin/course-form"))
                .andExpect(model().attributeExists("healingCourseForm", "courseSites"))
                .andExpect(model().attribute("courseId", org.hamcrest.Matchers.nullValue()))
                .andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey))
                .andExpect(result -> assertThat(((HealingCourseForm) result.getModelAndView().getModel()
                        .get("healingCourseForm")).getSiteId()).isEqualTo(site.getSiteId()));
        for (String path : new String[]{"/admin/courses", "/admin/courses/new"}) {
            mvc.perform(get(path).session(admin).param("siteId", "-1"))
                    .andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
        }
    }

    @Test
    void createAndEditPreserveValidationAndSiteCoordinates() throws Exception {
        mvc.perform(form("/admin/courses/new", other.getSiteId(), "HC-A"))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + other.getSiteId()));
        HealingCourse created = courses.findBySiteId(other.getSiteId()).getFirst();
        assertThat(created.getCenterLatitude()).isEqualTo(37.5);
        assertThat(created.getCenterLongitude()).isEqualTo(127.5);
        assertThat(created.getRadius()).isEqualTo(25.5);

        courses.createHealingCourse(site.getSiteId(), "HC-D", "중복 대상", null, null, null);
        for (String path : new String[]{"/admin/courses/new", "/admin/courses/" + course.getCourseId() + "/edit"}) {
            mvc.perform(post(path).session(admin).with(csrf()))
                    .andExpect(status().isOk()).andExpect(view().name("admin/course-form"))
                    .andExpect(model().attributeHasFieldErrors("healingCourseForm", "siteId", "code", "name"))
                    .andExpect(model().attributeExists("healingCourseForm", "courseSites", "kakaoMapsJavaScriptKey"))
                    .andExpect(result -> assertThat(result.getModelAndView().getModel()).containsKey("courseId"));
            mvc.perform(form(path, site.getSiteId(), "HC-D"))
                    .andExpect(status().isOk()).andExpect(view().name("admin/course-form"))
                    .andExpect(model().attributeHasErrors("healingCourseForm"))
                    .andExpect(model().attributeExists("courseSites", "kakaoMapsJavaScriptKey"))
                    .andExpect(result -> assertThat(result.getModelAndView().getModel()).containsKey("courseId"));
        }
        mvc.perform(get("/admin/courses/" + course.getCourseId() + "/edit").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/course-form"))
                .andExpect(model().attribute("courseId", course.getCourseId()))
                .andExpect(model().attributeExists("healingCourseForm", "courseSites", "kakaoMapsJavaScriptKey"));
        mvc.perform(form("/admin/courses/" + course.getCourseId() + "/edit", other.getSiteId(), "HC-C"))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + other.getSiteId()));
        assertThat(courses.findHealingCourse(course.getCourseId()).getSite().getSiteId()).isEqualTo(other.getSiteId());
        assertThat(courses.findHealingCourse(course.getCourseId()).getCenterLatitude()).isEqualTo(37.5);
    }

    @Test
    void detailDeleteAndMissingCourseKeepRedirectAndFlash() throws Exception {
        mvc.perform(get("/admin/courses/" + course.getCourseId()).session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/course-detail"))
                .andExpect(model().attributeExists("course"))
                .andExpect(model().attributeDoesNotExist("sites"));
        for (String path : new String[]{"/admin/courses/-1", "/admin/courses/-1/edit"}) {
            mvc.perform(get(path).session(admin))
                    .andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attribute("errorMessage", "존재하지 않는 HealingCourse입니다."));
        }
        mvc.perform(post("/admin/courses/-1/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/courses"))
                .andExpect(flash().attribute("errorMessage", "존재하지 않는 HealingCourse입니다."));
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + site.getSiteId()))
                .andExpect(flash().attribute("successMessage", "HealingCourse를 삭제했습니다."));
        assertThat(courseRepository.existsById(course.getCourseId())).isFalse();
    }

    @Test
    void adminBoundaryCsrfAndOtherControllersRemainAvailable() throws Exception {
        Member member = members.saveAndFlush(new Member(1_800_000_001, 1,
                "course-member-" + UUID.randomUUID(), encoder.encode("secret")));
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/courses")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/courses").session(participant)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete").session(participant).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/courses/new").session(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/edit").session(admin))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete").session(admin))
                .andExpect(status().isForbidden());
        assertThat(courseRepository.existsById(course.getCourseId())).isTrue();
        mvc.perform(get("/admin/sites").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/spots").session(admin).param("courseId", course.getCourseId().toString()))
                .andExpect(status().isOk());

        assertThat(mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().equals(AdminCourseController.class))).hasSize(7);
        assertThat(mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(AdminController.class))
                .noneMatch(entry -> entry.getKey().toString().contains("/admin/courses"))).isTrue();
    }
}
