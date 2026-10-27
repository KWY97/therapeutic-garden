package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HealingCourseManagementTests {
    @Autowired HealingCourseService service;
    @Autowired SiteService siteService;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    Site site, other;
    HealingCourse course;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        site = siteService.createSite("HC 기준 사이트", "주소", 37.0, 127.0, 3);
        other = siteService.createSite("HC 다른 사이트", "주소", 38.0, 128.0, 5);
        course = create(site, "HC-A");
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).build();
    }

    HealingCourse create(Site owner, String code) {
        return service.createHealingCourse(owner.getSiteId(), code, "코스", 37.0, 127.0, 50.5);
    }

    MockHttpServletRequestBuilder form(String path, Long siteId, String code) {
        return post(path).sessionAttr("loginAdminId", 1L).param("siteId", siteId.toString())
                .param("code", code).param("name", "변경 코스")
                .param("centerLatitude", "37.5").param("centerLongitude", "127.5").param("radius", "25.5");
    }

    private org.springframework.test.web.servlet.ResultMatcher inputAttributes(String name, String... attributes) {
        return result -> {
            String input = java.util.regex.Pattern.compile("<input\\b[^>]*>")
                    .matcher(result.getResponse().getContentAsString()).results()
                    .map(java.util.regex.MatchResult::group)
                    .filter(tag -> tag.contains("name=\"" + name + "\""))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing input: " + name));
            assertThat(input).contains(attributes).doesNotContain("disabled");
        };
    }

    @Test
    void duplicateCodesAreScopedToSite() {
        assertThatThrownBy(() -> create(site, "HC-A")).isInstanceOf(IllegalArgumentException.class);
        HealingCourse allowed = create(other, "HC-A");
        assertThat(service.findBySiteId(site.getSiteId())).extracting(HealingCourse::getCourseId)
                .containsExactly(course.getCourseId());
        assertThat(service.findBySiteId(other.getSiteId())).extracting(HealingCourse::getCourseId)
                .containsExactly(allowed.getCourseId());
    }

    @Test
    void updatesWithDirtyCheckingAndExcludesSelf() {
        service.updateHealingCourse(course.getCourseId(), site.getSiteId(), "HC-A", "수정", 38.0, 128.0, 20.5);
        em.flush(); em.clear();
        HealingCourse saved = service.findHealingCourse(course.getCourseId());
        assertThat(saved.getName()).isEqualTo("수정");
        assertThat(saved.getRadius()).isEqualTo(20.5);
        assertThat(saved.getCenterLatitude()).isEqualTo(38.0);
        assertThat(saved.getCenterLongitude()).isEqualTo(128.0);
        service.updateHealingCourse(course.getCourseId(), other.getSiteId(), "HC-A", "이동", null, null, null);
        em.flush(); em.clear();
        assertThat(service.findHealingCourse(course.getCourseId()).getSite().getSiteId()).isEqualTo(other.getSiteId());
    }

    @Test
    void rejectsConflictsIncludingMovingToAnotherSite() {
        create(site, "HC-B"); create(other, "HC-A");
        assertThatThrownBy(() -> service.updateHealingCourse(course.getCourseId(), site.getSiteId(), "HC-B", "수정", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateHealingCourse(course.getCourseId(), other.getSiteId(), "HC-A", "수정", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(course.getCode()).isEqualTo("HC-A");
        assertThat(course.getSite().getSiteId()).isEqualTo(site.getSiteId());
    }

    @Test
    void blocksDeletionWithSpotsAndPreservesData() throws Exception {
        HealingSpot spot = spots.save(new HealingSpot(course, "HS-A", "스팟", 37.0, 127.0));
        assertThatThrownBy(() -> service.deleteHealingCourse(course.getCourseId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HealingSpot");
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(flash().attribute("errorMessage", "등록된 HealingSpot이 존재하는 HealingCourse는 삭제할 수 없습니다."));
        em.flush(); em.clear();
        assertThat(courses.existsById(course.getCourseId())).isTrue();
        assertThat(spots.existsById(spot.getSpotId())).isTrue();
    }

    @Test
    void handlesMissingEntities() throws Exception {
        assertThatThrownBy(() -> service.findHealingCourse(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteHealingCourse(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createHealingCourse(-1L, "A", "코스", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateHealingCourse(course.getCourseId(), -1L, "A", "코스", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        for (String path : new String[]{"/admin/courses?siteId=-1", "/admin/courses/new?siteId=-1", "/admin/courses/-1/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attributeExists("errorMessage"));
        }
        mvc.perform(form("/admin/courses/-1/edit", site.getSiteId(), "A"))
                .andExpect(content().string(containsString("존재하지 않는 HealingCourse입니다.")));
        mvc.perform(form("/admin/courses/new", -1L, "A"))
                .andExpect(content().string(containsString("존재하지 않는 사이트입니다.")));
    }

    @Test
    void rendersTemplatesAndPreservesHomeApis() throws Exception {
        for (String path : new String[]{"/admin/courses", "/admin/courses?siteId=" + site.getSiteId(),
                "/admin/courses?siteId=" + other.getSiteId(), "/admin/courses/new",
                "/admin/courses/new?siteId=" + site.getSiteId(), "/admin/courses/" + course.getCourseId() + "/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/courses/" + course.getCourseId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("value=\"50.5\"")))
                .andExpect(content().string(containsString("data-map-level=\"3\"")));
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/courses"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("HC-A"));
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/spots")).andExpect(status().isOk());
        mvc.perform(get("/admin/monitoring").sessionAttr("loginAdminId", 1L)).andExpect(status().isOk());
        service.updateHealingCourse(course.getCourseId(), site.getSiteId(), "HC-A", "빈 좌표", null, null, null);
        mvc.perform(get("/admin/courses/" + course.getCourseId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk());
    }

    @Test
    void courseFormsKeepHiddenCentersAndMapControls() throws Exception {
        for (String path : new String[]{"/admin/courses/new", "/admin/courses/" + course.getCourseId() + "/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(status().isOk())
                    .andExpect(inputAttributes("centerLatitude", "type=\"hidden\""))
                    .andExpect(inputAttributes("centerLongitude", "type=\"hidden\""))
                    .andExpect(inputAttributes("radius", "type=\"number\""))
                    .andExpect(content().string(containsString("/js/admin-course-map.js")))
                    .andExpect(content().string(containsString("지도를 클릭해 코스의 중심 위치를 지정하고, 반경을 입력해 영역을 설정할 수 있습니다.")));
        }
    }

    @Test
    void validatesNumbersAndDuplicatesOnBothForms() throws Exception {
        create(site, "HC-B");
        for (String path : new String[]{"/admin/courses/new", "/admin/courses/" + course.getCourseId() + "/edit"}) {
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(model().attributeHasFieldErrors("healingCourseForm", "siteId", "code", "name"));
            mvc.perform(form(path, site.getSiteId(), "HC-B"))
                    .andExpect(content().string(containsString("선택한 사이트에 이미 등록된 HC 코드입니다.")));
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L).param("siteId", site.getSiteId().toString())
                            .param("code", "C").param("name", "코스").param("centerLatitude", "abc")
                            .param("centerLongitude", "181").param("radius", "-1"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("healingCourseForm", "centerLatitude", "centerLongitude", "radius"))
                    .andExpect(content().string(containsString("지도를 클릭해 코스의 중심 위치를 다시 지정해 주세요.")));
        }
    }

    @Test
    void adminCrudAndLoginProtection() throws Exception {
        mvc.perform(get("/admin/courses")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(form("/admin/courses/new", other.getSiteId(), "HC-A"))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + other.getSiteId()));
        mvc.perform(form("/admin/courses/" + course.getCourseId() + "/edit", site.getSiteId(), "HC-A"))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + site.getSiteId()));
        em.flush(); em.clear();
        assertThat(service.findHealingCourse(course.getCourseId()).getRadius()).isEqualTo(25.5);
        mvc.perform(post("/admin/courses/" + course.getCourseId() + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/courses?siteId=" + site.getSiteId()));
        em.flush(); em.clear();
        assertThat(courses.existsById(course.getCourseId())).isFalse();
    }
}
