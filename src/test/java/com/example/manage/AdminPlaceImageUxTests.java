package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.dto.*;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import com.example.manage.storage.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:image-ux;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false", "storage.mode=disabled",
        "storage.recovery-directory=${java.io.tmpdir}/manage-image-ux-recovery"})
@ActiveProfiles("test")
class AdminPlaceImageUxTests {
    @Autowired AdminPlaceFormService forms;
    @Autowired SiteImageService siteImages;
    @Autowired HealingSpotImageService spotImages;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @MockitoSpyBean SiteImageRepository siteRecords;
    @MockitoSpyBean HealingSpotImageRepository spotRecords;
    @MockitoBean ImageStorage storage;
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    MockMvc mvc;
    Long courseId;
    String name;

    @BeforeEach void setup() {
        objects.clear();
        name = "UX " + UUID.randomUUID();
        Site site = sites.saveAndFlush(new Site("소속 " + name, "주소", 37.0, 127.0, 3));
        courseId = courses.saveAndFlush(new HealingCourse(site, "HC-A", "회복", 37.0, 127.0, 50.0)).getCourseId();
        doAnswer(inv -> { objects.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(storage).upload(anyString(), any(byte[].class), anyString());
        doAnswer(inv -> { objects.remove(inv.<String>getArgument(0)); return null; }).when(storage).delete(anyString());
        when(storage.read(anyString())).thenAnswer(inv -> objects.get(inv.<String>getArgument(0)));
        when(storage.createReadUrl(anyString(), anyString())).thenAnswer(inv -> "https://example.invalid/" + inv.getArgument(0));
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).addFilters(security).build();
    }

    SiteForm siteForm() {
        SiteForm form = new SiteForm(); form.setName(name); form.setAddress("주소");
        form.setLatitude(37.0); form.setLongitude(127.0); form.setMapLevel(3); return form;
    }
    HealingSpotForm spotForm() {
        HealingSpotForm form = new HealingSpotForm(); form.setName(name); form.setCode("HS1");
        form.setCourseId(courseId); form.setLatitude(37.0); form.setLongitude(127.0); return form;
    }
    MockMultipartFile file(String name) { return new MockMultipartFile("files", name, "image/png", ImageTestFiles.png()); }
    Long create(boolean site, int count) {
        var files = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> (org.springframework.web.multipart.MultipartFile) file("photo" + i + ".png")).toList();
        return site ? forms.createSite(siteForm(), files).getSiteId() : forms.createSpot(spotForm(), files).getSpotId();
    }
    List<ImageResponse> images(boolean site, Long id) { return site ? siteImages.list(id) : spotImages.list(id); }
    String base(boolean site) { return "/admin/" + (site ? "sites" : "spots"); }
    String detail(boolean site, Long id) { return base(site) + "/" + id; }
    MockMultipartHttpServletRequestBuilder formRequest(boolean site, String path) {
        var request = multipart(path);
        request.param("name", name).param("latitude", "37.0").param("longitude", "127.0")
                .sessionAttr("loginAdminId", 1L).with(csrf());
        if (site) request.param("address", "주소").param("mapLevel", "3");
        else request.param("courseId", courseId.toString()).param("code", "HS1");
        return request;
    }
    long parentCount(boolean site) { return site ? sites.count() : spots.count(); }
    ResultActions page(boolean site, Long id, boolean edit) throws Exception {
        return mvc.perform(get(detail(site, id) + (edit ? "/edit" : "")).sessionAttr("loginAdminId", 1L));
    }
    void select(boolean site, Long id, Long image) {
        if (site) siteImages.setRepresentative(id, image); else spotImages.setRepresentative(id, image);
    }
    @SuppressWarnings("unchecked")
    List<ImageResponse> modelImages(MvcResult result) { return (List<ImageResponse>) result.getModelAndView().getModel().get("images"); }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void registerWithoutImagesAndBrowserEmptyPart(boolean site) throws Exception {
        long before = parentCount(site);
        var response = mvc.perform(formRequest(site, base(site) + "/new")
                        .file(new MockMultipartFile("files", "", "application/octet-stream", new byte[0])))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = response.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(base(site) + "/").doesNotContain("?");
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
        assertThat(parentCount(site)).isEqualTo(before + 1);
        assertThat(images(site, id)).isEmpty(); verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
        page(site, id, false).andExpect(content().string(containsString("등록된 이미지가 없습니다.")));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void registerOneImageAndReturnToDetail(boolean site) throws Exception {
        var response = mvc.perform(formRequest(site, base(site) + "/new").file(file("one.png")))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = response.getResponse().getRedirectedUrl();
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
        assertThat(images(site, id)).singleElement().satisfies(image -> {
            assertThat(image.representative()).isTrue(); assertThat(image.displayOrder()).isEqualTo(1);
        });
        assertThat(objects.keySet()).allMatch(key -> key.startsWith((site ? "sites/" : "healing-spots/") + id + "/"));
        String html = page(site, id, false).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-gallery-main", "one.png").doesNotContain("class=\"image-thumbnails\"");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void registerMultipleImagesWithFirstRepresentative(boolean site) throws Exception {
        var result = mvc.perform(formRequest(site, base(site) + "/new")
                        .file(file("first.png")).file(file("second.png")).file(file("third.png")))
                .andExpect(status().is3xxRedirection()).andReturn();
        String url = result.getResponse().getRedirectedUrl();
        Long id = Long.valueOf(url.substring(url.lastIndexOf('/') + 1));
        assertThat(images(site, id)).extracting(ImageResponse::representative).containsExactly(true, false, false);
        assertThat(images(site, id)).extracting(ImageResponse::displayOrder).containsExactly(1, 2, 3);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidParentFieldsPreventImageUpload(boolean site) throws Exception {
        long before = parentCount(site);
        mvc.perform(formRequest(site, base(site) + "/new").file(file("one.png")).with(request -> { request.setParameter("name", ""); return request; }))
                .andExpect(status().isOk()).andExpect(model().hasErrors())
                .andExpect(content().string(containsString("이미지는 다시 선택")));
        assertThat(parentCount(site)).isEqualTo(before);
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidImageBatchRollsBackOnlyNewParent(boolean site) throws Exception {
        Long existing = create(site, 0);
        name = "새 " + UUID.randomUUID();
        long before = parentCount(site);
        var request = formRequest(site, base(site) + "/new").file(file("good.png"))
                .file(new MockMultipartFile("files", "fake.png", "image/png",
                        "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!site) request.with(r -> { r.setParameter("code", "HS2"); return r; });
        mvc.perform(request).andExpect(status().isOk()).andExpect(model().hasErrors())
                .andExpect(content().string(containsString("이미지 파일 내용")));
        assertThat(parentCount(site)).isEqualTo(before);
        assertThat(site ? sites.existsById(existing) : spots.existsById(existing)).isTrue();
        assertThat(objects).isEmpty(); verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void namedEmptyFileIsRejectedRatherThanTreatedAsNoSelection(boolean site) throws Exception {
        long before = parentCount(site);
        for (String filename : List.of("empty.png", " ")) {
            mvc.perform(formRequest(site, base(site) + "/new")
                            .file(new MockMultipartFile("files", filename, "image/png", new byte[0])))
                    .andExpect(model().hasErrors()).andExpect(content().string(containsString("빈 파일")));
            assertThat(parentCount(site)).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void partialUploadFailureCompensatesObjectsAndNewParent(boolean site) throws Exception {
        long before = parentCount(site);
        long imageCount = site ? siteRecords.count() : spotRecords.count();
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), inv.getArgument(1));
            if (objects.size() == 2) throw new ImageStorageException("두 번째 업로드 실패");
            return null;
        }).when(storage).upload(anyString(), any(byte[].class), anyString());
        mvc.perform(formRequest(site, base(site) + "/new").file(file("one.png")).file(file("two.png")))
                .andExpect(status().isOk()).andExpect(model().hasErrors())
                .andExpect(content().string(containsString("이미지 저장 중 오류")))
                .andExpect(content().string(containsString(name)));
        assertThat(parentCount(site)).isEqualTo(before);
        assertThat(objects).isEmpty();
        assertThat(site ? siteRecords.count() : spotRecords.count()).isEqualTo(imageCount);
        verify(storage, times(2)).delete(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseFailureCompensatesNewParentAndImages(boolean site) throws Exception {
        long before = parentCount(site);
        if (site) doThrow(new DataIntegrityViolationException("test flush failure")).when(siteRecords).flush();
        else doThrow(new DataIntegrityViolationException("test flush failure")).when(spotRecords).flush();
        mvc.perform(formRequest(site, base(site) + "/new").file(file("one.png")))
                .andExpect(status().isOk()).andExpect(model().hasErrors());
        assertThat(parentCount(site)).isEqualTo(before); assertThat(objects).isEmpty();
        verify(storage).delete(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void duplicateNameOrSiteScopedHsCodeStillRejectedBeforeStorage(boolean site) throws Exception {
        create(site, 0); long before = parentCount(site);
        if (!site) {
            HealingCourse original = courses.findById(courseId).orElseThrow();
            courseId = courses.saveAndFlush(new HealingCourse(original.getSite(), "HC-B", "형제 코스", 37.0, 127.0, 50.0)).getCourseId();
        }
        mvc.perform(formRequest(site, base(site) + "/new").file(file("one.png")))
                .andExpect(model().hasErrors()).andExpect(content().string(containsString("이미 등록된")));
        assertThat(parentCount(site)).isEqualTo(before);
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void editDisplaysExistingImagesAndAddsImagesOnSave(boolean site) throws Exception {
        Long id = create(site, 1); Long first = images(site, id).getFirst().imageId();
        String html = page(site, id, true).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("photo0.png", "새 이미지 추가", "enctype=\"multipart/form-data\"", "data-key=\"e:" + first + "\"")
                .doesNotContain("data-image-action", "form=\"image-delete-");
        var tags = Pattern.compile("<(/?)form\\b[^>]*>").matcher(html);
        int depth = 0;
        while (tags.find()) { depth += tags.group(1).isEmpty() ? 1 : -1; assertThat(depth).isBetween(0, 1); }
        assertThat(depth).isZero();
        name = "수정 " + name;
        mvc.perform(formRequest(site, detail(site, id) + "/edit").file(file("added.png")))
                .andExpect(redirectedUrl(detail(site, id)));
        assertThat(images(site, id)).extracting(ImageResponse::representative).containsExactly(true, false);
        assertThat(images(site, id).getFirst().imageId()).isEqualTo(first);
        assertThat(site ? sites.findById(id).orElseThrow().getName() : spots.findById(id).orElseThrow().getName()).isEqualTo(name);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void editUploadFailureRestoresExistingInformationAndImages(boolean site) throws Exception {
        Long id = create(site, 1); String original = name; var before = images(site, id); var keys = Set.copyOf(objects.keySet());
        name = "저장 실패 " + name;
        doThrow(new ImageStorageException("test unavailable")).when(storage).upload(anyString(), any(byte[].class), anyString());
        mvc.perform(formRequest(site, detail(site, id) + "/edit").file(file("new.png")))
                .andExpect(status().isOk()).andExpect(model().hasErrors())
                .andExpect(content().string(containsString(name))).andExpect(content().string(containsString("photo0.png")));
        assertThat(site ? sites.findById(id).orElseThrow().getName() : spots.findById(id).orElseThrow().getName()).isEqualTo(original);
        assertThat(images(site, id)).isEqualTo(before); assertThat(objects.keySet()).isEqualTo(keys);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void editValidationRetainsExistingImagesAndMapContext(boolean site) throws Exception {
        Long id = create(site, 1);
        mvc.perform(formRequest(site, detail(site, id) + "/edit").with(request -> { request.setParameter("latitude", "91"); return request; }))
                .andExpect(status().isOk()).andExpect(model().hasErrors())
                .andExpect(model().attributeExists("kakaoMapsJavaScriptKey", "images"))
                .andExpect(content().string(containsString("photo0.png")));
        assertThat(images(site, id)).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void detailUsesRepresentativeFirstThenDisplayOrderAndHasNoManagementActions(boolean site) throws Exception {
        Long id = create(site, 3); var before = images(site, id); select(site, id, before.get(2).imageId());
        var result = page(site, id, false).andExpect(status().isOk()).andReturn();
        assertThat(modelImages(result)).extracting(ImageResponse::imageId)
                .containsExactly(before.get(2).imageId(), before.get(0).imageId(), before.get(1).imageId());
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("data-image-gallery", "data-gallery-main", "class=\"image-thumbnails\"", "https://example.invalid/", "admin-image-gallery.js")
                .doesNotContain(">이미지 관리</a>", "data-image-action", "/representative\"", "/order\"");
        assertThat(html.indexOf("photo2.png")).isLessThan(html.indexOf("photo0.png"));
        assertThat(images(site, id)).extracting(ImageResponse::imageId)
                .containsExactlyElementsOf(before.stream().map(ImageResponse::imageId).toList());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void galleryFallsBackToDisplayOrderWhenRepresentativeAbsent(boolean site) throws Exception {
        Long id = create(site, 2);
        if (site) {
            var records = siteRecords.findBySiteSiteIdOrderByDisplayOrderAscImageIdAsc(id);
            records.forEach(image -> image.changeRepresentative(false)); siteRecords.saveAllAndFlush(records);
        } else {
            var records = spotRecords.findByHealingSpotSpotIdOrderByDisplayOrderAscImageIdAsc(id);
            records.forEach(image -> image.changeRepresentative(false)); spotRecords.saveAllAndFlush(records);
        }
        var result = page(site, id, false).andReturn();
        assertThat(modelImages(result)).extracting(ImageResponse::displayOrder).containsExactly(1, 2);
        assertThat(result.getResponse().getContentAsString()).contains("data-gallery-main");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void emptyGalleryRendersNoImageFrame(boolean site) throws Exception {
        Long id = create(site, 0);
        String html = page(site, id, false).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("등록된 이미지가 없습니다.").doesNotContain("data-gallery-main", "class=\"image-gallery-stage\"", ">이미지 관리</a>");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void imageActionsReturnToEditAndHandleRepresentativeOrderAndEveryDeletion(boolean site) throws Exception {
        Long id = create(site, 3); var before = images(site, id); String path = detail(site, id) + "/images/";
        Long chosen = before.get(2).imageId();
        for (String action : List.of("representative", "order")) {
            mvc.perform(post(path + chosen + "/" + action).param("direction", "up").with(csrf()).sessionAttr("loginAdminId", 1L))
                    .andExpect(redirectedUrl(detail(site, id) + "/edit#place-images"));
        }
        assertThat(images(site, id)).extracting(ImageResponse::imageId).containsExactly(before.get(0).imageId(), chosen, before.get(1).imageId());
        for (Long remove : List.of(before.get(1).imageId(), chosen, before.get(0).imageId())) {
            mvc.perform(post(path + remove + "/delete").with(csrf()).sessionAttr("loginAdminId", 1L))
                    .andExpect(redirectedUrl(detail(site, id) + "/edit#place-images"));
            if (!images(site, id).isEmpty()) assertThat(images(site, id).stream().filter(ImageResponse::representative)).hasSize(1);
        }
        assertThat(images(site, id)).isEmpty(); assertThat(objects).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void storageUrlFailureDoesNotBreakBasicInformationOrEditForm(boolean site) throws Exception {
        Long id = create(site, 1);
        when(storage.createReadUrl(anyString(), anyString())).thenThrow(new ImageStorageException("이미지 저장소 설정 확인"));
        for (boolean edit : List.of(true, false)) {
            page(site, id, edit).andExpect(status().isOk()).andExpect(content().string(containsString(name)))
                    .andExpect(content().string(containsString("이미지를 불러오지 못했습니다")));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void newAndEditMultipartRequireAdminAndCsrf(boolean site) throws Exception {
        Long id = create(site, 0);
        for (String path : List.of(base(site) + "/new", detail(site, id) + "/edit")) {
            mvc.perform(multipart(path).file(file("one.png")).with(csrf())).andExpect(redirectedUrl("/admin/login"));
            mvc.perform(multipart(path).file(file("one.png")).with(csrf()).sessionAttr("loginMemberId", 1L))
                    .andExpect(redirectedUrl("/admin/login"));
            mvc.perform(multipart(path).file(file("one.png")).sessionAttr("loginAdminId", 1L)).andExpect(status().isForbidden());
        }
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    String key(ImageResponse image) { return "e:" + image.imageId(); }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void finalSubmitSavesMixedOrderRepresentativeDeletionAndParent(boolean site) throws Exception {
        Long id = create(site, 3); var before = images(site, id);
        var keys = Set.copyOf(objects.keySet());
        // Opening the editor and leaving it (GET only) must never mutate metadata or objects.
        page(site, id, true).andExpect(status().isOk());
        page(site, id, false).andExpect(status().isOk());
        assertThat(images(site, id)).isEqualTo(before); assertThat(objects.keySet()).isEqualTo(keys);
        name = "최종 " + name;
        mvc.perform(formRequest(site, detail(site, id) + "/edit")
                .file(file("new.png")).file(file("next.png"))
                .param("imageOrder", "n:0," + key(before.get(1)) + ",n:1," + key(before.get(0)))
                .param("imageDeleted", key(before.get(2))).param("imageRepresentative", "n:0"))
                .andExpect(redirectedUrl(detail(site, id)));
        assertThat(images(site, id)).extracting(ImageResponse::originalFileName)
                .containsExactly("new.png", "photo1.png", "next.png", "photo0.png");
        assertThat(images(site, id)).extracting(ImageResponse::displayOrder).containsExactly(1, 2, 3, 4);
        assertThat(images(site, id)).extracting(ImageResponse::representative).containsExactly(true, false, false, false);
        assertThat(objects).hasSize(4);
        assertThat(site ? sites.findById(id).orElseThrow().getName() : spots.findById(id).orElseThrow().getName()).isEqualTo(name);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void finalSubmitExistingRepresentativeFallbackAndDeleteAll(boolean site) throws Exception {
        Long id = create(site, 3); var before = images(site, id);
        mvc.perform(formRequest(site, detail(site, id) + "/edit")
                .param("imageOrder", key(before.get(2)) + "," + key(before.get(1)) + "," + key(before.get(0)))
                .param("imageRepresentative", key(before.get(1))))
                .andExpect(redirectedUrl(detail(site, id)));
        assertThat(images(site, id)).extracting(ImageResponse::representative).containsExactly(false, true, false);
        mvc.perform(formRequest(site, detail(site, id) + "/edit")
                .param("imageOrder", key(before.get(2))).param("imageDeleted", key(before.get(0)) + "," + key(before.get(1)))
                .param("imageRepresentative", key(before.get(1))))
                .andExpect(redirectedUrl(detail(site, id)));
        assertThat(images(site, id)).singleElement().satisfies(image -> assertThat(image.representative()).isTrue());
        mvc.perform(formRequest(site, detail(site, id) + "/edit").param("imageOrder", "")
                .param("imageDeleted", key(before.get(2))))
                .andExpect(redirectedUrl(detail(site, id)));
        assertThat(images(site, id)).isEmpty(); assertThat(objects).isEmpty();
        mvc.perform(formRequest(site, detail(site, id) + "/edit").param("imageOrder", ""))
                .andExpect(redirectedUrl(detail(site, id)));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void combinedEditDeletionFailureRestoresUploadedAndDeletedObjects(boolean site) throws Exception {
        Long id = create(site, 3); var before = images(site, id); var original = name;
        var keys = Set.copyOf(objects.keySet());
        doAnswer(inv -> {
            String key = inv.getArgument(0); objects.remove(key);
            if (keys.contains(key)) throw new ImageStorageException("secret storage path");
            return null;
        }).when(storage).delete(anyString());
        name = "실패 " + name;
        mvc.perform(formRequest(site, detail(site, id) + "/edit").file(file("new.png"))
                .param("imageOrder", "n:0," + key(before.get(2)))
                .param("imageDeleted", key(before.get(0)) + "," + key(before.get(1))).param("imageRepresentative", "n:0"))
                .andExpect(model().hasErrors()).andExpect(content().string(containsString("이미지 저장 중 오류")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret storage path"))));
        assertThat(images(site, id)).isEqualTo(before); assertThat(objects.keySet()).isEqualTo(keys);
        assertThat(site ? sites.findById(id).orElseThrow().getName() : spots.findById(id).orElseThrow().getName()).isEqualTo(original);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void combinedEditDatabaseFailureRestoresDeletionAndParent(boolean site) throws Exception {
        Long id = create(site, 2); var before = images(site, id); var keys = Set.copyOf(objects.keySet());
        String original = name; name = "DB 실패 " + name;
        if (site) doNothing().doThrow(new DataIntegrityViolationException("flush")).when(siteRecords).flush();
        else doNothing().doThrow(new DataIntegrityViolationException("flush")).when(spotRecords).flush();
        mvc.perform(formRequest(site, detail(site, id) + "/edit").file(file("new.png"))
                .param("imageOrder", "n:0," + key(before.get(1))).param("imageDeleted", key(before.get(0))))
                .andExpect(model().hasErrors());
        assertThat(images(site, id)).isEqualTo(before); assertThat(objects.keySet()).isEqualTo(keys);
        assertThat(site ? sites.findById(id).orElseThrow().getName() : spots.findById(id).orElseThrow().getName()).isEqualTo(original);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void malformedForeignOrStaleEditCannotDeleteObjects(boolean site) throws Exception {
        Long id = create(site, 2); var before = images(site, id); var keys = Set.copyOf(objects.keySet());
        for (String order : List.of("e:999999999", key(before.get(0)), key(before.get(0)) + "," + key(before.get(0)), "n:2")) {
            mvc.perform(formRequest(site, detail(site, id) + "/edit").file(file("new.png"))
                    .param("imageOrder", order)).andExpect(model().hasErrors());
        }
        assertThat(images(site, id)).isEqualTo(before); assertThat(objects.keySet()).isEqualTo(keys);
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void registrationUsesSameDraftOrderAndRepresentative(boolean site) throws Exception {
        var result = mvc.perform(formRequest(site, base(site) + "/new").file(file("first.png")).file(file("second.png"))
                .param("imageOrder", "n:1,n:0").param("imageRepresentative", "n:0"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        Long id = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
        assertThat(images(site, id)).extracting(ImageResponse::originalFileName).containsExactly("second.png", "first.png");
        assertThat(images(site, id)).extracting(ImageResponse::representative).containsExactly(false, true);
    }
}
