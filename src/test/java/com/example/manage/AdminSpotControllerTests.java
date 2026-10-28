package com.example.manage;

import com.example.manage.controller.AdminController;
import com.example.manage.controller.AdminImageController;
import com.example.manage.controller.AdminSpotController;
import com.example.manage.controller.ImageUploadExceptionHandler;
import com.example.manage.domain.Admin;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.dto.HealingSpotForm;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.AdminPlaceFormService;
import com.example.manage.service.HealingCourseService;
import com.example.manage.service.HealingSpotService;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.context.WebApplicationContext;
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
class AdminSpotControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;
    @Autowired SiteService sites;
    @Autowired HealingCourseService courses;
    @Autowired HealingSpotService spots;
    @Autowired HealingSpotRepository spotRepository;
    @MockitoSpyBean AdminPlaceFormService placeForms;
    @Value("${kakao.maps.javascript-key}") String mapsKey;

    MockMvc mvc;
    MockHttpSession admin;
    Site site, otherSite;
    HealingCourse course, otherCourse;
    HealingSpot spot;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Admin account = admins.saveAndFlush(new Admin("spot-controller-" + UUID.randomUUID(), encoder.encode("secret")));
        admin = new MockHttpSession();
        authentication.authenticateAdmin(admin, account);
        site = sites.createSite("Spot 기준 사이트", "주소", 37.0, 127.0, 3);
        otherSite = sites.createSite("Spot 다른 사이트", "주소", 38.0, 128.0, 5);
        course = courses.createHealingCourse(site.getSiteId(), "HC-A", "기준 코스", 37.1, 127.1, 50.0);
        otherCourse = courses.createHealingCourse(otherSite.getSiteId(), "HC-B", "다른 코스", 38.1, 128.1, 60.0);
        spot = spots.createHealingSpot(course.getCourseId(), "HS-A", "기준 스팟", 37.2, 127.2);
    }

    private MockHttpServletRequestBuilder form(String path, Long courseId, String code) {
        return post(path).session(admin).with(csrf())
                .param("courseId", courseId.toString()).param("code", code).param("name", "변경 스팟")
                .param("latitude", "37.5").param("longitude", "127.5");
    }

    @Test
    void listAndFormPreserveCourseSiteFilterAndModel() throws Exception {
        mvc.perform(get("/admin/spots").session(admin))
                .andExpect(redirectedUrl("/admin/courses"));
        mvc.perform(get("/admin/spots").session(admin)
                        .param("courseId", course.getCourseId().toString()).param("siteId", site.getSiteId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("admin/spot-list"))
                .andExpect(model().attributeExists("selectedCourse", "sites"))
                .andExpect(model().attribute("spots", hasSize(1)));
        mvc.perform(get("/admin/spots/new").session(admin)
                        .param("courseId", course.getCourseId().toString()).param("siteId", site.getSiteId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("admin/spot-form"))
                .andExpect(model().attribute("spotCourses", hasSize(2)))
                .andExpect(model().attributeExists("healingSpotForm", "sites", "images", "imageTitle", "kakaoMapsJavaScriptKey"))
                .andExpect(model().attribute("kakaoMapsJavaScriptKey", mapsKey))
                .andExpect(result -> assertThat(result.getModelAndView().getModel()).containsKey("spotId"))
                .andExpect(result -> assertThat(((HealingSpotForm) result.getModelAndView().getModel()
                        .get("healingSpotForm")).getCourseId()).isEqualTo(course.getCourseId()));
        for (String path : new String[]{"/admin/spots", "/admin/spots/new"}) {
            mvc.perform(get(path).session(admin)
                            .param("courseId", course.getCourseId().toString())
                            .param("siteId", otherSite.getSiteId().toString()))
                    .andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attribute("errorMessage", "선택한 사이트에 속한 코스가 아닙니다."));
        }
    }

    @Test
    void createEditAndValidationPreserveCoordinatesAndImageContext() throws Exception {
        mvc.perform(form("/admin/spots/new", otherCourse.getCourseId(), "HS-B"))
                .andExpect(redirectedUrlPattern("/admin/spots/*"));
        HealingSpot created = spots.findByCourseId(otherCourse.getCourseId()).getFirst();
        assertThat(created.getLatitude()).isEqualTo(37.5);
        assertThat(created.getLongitude()).isEqualTo(127.5);

        for (String path : new String[]{"/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit"}) {
            mvc.perform(post(path).session(admin).with(csrf()))
                    .andExpect(status().isOk()).andExpect(view().name("admin/spot-form"))
                    .andExpect(model().attributeHasFieldErrors("healingSpotForm", "courseId", "code", "name", "latitude", "longitude"))
                    .andExpect(model().attributeExists("healingSpotForm", "spotCourses", "sites", "images", "imageSelectionNotice", "kakaoMapsJavaScriptKey"))
                    .andExpect(result -> assertThat(result.getModelAndView().getModel()).containsKey("spotId"));
            mvc.perform(post(path).session(admin).with(csrf())
                            .param("courseId", course.getCourseId().toString())
                            .param("code", "HS-Z").param("name", "유효성 오류")
                            .param("latitude", "91").param("longitude", "127.5"))
                    .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("healingSpotForm", "latitude"))
                    .andExpect(model().attribute("spotCourses", hasSize(2)))
                    .andExpect(result -> assertThat(((HealingSpotForm) result.getModelAndView().getModel()
                            .get("healingSpotForm")).getCourseId()).isEqualTo(course.getCourseId()));
        }
        mvc.perform(get("/admin/spots/" + spot.getSpotId() + "/edit").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/spot-form"))
                .andExpect(model().attribute("spotId", spot.getSpotId()))
                .andExpect(model().attributeExists("images", "spotCourses", "sites", "kakaoMapsJavaScriptKey"));
        mvc.perform(form("/admin/spots/" + spot.getSpotId() + "/edit", otherCourse.getCourseId(), "HS-A"))
                .andExpect(redirectedUrl("/admin/spots/" + spot.getSpotId()));
        HealingSpot updated = spots.findHealingSpot(spot.getSpotId());
        assertThat(updated.getHealingCourse().getCourseId()).isEqualTo(otherCourse.getCourseId());
        assertThat(updated.getLatitude()).isEqualTo(37.5);
    }

    @Test
    void detailDeleteAndMissingSpotKeepResponses() throws Exception {
        mvc.perform(get("/admin/spots/" + spot.getSpotId()).session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/spot-detail"))
                .andExpect(model().attributeExists("spot", "sites", "images", "imageTitle"));
        for (String path : new String[]{"/admin/spots/-1", "/admin/spots/-1/edit"}) {
            mvc.perform(get(path).session(admin))
                    .andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attribute("errorMessage", "존재하지 않는 HealingSpot입니다."));
        }
        mvc.perform(form("/admin/spots/-1/edit", course.getCourseId(), "HS-Z"))
                .andExpect(status().isOk()).andExpect(view().name("admin/spot-form"))
                .andExpect(model().attributeHasErrors("healingSpotForm"));
        mvc.perform(post("/admin/spots/-1/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/courses"))
                .andExpect(flash().attribute("errorMessage", "존재하지 않는 HealingSpot입니다."));
        mvc.perform(post("/admin/spots/" + spot.getSpotId() + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/spots?courseId=" + course.getCourseId()))
                .andExpect(flash().attribute("successMessage", "HealingSpot을 삭제했습니다."));
        assertThat(spotRepository.existsById(spot.getSpotId())).isFalse();
    }

    @Test
    void uploadAdviceRetainsSpotFormRedirectsAndNarrowScope() throws Exception {
        Class<?>[] advised = ImageUploadExceptionHandler.class.getAnnotation(ControllerAdvice.class).assignableTypes();
        assertThat(advised).containsExactlyInAnyOrder(
                AdminImageController.class, AdminController.class, AdminSpotController.class);
        doThrow(new MaxUploadSizeExceededException(10))
                .when(placeForms).createSpot(any(HealingSpotForm.class), any());
        mvc.perform(multipart("/admin/spots/new").session(admin).with(csrf())
                        .param("courseId", course.getCourseId().toString()).param("code", "HS-Z")
                        .param("name", "새 스팟").param("latitude", "37.5").param("longitude", "127.5"))
                .andExpect(redirectedUrl("/admin/spots/new"))
                .andExpect(flash().attribute("errorMessage",
                        "업로드 용량을 초과했습니다. 파일당 10MB 이하, 한 번에 최대 10장을 선택해 주세요."));
        doThrow(new MultipartException("bad multipart"))
                .when(placeForms).updateSpot(any(), any(HealingSpotForm.class), any());
        mvc.perform(multipart("/admin/spots/" + spot.getSpotId() + "/edit").session(admin).with(csrf())
                        .param("courseId", course.getCourseId().toString()).param("code", "HS-A")
                        .param("name", "기준 스팟").param("latitude", "37.2").param("longitude", "127.2"))
                .andExpect(redirectedUrl("/admin/spots/" + spot.getSpotId() + "/edit"))
                .andExpect(flash().attribute("errorMessage", "파일 업로드 요청을 처리하지 못했습니다. 파일을 다시 선택하고 시도해 주세요."));
        assertThat(spotRepository.existsById(spot.getSpotId())).isTrue();
    }

    @Test
    void adminBoundaryCsrfMappingsAndOtherManagementRemainAvailable() throws Exception {
        Member member = members.saveAndFlush(new Member(1_800_000_002, 1,
                "spot-member-" + UUID.randomUUID(), encoder.encode("secret")));
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/spots").param("courseId", course.getCourseId().toString()))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/spots/new").session(participant))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/spots/" + spot.getSpotId() + "/delete").session(participant).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        for (String path : new String[]{"/admin/spots/new", "/admin/spots/" + spot.getSpotId() + "/edit",
                "/admin/spots/" + spot.getSpotId() + "/delete"}) {
            mvc.perform(post(path).session(admin)).andExpect(status().isForbidden());
        }
        assertThat(spotRepository.existsById(spot.getSpotId())).isTrue();
        mvc.perform(get("/admin/courses").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/sites").session(admin)).andExpect(status().isOk());
        assertThat(mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().equals(AdminSpotController.class))).hasSize(7);
        assertThat(mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(AdminController.class))
                .noneMatch(entry -> entry.getKey().toString().contains("/admin/spots"))).isTrue();
    }
}
