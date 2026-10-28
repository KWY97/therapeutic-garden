package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.dto.ImageResponse;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import com.example.manage.storage.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:image-tests;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false", "storage.mode=disabled",
        "storage.recovery-directory=${java.io.tmpdir}/manage-image-test-recovery"})
@ActiveProfiles("test")
class ImageManagementTests {
    @Autowired SiteImageService siteImages;
    @Autowired HealingSpotImageService spotImages;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired MemberRepository members;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired SiteService siteService;
    @Autowired HealingSpotService spotService;
    @MockitoSpyBean SiteImageRepository siteRecords;
    @MockitoSpyBean HealingSpotImageRepository spotRecords;
    @MockitoBean ImageStorage storage;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    private MockMvc mvc;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach void setup() {
        objects.clear();
        doAnswer(inv -> { objects.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(storage).upload(anyString(), any(byte[].class), anyString());
        doAnswer(inv -> { objects.remove(inv.getArgument(0)); return null; }).when(storage).delete(anyString());
        when(storage.read(anyString())).thenAnswer(inv -> {
            byte[] bytes = objects.get(inv.<String>getArgument(0));
            if (bytes == null) throw new ImageStorageException("파일이 없습니다.");
            return bytes;
        });
        when(storage.createReadUrl(anyString(), anyString())).thenReturn("https://example.invalid/temporary-image");
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).addFilters(security).build();
    }

    private Long parent(boolean site) {
        Site s = sites.saveAndFlush(new Site("이미지 " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        if (site) return s.getSiteId();
        HealingCourse c = courses.saveAndFlush(new HealingCourse(s, "HC-A", "회복 코스", 37.0, 127.0, 50.0));
        return spots.saveAndFlush(new HealingSpot(c, "HS1", "곶자왈원", 37.0, 127.0)).getSpotId();
    }
    private MockMultipartFile file() { return new MockMultipartFile("files", "../원본.jpg", "image/jpeg", ImageTestFiles.jpeg()); }
    private void upload(boolean site, Long id, int count) {
        var files = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> (org.springframework.web.multipart.MultipartFile) file()).toList();
        if (site) siteImages.upload(id, files); else spotImages.upload(id, files);
    }
    private List<ImageResponse> list(boolean site, Long id) { return site ? siteImages.list(id) : spotImages.list(id); }
    private void representative(boolean site, Long id, Long image) {
        if (site) siteImages.setRepresentative(id, image); else spotImages.setRepresentative(id, image);
    }
    private void deleteImage(boolean site, Long id, Long image) {
        if (site) siteImages.delete(id, image); else spotImages.delete(id, image);
    }
    private void move(boolean site, Long id, Long image, String direction) {
        if (site) siteImages.move(id, image, direction); else spotImages.move(id, image, direction);
    }
    private String path(boolean site, Long id) { return "/admin/" + (site ? "sites/" : "spots/") + id + "/images"; }
    private void failFlush(boolean site) {
        if (site) doThrow(new DataIntegrityViolationException("test DB failure")).when(siteRecords).flush();
        else doThrow(new DataIntegrityViolationException("test DB failure")).when(spotRecords).flush();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void firstUploadIsRepresentativeAndSecondIsNot(boolean site) {
        Long id = parent(site);
        upload(site, id, 1);
        assertThat(list(site, id)).singleElement().satisfies(image -> {
            assertThat(image.representative()).isTrue();
            assertThat(image.displayOrder()).isEqualTo(1);
            assertThat(image.originalFileName()).isEqualTo("원본.jpg");
        });
        upload(site, id, 1);
        assertThat(list(site, id)).extracting(ImageResponse::representative).containsExactly(true, false);
        assertThat(objects.keySet()).allMatch(key -> key.matches("(?:sites|healing-spots)/[0-9]+/[a-f0-9-]{36}\\.jpg"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void changingRepresentativeIsIdempotentAndKeepsExactlyOne(boolean site) {
        Long id = parent(site); upload(site, id, 3);
        Long selected = list(site, id).get(2).imageId();
        representative(site, id, selected); representative(site, id, selected);
        assertThat(list(site, id)).extracting(ImageResponse::representative).containsExactly(false, false, true);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void deletingOrdinaryImageRenumbersWithoutChangingRepresentative(boolean site) {
        Long id = parent(site); upload(site, id, 3);
        var before = list(site, id);
        deleteImage(site, id, before.get(1).imageId());
        assertThat(list(site, id)).extracting(ImageResponse::imageId).containsExactly(before.get(0).imageId(), before.get(2).imageId());
        assertThat(list(site, id)).extracting(ImageResponse::displayOrder).containsExactly(1, 2);
        assertThat(list(site, id)).extracting(ImageResponse::representative).containsExactly(true, false);
        assertThat(objects).hasSize(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void deletingRepresentativeUsesFirstRemainingInCurrentOrderAndLastCanBeDeleted(boolean site) {
        Long id = parent(site); upload(site, id, 3);
        var before = list(site, id);
        move(site, id, before.get(2).imageId(), "up");
        deleteImage(site, id, before.get(0).imageId());
        assertThat(list(site, id).getFirst().imageId()).isEqualTo(before.get(2).imageId());
        assertThat(list(site, id)).extracting(ImageResponse::representative).containsExactly(true, false);
        deleteImage(site, id, before.get(2).imageId());
        deleteImage(site, id, before.get(1).imageId());
        assertThat(list(site, id)).isEmpty(); assertThat(objects).isEmpty();
        upload(site, id, 1);
        assertThat(list(site, id).getFirst().representative()).isTrue();
        assertThat(list(site, id).getFirst().displayOrder()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void orderMovesAndBoundariesAreStable(boolean site) {
        Long id = parent(site); upload(site, id, 3);
        var before = list(site, id);
        move(site, id, before.getFirst().imageId(), "up");
        move(site, id, before.getLast().imageId(), "down");
        assertThat(list(site, id)).isEqualTo(before);
        move(site, id, before.getFirst().imageId(), "down");
        assertThat(list(site, id)).extracting(ImageResponse::imageId)
                .containsExactly(before.get(1).imageId(), before.get(0).imageId(), before.get(2).imageId());
        assertThat(list(site, id)).extracting(ImageResponse::displayOrder).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> move(site, id, before.getFirst().imageId(), "sideways")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void foreignImageIdsCannotMutateOrRead(boolean site) {
        Long id = parent(site), other = parent(site); upload(site, id, 1); upload(site, other, 1);
        Long foreign = list(site, other).getFirst().imageId();
        clearInvocations(storage);
        assertThatThrownBy(() -> representative(site, id, foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deleteImage(site, id, foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> move(site, id, foreign, "up")).isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).delete(anyString());
        assertThat(list(site, other).getFirst().representative()).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseFailureCompensatesUploadedObjects(boolean site) {
        Long id = parent(site); failFlush(site);
        assertThatThrownBy(() -> upload(site, id, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(list(site, id)).isEmpty(); assertThat(objects).isEmpty();
        verify(storage, times(2)).delete(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void outerTransactionRollbackAlsoCompensatesAfterSuccessfulFlush(boolean site) {
        Long id = parent(site);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            upload(site, id, 1); tx.setRollbackOnly();
        });
        assertThat(list(site, id)).isEmpty(); assertThat(objects).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void partialBatchUploadFailureCompensatesAllObjects(boolean site) {
        Long id = parent(site);
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), inv.getArgument(1));
            if (objects.size() == 2) throw new ImageStorageException("test upload timeout");
            return null;
        }).when(storage).upload(anyString(), any(byte[].class), anyString());
        assertThatThrownBy(() -> upload(site, id, 3)).isInstanceOf(ImageStorageException.class);
        assertThat(list(site, id)).isEmpty(); assertThat(objects).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void storageDeleteFailurePreservesDatabaseAndRestoresObject(boolean site) {
        Long id = parent(site); upload(site, id, 2); var before = list(site, id);
        doAnswer(inv -> { objects.remove(inv.<String>getArgument(0)); throw new ImageStorageException("test delete timeout"); })
                .when(storage).delete(anyString());
        assertThatThrownBy(() -> deleteImage(site, id, before.getFirst().imageId())).isInstanceOf(ImageStorageException.class);
        assertThat(list(site, id)).isEqualTo(before); assertThat(objects).hasSize(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseDeleteFailureRestoresDeletedObject(boolean site) {
        Long id = parent(site); upload(site, id, 2); var before = list(site, id); failFlush(site);
        assertThatThrownBy(() -> deleteImage(site, id, before.getFirst().imageId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(list(site, id)).isEqualTo(before); assertThat(objects).hasSize(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void parentDeletionCleansImages(boolean site) {
        Long id = parent(site); upload(site, id, 2);
        if (site) { siteService.deleteSite(id); assertThat(sites.existsById(id)).isFalse(); }
        else { spotService.deleteHealingSpot(id); assertThat(spots.existsById(id)).isFalse(); }
        assertThat(objects).isEmpty();
    }

    @Test void siteWithCourseStillCannotBeDeletedAndImagesRemain() {
        Long id = parent(true); upload(true, id, 1);
        courses.saveAndFlush(new HealingCourse(sites.findById(id).orElseThrow(), "HC-B", "코스", 37.0, 127.0, 50.0));
        clearInvocations(storage);
        assertThatThrownBy(() -> siteService.deleteSite(id)).hasMessageContaining("HealingCourse");
        verify(storage, never()).delete(anyString()); assertThat(objects).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentUploadsAndRepresentativeChangesStayConsistent(boolean site) throws Exception {
        Long id = parent(site);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> upload(site, id, 1));
            var b = executor.submit(() -> upload(site, id, 1));
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            var all = list(site, id);
            var c = executor.submit(() -> representative(site, id, all.get(0).imageId()));
            var d = executor.submit(() -> representative(site, id, all.get(1).imageId()));
            c.get(20, TimeUnit.SECONDS); d.get(20, TimeUnit.SECONDS);
        }
        assertThat(list(site, id).stream().filter(ImageResponse::representative)).hasSize(1);
        assertThat(list(site, id)).extracting(ImageResponse::displayOrder).containsExactly(1, 2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void adminViewsAndMutationEndpointsWorkWithCsrf(boolean site) throws Exception {
        Long id = parent(site); String path = path(site, id);
        String edit = path.replace("/images", "/edit");
        mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl(edit + "#place-images"));
        mvc.perform(get(edit).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andExpect(content().string(containsString("새 이미지 추가")));
        mvc.perform(multipart(path).file(file()).with(csrf()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(edit + "#place-images"));
        upload(site, id, 1); Long image = list(site, id).getLast().imageId();
        mvc.perform(get(edit).sessionAttr("loginAdminId", 1L))
                .andExpect(content().string(containsString("대표 이미지")))
                .andExpect(content().string(containsString("temporary-image")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        for (String action : List.of("representative", "order", "delete")) {
            mvc.perform(post(path + "/" + image + "/" + action).param("direction", "up")
                            .with(csrf()).sessionAttr("loginAdminId", 1L))
                    .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        }
        assertThat(list(site, id)).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anonymousAndMemberCannotAccessAndAdminPostRequiresCsrf(boolean site) throws Exception {
        Long id = parent(site); String path = path(site, id);
        mvc.perform(get(path)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get(path).sessionAttr("loginMemberId", 1L)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(multipart(path).file(file()).sessionAttr("loginAdminId", 1L)).andExpect(status().isForbidden());
        for (String action : List.of("delete", "representative", "order")) {
            mvc.perform(post(path + "/1/" + action).with(csrf()).param("direction", "up"))
                    .andExpect(redirectedUrl("/admin/login"));
            mvc.perform(post(path + "/1/" + action).sessionAttr("loginAdminId", 1L).param("direction", "up"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(path + "/1/content")).andExpect(status().isUnauthorized());
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidUploadAndForeignMutationShowFriendlyErrors(boolean site) throws Exception {
        Long id = parent(site), other = parent(site); upload(site, other, 1);
        Long foreign = list(site, other).getFirst().imageId(); String path = path(site, id);
        mvc.perform(multipart(path).file(new MockMultipartFile("files", new byte[0]))
                        .with(csrf()).sessionAttr("loginAdminId", 1L))
                .andExpect(flash().attributeExists("errorMessage"));
        for (String action : List.of("delete", "representative", "order")) {
            mvc.perform(post(path + "/" + foreign + "/" + action).param("direction", "up")
                            .with(csrf()).sessionAttr("loginAdminId", 1L))
                    .andExpect(flash().attributeExists("errorMessage"));
        }
        assertThat(list(site, id)).isEmpty(); assertThat(objects).hasSize(1);
    }
    @Test void scheduledSpotDeletionStillBlockedWithImages() {
        Long id = parent(false); upload(false, id, 1);
        Member member = members.saveAndFlush(new Member(900001, 1, "image-member", "test-only"));
        Schedule schedule = schedules.saveAndFlush(new Schedule(member, java.time.LocalDate.of(2026, 9, 17)));
        scheduleSpots.saveAndFlush(new ScheduleSpot(schedule, spots.findById(id).orElseThrow(), java.time.LocalTime.NOON, 1));
        clearInvocations(storage);
        assertThatThrownBy(() -> spotService.deleteHealingSpot(id)).hasMessageContaining("일정");
        verify(storage, never()).delete(anyString());
        assertThat(objects).hasSize(1); assertThat(list(false, id)).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void parentDeletionPartialStorageFailureRestoresAllEarlierImages(boolean site) {
        Long id = parent(site); upload(site, id, 3); var before = list(site, id);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            objects.remove(inv.<String>getArgument(0));
            if (count.incrementAndGet() == 2) throw new ImageStorageException("test timeout");
            return null;
        }).when(storage).delete(anyString());
        assertThatThrownBy(() -> { if (site) siteService.deleteSite(id); else spotService.deleteHealingSpot(id); })
                .isInstanceOf(ImageStorageException.class);
        assertThat(list(site, id)).isEqualTo(before); assertThat(objects).hasSize(3);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidBatchIsRejectedBeforeAnyStorageWrites(boolean site) {
        Long id = parent(site);
        var batch = List.of(file(), new MockMultipartFile("files", "fake.jpg", "image/jpeg",
                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> { if (site) siteImages.upload(id, new ArrayList<>(batch));
            else spotImages.upload(id, new ArrayList<>(batch)); }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미지 파일 내용");
        assertThatThrownBy(() -> upload(site, id, 11)).hasMessageContaining("10장");
        assertThat(list(site, id)).isEmpty();
        assertThat(objects).isEmpty();
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidMiddleOrLastFileLeavesTheWholeBatchUnstored(boolean site) {
        for (int invalidIndex : List.of(1, 2)) {
            Long id = parent(site);
            List<org.springframework.web.multipart.MultipartFile> batch = new ArrayList<>(List.of(file(), file(), file()));
            batch.set(invalidIndex, new MockMultipartFile("files", "fake.jpg", "image/jpeg",
                    "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> {
                if (site) siteImages.upload(id, batch); else spotImages.upload(id, batch);
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미지 파일 내용");
            assertThat(list(site, id)).isEmpty();
            assertThat(objects).isEmpty();
        }
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @Test
    void invalidSiteReplacementDoesNotChangeImagesOrSpatialRevision() {
        Long id = parent(true);
        upload(true, id, 2);
        var before = list(true, id);
        long revision = sites.findById(id).orElseThrow().getSpatialRevision();
        var form = new com.example.manage.dto.ImageEditForm();
        form.setImageOrder("e:" + before.getFirst().imageId() + ",n:0");
        form.setImageDeleted("e:" + before.getLast().imageId());
        form.setImageSpatial("n:0");
        var invalid = new MockMultipartFile("files", "fake.jpg", "image/jpeg",
                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> siteImages.edit(id, List.of(invalid), form))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미지 파일 내용");
        assertThat(list(true, id)).isEqualTo(before);
        assertThat(objects).hasSize(2);
        assertThat(sites.findById(id).orElseThrow().getSpatialRevision()).isEqualTo(revision);
        verify(storage, times(2)).upload(anyString(), any(byte[].class), anyString());
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void validWebpUsesTheSharedValidationBeforeStorage(boolean site) {
        Long id = parent(site);
        var file = new MockMultipartFile("files", "photo.webp", "image/webp", ImageTestFiles.webp());
        if (site) siteImages.upload(id, List.of(file)); else spotImages.upload(id, List.of(file));
        assertThat(list(site, id)).hasSize(1);
        assertThat(objects).hasSize(1);
        assertThat(objects.keySet()).allMatch(key -> key.endsWith(".webp"));
        verify(storage).upload(anyString(), eq(ImageTestFiles.webp()), eq("image/webp"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidReplacementLeavesExistingImageAndStorageUntouched(boolean site) {
        Long id = parent(site);
        upload(site, id, 1);
        var before = list(site, id);
        var form = new com.example.manage.dto.ImageEditForm();
        form.setImageOrder("e:" + before.getFirst().imageId() + ",n:0");
        form.setImageRepresentative("e:" + before.getFirst().imageId());
        var fake = new MockMultipartFile("files", "fake.jpg", "image/jpeg",
                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> {
            if (site) siteImages.edit(id, List.of(fake), form); else spotImages.edit(id, List.of(fake), form);
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미지 파일 내용");
        assertThat(list(site, id)).isEqualTo(before);
        assertThat(objects).hasSize(1);
        verify(storage, times(1)).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failureDuringTransactionCommitCompensatesUpload(boolean site) {
        Long id = parent(site);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            upload(site, id, 1);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            throw new DataIntegrityViolationException("test commit failure");
                        }
                    });
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(list(site, id)).isEmpty(); assertThat(objects).isEmpty();
    }

}
