package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HealingSpotManagementTests {
    @Autowired HealingSpotService service;
    @Autowired SiteService siteService;
    @Autowired HealingCourseService courseService;
    @Autowired HealingSpotRepository spots;
    @Autowired MemberRepository members;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    @Value("${kakao.maps.javascript-key}") String mapsKey;
    Site site, otherSite;
    HealingCourse course, sibling, other;
    HealingSpot spot;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        site = siteService.createSite("HS 기준 사이트", "주소", 37.0, 127.0, 3);
        otherSite = siteService.createSite("HS 다른 사이트", "주소", 38.0, 128.0, 5);
        course = courseService.createHealingCourse(site, "HC-A", "회복 코스", null, null, null);
        sibling = courseService.createHealingCourse(site, "HC-B", "감각 코스", 37.1, 127.1, 50.0);
        other = courseService.createHealingCourse(otherSite, "HC-A", "다른 코스", null, null, null);
        spot = create(course, "HS1");
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).build();
    }

    HealingSpot create(HealingCourse owner, String code) {
        return service.createHealingSpot(owner.getCourseId(), code, "곶자왈원", 37.2, 127.2);
    }

    void reload() { em.flush(); em.clear(); }

    MockHttpServletRequestBuilder form(String path, Long courseId, String code) {
        return post(path).sessionAttr("loginAdminId", 1L).param("courseId", courseId.toString())
                .param("code", code).param("name", "변경 스팟")
                .param("latitude", "37.5").param("longitude", "127.5");
    }

    private org.springframework.test.web.servlet.ResultMatcher inputAttributes(String name, String... attributes) {
        return result -> {
            var inputs = java.util.regex.Pattern.compile("<input\\b[^>]*>")
                    .matcher(result.getResponse().getContentAsString()).results()
                    .map(java.util.regex.MatchResult::group)
                    .filter(tag -> tag.contains("name=\"" + name + "\""))
                    .toList();
            assertThat(inputs).hasSize(1);
            assertThat(inputs.get(0)).contains(attributes).doesNotContain("disabled");
        };
    }

    @Test
    void createsAndListsByCourse() {
        HealingSpot second = create(sibling, "HS2");
        reload();
        assertThat(service.findByCourseId(course.getCourseId())).extracting(HealingSpot::getSpotId)
                .containsExactly(spot.getSpotId());
        assertThat(service.findByCourseId(sibling.getCourseId())).extracting(HealingSpot::getSpotId)
                .containsExactly(second.getSpotId());
        HealingSpot saved = service.findHealingSpot(spot.getSpotId());
        assertThat(saved.getName()).isEqualTo("곶자왈원");
        assertThat(saved.getLatitude()).isEqualTo(37.2);
        assertThat(saved.getLongitude()).isEqualTo(127.2);
    }

    @Test
    void duplicateCodesAreScopedToSiteAcrossCourses() {
        assertThatThrownBy(() -> create(course, "HS1")).hasMessage("선택한 사이트에 이미 등록된 HS 코드입니다.");
        assertThatThrownBy(() -> create(sibling, "HS1")).hasMessage("선택한 사이트에 이미 등록된 HS 코드입니다.");
        HealingSpot allowed = create(other, "HS1");
        assertThat(service.findByCourseId(other.getCourseId())).extracting(HealingSpot::getSpotId)
                .containsExactly(allowed.getSpotId());
        assertThatThrownBy(() -> service.createHealingSpot(sibling, "HS1", "다른 이름", 37.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatesWithDirtyCheckingAndExcludesSelf() {
        service.updateHealingSpot(spot.getSpotId(), sibling.getCourseId(), "HS1", "수정", 38.0, 128.0);
        reload();
        HealingSpot saved = service.findHealingSpot(spot.getSpotId());
        assertThat(saved.getCode()).isEqualTo("HS1");
        assertThat(saved.getName()).isEqualTo("수정");
        assertThat(saved.getLatitude()).isEqualTo(38.0);
        assertThat(saved.getLongitude()).isEqualTo(128.0);
        assertThat(saved.getHealingCourse().getCourseId()).isEqualTo(sibling.getCourseId());
        service.updateHealingSpot(spot.getSpotId(), other.getCourseId(), "HS1", "이동", 39.0, 129.0);
        reload();
        assertThat(service.findHealingSpot(spot.getSpotId()).getHealingCourse().getSite().getSiteId())
                .isEqualTo(otherSite.getSiteId());
    }

    @Test
    void rejectsUpdateConflictsInSameAndDestinationSites() {
        create(sibling, "HS2"); create(other, "HS1");
        assertThatThrownBy(() -> service.updateHealingSpot(spot.getSpotId(), course.getCourseId(), "HS2", "수정", 38.0, 128.0))
                .hasMessage("선택한 사이트에 이미 등록된 HS 코드입니다.");
        assertThatThrownBy(() -> service.updateHealingSpot(spot.getSpotId(), other.getCourseId(), "HS1", "수정", 38.0, 128.0))
                .hasMessage("선택한 사이트에 이미 등록된 HS 코드입니다.");
        reload();
        HealingSpot saved = service.findHealingSpot(spot.getSpotId());
        assertThat(saved.getCode()).isEqualTo("HS1");
        assertThat(saved.getLatitude()).isEqualTo(37.2);
        assertThat(saved.getHealingCourse().getCourseId()).isEqualTo(course.getCourseId());
    }

    @Test
    void handlesMissingEntities() throws Exception {
        assertThatThrownBy(() -> service.findHealingSpot(-1L)).hasMessage("존재하지 않는 HealingSpot입니다.");
        assertThatThrownBy(() -> service.deleteHealingSpot(-1L)).hasMessage("존재하지 않는 HealingSpot입니다.");
        assertThatThrownBy(() -> service.findByCourseId(-1L)).hasMessage("존재하지 않는 HealingCourse입니다.");
        assertThatThrownBy(() -> service.createHealingSpot(-1L, "HS2", "스팟", 37.0, 127.0))
                .hasMessage("존재하지 않는 HealingCourse입니다.");
        assertThatThrownBy(() -> service.createHealingSpot((Long) null, "HS2", "스팟", 37.0, 127.0))
                .hasMessage("코스를 선택해 주세요.");
        assertThatThrownBy(() -> service.updateHealingSpot(spot.getSpotId(), -1L, "HS2", "스팟", 37.0, 127.0))
                .hasMessage("존재하지 않는 HealingCourse입니다.");
        for (String path : new String[]{"/admin/spots?courseId=-1", "/admin/spots/new?courseId=-1", "/admin/spots/-1/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attributeExists("errorMessage"));
        }
        mvc.perform(form("/admin/spots/-1/edit", course.getCourseId(), "HS2"))
                .andExpect(content().string(containsString("존재하지 않는 HealingSpot입니다.")));
        mvc.perform(form("/admin/spots/new", -1L, "HS2"))
                .andExpect(content().string(containsString("존재하지 않는 HealingCourse입니다.")));
        mvc.perform(post("/admin/spots/-1/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/courses")).andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void deletesUnusedSpot() {
        service.deleteHealingSpot(spot.getSpotId());
        reload();
        assertThat(spots.existsById(spot.getSpotId())).isFalse();
        assertThat(courseService.findHealingCourse(course.getCourseId())).isNotNull();
    }

    @Test
    void blocksReferencedSpotDeletionWithoutCascading() throws Exception {
        Member member = members.save(new Member(101, 1, "hs-member", "password"));
        Schedule schedule = schedules.save(new Schedule(member, LocalDate.of(2026, 9, 17)));
        ScheduleSpot reference = scheduleSpots.save(new ScheduleSpot(schedule, spot, LocalTime.of(9, 0), 1));
        reload();
        assertThatThrownBy(() -> service.deleteHealingSpot(spot.getSpotId()))
                .hasMessage("일정에 사용 중인 HealingSpot은 삭제할 수 없습니다.");
        mvc.perform(post("/admin/spots/" + spot.getSpotId() + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/spots?courseId=" + course.getCourseId()))
                .andExpect(flash().attribute("errorMessage", "일정에 사용 중인 HealingSpot은 삭제할 수 없습니다."));
        reload();
        assertThat(spots.existsById(spot.getSpotId())).isTrue();
        assertThat(schedules.existsById(schedule.getScheduleId())).isTrue();
        ScheduleSpot saved = scheduleSpots.findById(reference.getScheduleSpotId()).orElseThrow();
        assertThat(saved.getHealingSpot().getSpotId()).isEqualTo(spot.getSpotId());
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(saved.getSequence()).isEqualTo(1);
        assertThat(scheduleSpots.count()).isEqualTo(1);
    }

    @Test
    void rendersHierarchyAndRejectsMismatchedSite() throws Exception {
        create(sibling, "HS2");
        mvc.perform(get("/admin/courses").param("siteId", site.getSiteId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("href=\"/admin/courses/" + course.getCourseId() + "\"")));
        mvc.perform(get("/admin/spots").param("courseId", course.getCourseId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(content().string(containsString("HS 기준 사이트 / HC-A 회복 코스")))
                .andExpect(model().attribute("spots", org.hamcrest.Matchers.hasSize(1)));
        for (String path : new String[]{"/admin/spots", "/admin/spots/new"}) {
            mvc.perform(get(path).param("courseId", course.getCourseId().toString())
                            .param("siteId", otherSite.getSiteId().toString()).sessionAttr("loginAdminId", 1L))
                    .andExpect(redirectedUrl("/admin/courses")).andExpect(flash().attributeExists("errorMessage"));
        }
        mvc.perform(get("/admin/spots").param("courseId", other.getCourseId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("등록된 HS가 없습니다.")));
    }

    @Test
    void formsKeepHiddenCoordinatesKeyAndExistingValues() throws Exception {
        for (String path : new String[]{"/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(status().isOk()).andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey))
                    .andExpect(inputAttributes("latitude", "type=\"hidden\""))
                    .andExpect(inputAttributes("longitude", "type=\"hidden\""))
                    .andExpect(content().string(containsString("/js/admin-spot-map.js")))
                    .andExpect(content().string(containsString("data-map-level=\"3\"")))
                    .andExpect(content().string(containsString("HS 다른 사이트 / HC-A 다른 코스")));
        }
        mvc.perform(get("/admin/spots/new").param("courseId", course.getCourseId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(inputAttributes("latitude", "value=\"\""))
                .andExpect(inputAttributes("longitude", "value=\"\""));
        mvc.perform(get("/admin/spots/" + spot.getSpotId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(inputAttributes("latitude", "value=\"37.2\""))
                .andExpect(inputAttributes("longitude", "value=\"127.2\""))
                .andExpect(inputAttributes("code", "value=\"HS1\""))
                .andExpect(inputAttributes("name", "value=\"곶자왈원\""))
                .andExpect(result -> assertThat(((com.example.manage.dto.HealingSpotForm)
                        result.getModelAndView().getModel().get("healingSpotForm")).getCourseId()).isEqualTo(course.getCourseId()));
    }

    @Test
    void validatesRequiredRangesAndDuplicatesOnBothForms() throws Exception {
        create(sibling, "HS2");
        for (String path : new String[]{"/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit"}) {
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(model().attributeHasFieldErrors("healingSpotForm", "courseId", "code", "name", "latitude", "longitude"))
                    .andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey));
            mvc.perform(form(path, course.getCourseId(), "HS2"))
                    .andExpect(content().string(containsString("선택한 사이트에 이미 등록된 HS 코드입니다.")))
                    .andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey))
                    .andExpect(inputAttributes("latitude", "value=\"37.5\""))
                    .andExpect(inputAttributes("longitude", "value=\"127.5\""));
            for (String[] coordinates : new String[][]{{"91", "181"}, {"-91", "-181"}, {"abc", "NaN"}, {"Infinity", "-Infinity"}}) {
                mvc.perform(post(path).sessionAttr("loginAdminId", 1L).param("courseId", course.getCourseId().toString())
                                .param("code", " ").param("name", " ").param("latitude", coordinates[0]).param("longitude", coordinates[1]))
                        .andExpect(status().isOk())
                        .andExpect(model().attributeHasFieldErrors("healingSpotForm", "code", "name", "latitude", "longitude"));
            }
        }
    }

    @Test
    void adminCrudAndLoginProtection() throws Exception {
        for (String path : new String[]{"/admin/spots", "/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit"}) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/admin/login"));
        }
        for (String path : new String[]{"/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit", "/admin/spots/" + spot.getSpotId() + "/delete"}) {
            mvc.perform(post(path)).andExpect(redirectedUrl("/admin/login"));
        }
        var created = mvc.perform(form("/admin/spots/new", other.getCourseId(), "HS1"))
                .andExpect(status().is3xxRedirection()).andReturn();
        Long createdId = service.findByCourseId(other.getCourseId()).getFirst().getSpotId();
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/admin/spots/" + createdId);
        mvc.perform(form("/admin/spots/" + spot.getSpotId() + "/edit", sibling.getCourseId(), "HS1"))
                .andExpect(redirectedUrl("/admin/spots/" + spot.getSpotId()));
        reload();
        assertThat(service.findHealingSpot(spot.getSpotId()).getLatitude()).isEqualTo(37.5);
        mvc.perform(post("/admin/spots/" + spot.getSpotId() + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/spots?courseId=" + sibling.getCourseId()));
        reload();
        assertThat(spots.existsById(spot.getSpotId())).isFalse();
    }
}
