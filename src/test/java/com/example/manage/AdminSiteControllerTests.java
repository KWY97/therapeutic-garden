package com.example.manage;

import com.example.manage.controller.AdminController;
import com.example.manage.controller.AdminImageController;
import com.example.manage.controller.AdminSiteController;
import com.example.manage.controller.AdminSpotController;
import com.example.manage.controller.ImageUploadExceptionHandler;
import com.example.manage.domain.Admin;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.SiteRepository;
import com.example.manage.service.AdminPlaceFormService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminSiteControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;
    @Autowired SiteService sites;
    @Autowired SiteRepository siteRepository;
    @Autowired HealingCourseService courses;
    @MockitoSpyBean AdminPlaceFormService placeForms;
    @Value("${kakao.maps.javascript-key}") String mapsKey;

    MockMvc mvc;
    MockHttpSession admin;
    Site site;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Admin account = admins.saveAndFlush(new Admin("site-controller-" + UUID.randomUUID(), encoder.encode("secret")));
        admin = new MockHttpSession();
        authentication.authenticateAdmin(admin, account);
        site = sites.createSite("Site 기준 " + UUID.randomUUID(), "기준 주소", 37.0, 127.0, 3);
    }

    private MockHttpServletRequestBuilder form(String path, String name) {
        return post(path).session(admin).with(csrf()).param("name", name).param("address", "수정 주소")
                .param("latitude", "38.5").param("longitude", "128.5").param("mapLevel", "5");
    }

    @Test
    void listFormsAndDetailKeepModelsAndImageContext() throws Exception {
        mvc.perform(get("/admin/sites").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-list"))
                .andExpect(model().attribute("siteList", hasSize(1)))
                .andExpect(model().attributeExists("sites"));
        mvc.perform(get("/admin/sites/new").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-form"))
                .andExpect(model().attributeExists("siteForm", "sites", "images", "imageTitle", "kakaoMapsJavaScriptKey"))
                .andExpect(model().attribute("siteImageEditor", true))
                .andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey));
        mvc.perform(get("/admin/sites/" + site.getSiteId()).session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-detail"))
                .andExpect(model().attributeExists("site", "sites", "images"))
                .andExpect(model().attribute("imageBasePath", "/admin/sites/" + site.getSiteId() + "/images"));
        mvc.perform(get("/admin/sites/" + site.getSiteId() + "/edit").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-edit"))
                .andExpect(model().attributeExists("siteForm", "siteId", "sites", "images", "kakaoMapsJavaScriptKey"));
        mvc.perform(get("/admin/sites/" + site.getSiteId() + "/spatial-layout").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/spatial-layout"));
    }

    @Test
    void createEditDeleteValidationAndMissingSiteKeepResponses() throws Exception {
        mvc.perform(post("/admin/sites/new").session(admin).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-form"))
                .andExpect(model().attributeHasFieldErrors("siteForm", "name", "address", "latitude", "longitude"))
                .andExpect(model().attributeExists("siteForm", "sites", "images", "imageSelectionNotice", "kakaoMapsJavaScriptKey"));
        mvc.perform(post("/admin/sites/" + site.getSiteId() + "/edit").session(admin).with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("admin/site-edit"))
                .andExpect(model().attributeHasFieldErrors("siteForm", "name", "address", "latitude", "longitude"))
                .andExpect(model().attributeExists("siteForm", "siteId", "sites", "images", "imageSelectionNotice", "kakaoMapsJavaScriptKey"));

        mvc.perform(form("/admin/sites/new", "새 사이트 " + UUID.randomUUID()))
                .andExpect(redirectedUrlPattern("/admin/sites/*"));
        Site created = sites.findAllSites().stream().filter(s -> !s.getSiteId().equals(site.getSiteId()))
                .findFirst().orElseThrow();
        assertThat(created.getLatitude()).isEqualTo(38.5);
        mvc.perform(form("/admin/sites/" + created.getSiteId() + "/edit", "변경 사이트 " + UUID.randomUUID()))
                .andExpect(redirectedUrl("/admin/sites/" + created.getSiteId()));
        assertThat(sites.findSite(created.getSiteId()).getLongitude()).isEqualTo(128.5);
        mvc.perform(post("/admin/sites/" + created.getSiteId() + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/sites"))
                .andExpect(flash().attribute("successMessage", "사이트를 삭제했습니다."));
        assertThat(siteRepository.existsById(created.getSiteId())).isFalse();

        for (String path : new String[]{"/admin/sites/-1", "/admin/sites/-1/edit"}) {
            mvc.perform(get(path).session(admin))
                    .andExpect(redirectedUrl("/admin/sites"))
                    .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
        }
        mvc.perform(form("/admin/sites/-1/edit", "없는 사이트"))
                .andExpect(redirectedUrl("/admin/sites"))
                .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
        mvc.perform(post("/admin/sites/-1/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/sites"))
                .andExpect(flash().attribute("errorMessage", "존재하지 않는 사이트입니다."));
    }

    @Test
    void multipartAdviceFollowsSiteFormsWithoutExpandingScope() throws Exception {
        assertThat(ImageUploadExceptionHandler.class.getAnnotation(ControllerAdvice.class).assignableTypes())
                .containsExactlyInAnyOrder(AdminImageController.class, AdminSiteController.class, AdminSpotController.class);
        doThrow(new MaxUploadSizeExceededException(10))
                .when(placeForms).createSite(any(), any());
        mvc.perform(multipart("/admin/sites/new").session(admin).with(csrf())
                        .param("name", "새 사이트").param("address", "주소")
                        .param("latitude", "37.0").param("longitude", "127.0").param("mapLevel", "3"))
                .andExpect(redirectedUrl("/admin/sites/new"))
                .andExpect(flash().attribute("errorMessage",
                        "업로드 용량을 초과했습니다. 파일당 10MB 이하, 한 번에 최대 10장을 선택해 주세요."));
        doThrow(new MultipartException("bad multipart"))
                .when(placeForms).updateSite(any(), any(), any());
        mvc.perform(multipart("/admin/sites/" + site.getSiteId() + "/edit").session(admin).with(csrf())
                        .param("name", site.getName()).param("address", "주소")
                        .param("latitude", "37.0").param("longitude", "127.0").param("mapLevel", "3"))
                .andExpect(redirectedUrl("/admin/sites/" + site.getSiteId() + "/edit"))
                .andExpect(flash().attribute("errorMessage", "파일 업로드 요청을 처리하지 못했습니다. 파일을 다시 선택하고 시도해 주세요."));
        assertThat(siteRepository.existsById(site.getSiteId())).isTrue();
    }

    @Test
    void scheduleAndSeparatedControllersKeepSitesModel() throws Exception {
        HealingCourse course = courses.createHealingCourse(site.getSiteId(), "HC-A", "코스", 37.0, 127.0, 50.0);
        mvc.perform(get("/admin/schedules/new").session(admin))
                .andExpect(status().isOk()).andExpect(model().attribute("sites", hasSize(1)));
        mvc.perform(get("/admin/schedules").session(admin))
                .andExpect(status().isOk()).andExpect(model().attributeExists("sites"));
        mvc.perform(get("/admin/courses").session(admin))
                .andExpect(status().isOk()).andExpect(model().attributeExists("sites"));
        mvc.perform(get("/admin/spots/new").session(admin).param("courseId", course.getCourseId().toString()))
                .andExpect(status().isOk()).andExpect(model().attributeExists("sites"));
        mvc.perform(get("/admin/members").session(admin))
                .andExpect(status().isOk()).andExpect(model().attributeExists("sites"));
    }

    @Test
    void adminBoundaryCsrfAndMappingsStayIntact() throws Exception {
        Member member = members.saveAndFlush(new Member(1_800_000_004, 1,
                "site-member-" + UUID.randomUUID(), encoder.encode("secret")));
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/sites")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/sites/new").session(participant)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/sites/" + site.getSiteId() + "/delete").session(participant).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        for (String path : new String[]{"/admin/sites/new", "/admin/sites/" + site.getSiteId() + "/edit",
                "/admin/sites/" + site.getSiteId() + "/delete"}) {
            mvc.perform(post(path).session(admin)).andExpect(status().isForbidden());
        }
        assertThat(siteRepository.existsById(site.getSiteId())).isTrue();
        assertThat(mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().equals(AdminSiteController.class))).hasSize(7);
        assertThat(mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(AdminController.class))
                .noneMatch(entry -> entry.getKey().toString().contains("/admin/sites"))).isTrue();
    }
}
