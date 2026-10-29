package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.SiteService;
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
class SiteManagementTests {

    @Autowired SiteService service;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired EntityManager entityManager;
    @Autowired WebApplicationContext context;

    private Site site;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        site = service.createSite("기준 사이트", "서울 주소", 37.0, 127.0, 3);
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).build();
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private MockHttpServletRequestBuilder form(String path, String name) {
        return post(path).sessionAttr("loginAdminId", 1L)
                .param("name", name).param("address", "수정 주소")
                .param("latitude", "38.5").param("longitude", "128.5").param("mapLevel", "5");
    }

    private org.springframework.test.web.servlet.ResultActions expectLocationForm(
            MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("kakaoMapsJavaScriptKey"))
                .andExpect(inputAttributes("address", "readonly"))
                .andExpect(inputAttributes("latitude", "type=\"hidden\""))
                .andExpect(inputAttributes("longitude", "type=\"hidden\""))
                .andExpect(content().string(containsString("id=\"site-location-map\"")))
                .andExpect(content().string(containsString("id=\"site-location-search\" type=\"button\"")))
                .andExpect(content().string(containsString("id=\"site-postcode-modal\"")))
                .andExpect(content().string(containsString("aria-labelledby=\"site-postcode-title\"")))
                .andExpect(content().string(containsString("id=\"site-postcode-embed\"")))
                .andExpect(content().string(containsString("id=\"site-postcode-close\" type=\"button\"")))
                .andExpect(content().string(containsString("https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js")))
                .andExpect(content().string(containsString("libraries=services")))
                .andExpect(content().string(containsString("/js/admin-site-map.js")));
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
    void locationFormsRetainConfigurationAndExistingValues() throws Exception {
        expectLocationForm(get("/admin/sites/new").sessionAttr("loginAdminId", 1L))
                .andExpect(view().name("admin/site-form"))
                .andExpect(inputAttributes("mapLevel", "value=\"3\""));
        service.updateSite(site.getSiteId(), site.getName(), site.getAddress(), 37.0, 127.0, 7);
        expectLocationForm(get("/admin/sites/" + site.getSiteId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(view().name("admin/site-edit"))
                .andExpect(content().string(containsString("value=\"37.0\"")))
                .andExpect(content().string(containsString("value=\"127.0\"")))
                .andExpect(content().string(containsString("value=\"서울 주소\"")))
                .andExpect(inputAttributes("mapLevel", "value=\"7\""));
    }

    @Test
    void locationConfigurationSurvivesValidationAndDuplicateErrors() throws Exception {
        Site other = service.createSite("중복 대상", "주소", 0.0, 0.0, 1);
        for (String path : new String[]{"/admin/sites/new", "/admin/sites/" + site.getSiteId() + "/edit"}) {
            expectLocationForm(post(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(model().attributeHasFieldErrors("siteForm", "latitude", "longitude"));
            expectLocationForm(form(path, other.getName()))
                    .andExpect(model().attributeHasFieldErrors("siteForm", "name"))
                    .andExpect(content().string(containsString("value=\"38.5\"")))
                    .andExpect(content().string(containsString("value=\"128.5\"")))
                    .andExpect(content().string(containsString("value=\"수정 주소\"")))
                    .andExpect(inputAttributes("mapLevel", "value=\"5\""));
        }
    }

    @Test
    void createsAndReadsSite() {
        reload();
        Site saved = service.findSite(site.getSiteId());
        assertThat(saved.getName()).isEqualTo("기준 사이트");
        assertThat(saved.getAddress()).isEqualTo("서울 주소");
        assertThat(saved.getLatitude()).isEqualTo(37.0);
        assertThat(saved.getLongitude()).isEqualTo(127.0);
        assertThat(saved.getMapLevel()).isEqualTo(3);
        assertThat(service.findAllSites()).extracting(Site::getSiteId).contains(site.getSiteId());
        assertThat(service.findSite(-1L)).isNull();
    }

    @Test
    void rejectsDuplicateCreation() {
        assertThatThrownBy(() -> service.createSite("기준 사이트", "다른 주소", 0.0, 0.0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("이미 등록된 사이트명입니다.");
        assertThat(sites.count()).isEqualTo(1);
    }

    @Test
    void updatesAllFieldsAndHomeReadsUpdatedData() throws Exception {
        service.updateSite(site.getSiteId(), "변경 사이트", "변경 주소", 38.5, 128.5, 5);
        reload();
        Site updated = service.findSite(site.getSiteId());
        assertThat(updated.getName()).isEqualTo("변경 사이트");
        assertThat(updated.getAddress()).isEqualTo("변경 주소");
        assertThat(updated.getLatitude()).isEqualTo(38.5);
        assertThat(updated.getLongitude()).isEqualTo(128.5);
        assertThat(updated.getMapLevel()).isEqualTo(5);
        mvc.perform(get("/admin/monitoring").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("변경 사이트")))
                .andExpect(content().string(containsString("data-latitude=\"38.5\"")))
                .andExpect(content().string(containsString("data-map-level=\"5\"")));
    }

    @Test
    void allowsUnchangedName() {
        service.updateSite(site.getSiteId(), site.getName(), "새 주소", 0.0, 0.0, 1);
        reload();
        assertThat(service.findSite(site.getSiteId()).getAddress()).isEqualTo("새 주소");
    }

    @Test
    void rejectsAnotherSitesNameWithoutChangingFields() {
        service.createSite("다른 사이트", "주소", 0.0, 0.0, 1);
        assertThatThrownBy(() -> service.updateSite(site.getSiteId(), "다른 사이트", "새 주소", 0.0, 0.0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("이미 등록된 사이트명입니다.");
        reload();
        assertThat(service.findSite(site.getSiteId()).getName()).isEqualTo("기준 사이트");
        assertThat(service.findSite(site.getSiteId()).getAddress()).isEqualTo("서울 주소");
    }

    @Test
    void deletesSiteWithoutCourses() {
        service.deleteSite(site.getSiteId());
        reload();
        assertThat(sites.existsById(site.getSiteId())).isFalse();
    }

    @Test
    void blocksDeletionAndPreservesSiteCourseAndSpot() {
        HealingCourse course = courses.save(new HealingCourse(site, "C1", "기준 코스", 37.0, 127.0, 50.0));
        HealingSpot spot = spots.save(new HealingSpot(course, "S1", "기준 스팟", 37.0, 127.0));
        reload();
        assertThatThrownBy(() -> service.deleteSite(site.getSiteId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("등록된 HealingCourse가 존재하는 사이트는 삭제할 수 없습니다.");
        reload();
        assertThat(sites.findById(site.getSiteId())).isPresent();
        assertThat(courses.findById(course.getCourseId())).isPresent();
        assertThat(spots.findById(spot.getSpotId())).isPresent();
        assertThat(courses.findById(course.getCourseId()).orElseThrow().getSite().getSiteId()).isEqualTo(site.getSiteId());
        assertThat(spots.findById(spot.getSpotId()).orElseThrow().getHealingCourse().getCourseId()).isEqualTo(course.getCourseId());
    }

    @Test
    void rendersAdminPages() throws Exception {
        for (String path : new String[]{"/admin/sites", "/admin/sites/new",
                "/admin/sites/" + site.getSiteId(), "/admin/sites/" + site.getSiteId() + "/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("사이트")));
        }
        mvc.perform(get("/admin/sites/" + site.getSiteId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("value=\"기준 사이트\"")));
        mvc.perform(get("/admin").sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("/admin/sites")));
    }

    @Test
    void validatesMissingAndOutOfRangeInputsOnCreateAndEdit() throws Exception {
        for (String path : new String[]{"/admin/sites/new", "/admin/sites/" + site.getSiteId() + "/edit"}) {
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L).param("mapLevel", ""))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("siteForm", "name", "address", "latitude", "longitude", "mapLevel"))
                    .andExpect(inputAttributes("mapLevel", "value=\"\""));
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L)
                            .param("name", " ").param("address", " ")
                            .param("latitude", "91").param("longitude", "-181").param("mapLevel", "15"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("siteForm", "name", "address", "latitude", "longitude", "mapLevel"));
            mvc.perform(post(path).sessionAttr("loginAdminId", 1L)
                            .param("latitude", "not-a-number").param("longitude", "181").param("mapLevel", "1.5"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("siteForm", "latitude", "longitude", "mapLevel"))
                    .andExpect(content().string(containsString("주소를 검색하거나 지도를 클릭해 위치를 다시 지정해 주세요.")));
        }
        assertThat(sites.count()).isEqualTo(1);
        assertThat(service.findSite(site.getSiteId()).getName()).isEqualTo("기준 사이트");
    }

    @Test
    void createsUpdatesAndDeletesThroughAdmin() throws Exception {
        var created = mvc.perform(form("/admin/sites/new", "새 사이트")).andExpect(status().is3xxRedirection()).andReturn();
        Long id = sites.findAll().stream().filter(s -> s.getName().equals("새 사이트")).findFirst().orElseThrow().getSiteId();
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/admin/sites/" + id);
        reload();
        assertThat(service.findSite(id).getLatitude()).isEqualTo(38.5);
        assertThat(service.findSite(id).getLongitude()).isEqualTo(128.5);
        mvc.perform(form("/admin/sites/" + id + "/edit", "수정 사이트"))
                .andExpect(redirectedUrl("/admin/sites/" + id));
        reload();
        assertThat(service.findSite(id).getName()).isEqualTo("수정 사이트");
        assertThat(service.findSite(id).getLatitude()).isEqualTo(38.5);
        assertThat(service.findSite(id).getLongitude()).isEqualTo(128.5);
        mvc.perform(post("/admin/sites/" + id + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/sites"));
        reload();
        assertThat(service.findSite(id)).isNull();
    }

    @Test
    void displaysDuplicateAndBlockedDeletionMessages() throws Exception {
        mvc.perform(form("/admin/sites/new", site.getName()))
                .andExpect(model().attributeHasFieldErrors("siteForm", "name"))
                .andExpect(content().string(containsString("이미 등록된 사이트명입니다.")));
        Site other = service.createSite("다른 사이트", "주소", 0.0, 0.0, 1);
        mvc.perform(form("/admin/sites/" + site.getSiteId() + "/edit", other.getName()))
                .andExpect(model().attributeHasFieldErrors("siteForm", "name"));
        courses.save(new HealingCourse(site, "C1", "코스", 37.0, 127.0, 50.0));
        var result = mvc.perform(post("/admin/sites/" + site.getSiteId() + "/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/sites"))
                .andExpect(flash().attribute("errorMessage", "등록된 HealingCourse가 존재하는 사이트는 삭제할 수 없습니다."))
                .andReturn();
        mvc.perform(get("/admin/sites").sessionAttr("loginAdminId", 1L).flashAttrs(result.getFlashMap()))
                .andExpect(content().string(containsString("등록된 HealingCourse가 존재하는 사이트는 삭제할 수 없습니다.")));
    }

    @Test
    void handlesUnknownSitesAndRequiresAdminLogin() throws Exception {
        for (String path : new String[]{"/admin/sites/-1", "/admin/sites/-1/edit"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(redirectedUrl("/admin/sites"))
                    .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
        }
        mvc.perform(form("/admin/sites/-1/edit", "없는 사이트")).andExpect(redirectedUrl("/admin/sites"));
        mvc.perform(post("/admin/sites/-1/delete").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/sites"))
                .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
        mvc.perform(get("/admin/sites")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/sites/" + site.getSiteId() + "/delete"))
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(sites.existsById(site.getSiteId())).isTrue();
    }
}
