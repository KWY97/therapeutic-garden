package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.dto.*;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import com.example.manage.storage.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:spatial;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false", "storage.mode=disabled", "storage.recovery-directory=${java.io.tmpdir}/manage-spatial-recovery"})
@ActiveProfiles("test")
class SpatialLayoutTests {
    @Autowired javax.sql.DataSource dataSource;
    @Autowired SiteRepository sites;
    @Autowired SiteImageRepository images;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired SiteImageSpotPositionRepository positions;
    @Autowired SiteImageService siteImages;
    @Autowired HealingSpotImageService spotImages;
    @Autowired HealingSpotService spotService;
    @Autowired HealingCourseService courseService;
    @Autowired AdminPlaceFormService forms;
    @Autowired SpatialLayoutService layouts;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @MockitoBean ImageStorage storage;
    Site site; HealingCourse course; HealingSpot first; HealingSpot second;
    Long a; Long b;
    MockMvc mvc;
    Map<String, byte[]> objects;

    @BeforeEach void setup() {
        objects = new HashMap<>();
        doAnswer(i -> { objects.put(i.getArgument(0), i.getArgument(1)); return null; }).when(storage).upload(anyString(), any(), anyString());
        doAnswer(i -> { objects.remove(i.<String>getArgument(0)); return null; }).when(storage).delete(anyString());
        when(storage.read(anyString())).thenAnswer(i -> objects.get(i.<String>getArgument(0)));
        when(storage.createReadUrl(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        site = sites.saveAndFlush(new Site("Spatial " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        course = courses.saveAndFlush(new HealingCourse(site, "HC-A", "코스", null, null, null));
        first = spots.saveAndFlush(new HealingSpot(course, "HS1", "정원", 37.0, 127.0));
        second = spots.saveAndFlush(new HealingSpot(course, "HS2", "숲", 37.0, 127.0));
        siteImages.upload(site.getSiteId(), List.of(file("a.png"), file("b.png")));
        var list = siteImages.list(site.getSiteId()); a = list.get(0).imageId(); b = list.get(1).imageId();
        select(a);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }
    MockMultipartFile file(String name) { return new MockMultipartFile("files", name, "image/png", new byte[]{1,2,3}); }
    ImageEditForm editForm(String spatial) {
        var f = new ImageEditForm(); f.setImageOrder("e:" + a + ",e:" + b); f.setImageSpatial(spatial); f.setImageRepresentative("e:" + a); return f;
    }
    void select(Long id) { siteImages.edit(site.getSiteId(), List.of(), editForm(id == null ? "" : "e:" + id)); }
    SpatialLayoutForm.Position point(Long id, String x, String y) {
        var p = new SpatialLayoutForm.Position(); p.setSpotId(id);
        p.setXPercent(x == null ? null : new BigDecimal(x)); p.setYPercent(y == null ? null : new BigDecimal(y)); return p;
    }
    SpatialLayoutForm draft() {
        var f = new SpatialLayoutForm(); f.setSiteImageId(a); f.setRevision(sites.findById(site.getSiteId()).orElseThrow().getSpatialRevision());
        f.setPositions(new ArrayList<>(List.of(point(first.getSpotId(), "20.12345", "30"), point(second.getSpotId(), "100", "0")))); return f;
    }
    void save(SpatialLayoutForm f) { layouts.save(site.getSiteId(), f); }
    String path() { return "/admin/sites/" + site.getSiteId() + "/spatial-layout"; }

    @Test void monitoringVariantsFollowRepresentativeWhileGalleryKeepsOriginalsAndDeletionCleansUp() throws Exception {
        when(storage.exists(anyString())).thenAnswer(i -> objects.containsKey(i.<String>getArgument(0)));
        var photo = new MockMultipartFile("files", "photo.png", "image/png", MonitoringThumbnailTests.photo(1200, 800));
        spotImages.upload(first.getSpotId(), List.of(photo, photo));
        var gallery = spotImages.list(first.getSpotId());
        String originalUrl = gallery.getFirst().readUrl();
        String thumbnailUrl = originalUrl.replace("/content", "/thumbnail");
        assertThat(layouts.load(site.getSiteId()).spots().getFirst().readUrl()).isEqualTo(thumbnailUrl);
        assertThat(spotImages.list(first.getSpotId()).getFirst().readUrl()).isEqualTo(originalUrl);
        assertThat(objects.keySet().stream().filter(k -> k.endsWith(".monitoring-v1-560-q82.jpg"))).hasSize(1);
        spotImages.setRepresentative(first.getSpotId(), gallery.getLast().imageId());
        assertThat(layouts.load(site.getSiteId()).spots().getFirst().readUrl())
                .isEqualTo(gallery.getLast().readUrl().replace("/content", "/thumbnail"));
        spotImages.move(first.getSpotId(), gallery.getLast().imageId(), "up");
        assertThat(spotImages.monitoringReadUrl(first.getSpotId()))
                .isEqualTo(gallery.getLast().readUrl().replace("/content", "/thumbnail"));
        spotImages.delete(first.getSpotId(), gallery.getLast().imageId());
        assertThat(spotImages.monitoringReadUrl(first.getSpotId())).isEqualTo(thumbnailUrl);
        assertThat(objects.keySet().stream().filter(k -> k.endsWith(".monitoring-v1-560-q82.jpg"))).hasSize(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            spotImages.deleteAll(first.getSpotId());
            status.setRollbackOnly();
        });
        assertThat(spotImages.monitoringReadUrl(first.getSpotId())).isEqualTo(thumbnailUrl);
        assertThat(objects.keySet().stream().filter(k -> k.endsWith(".monitoring-v1-560-q82.jpg"))).hasSize(1);
        spotImages.deleteAll(first.getSpotId());
        assertThat(objects.keySet().stream().filter(k -> k.endsWith(".monitoring-v1-560-q82.jpg"))).isEmpty();
    }

    @Test void integratedEditDeletesVariantAndUsesNewRepresentative() throws Exception {
        when(storage.exists(anyString())).thenAnswer(i -> objects.containsKey(i.<String>getArgument(0)));
        var photo = new MockMultipartFile("files", "photo.png", "image/png", MonitoringThumbnailTests.photo(800, 600));
        spotImages.upload(first.getSpotId(), List.of(photo, photo));
        var gallery = spotImages.list(first.getSpotId());
        spotImages.monitoringReadUrl(first.getSpotId());
        var form = new ImageEditForm();
        form.setImageOrder("e:" + gallery.getLast().imageId());
        form.setImageRepresentative("e:" + gallery.getLast().imageId());
        form.setImageDeleted("e:" + gallery.getFirst().imageId());
        spotImages.edit(first.getSpotId(), List.of(), form);
        assertThat(objects.keySet().stream().filter(k -> k.endsWith(".monitoring-v1-560-q82.jpg"))).isEmpty();
        assertThat(spotImages.monitoringReadUrl(first.getSpotId()))
                .isEqualTo(gallery.getLast().readUrl().replace("/content", "/thumbnail"));
    }

    @Test void rolesAreIndependentAndSelectionCanBeCleared() {
        select(b);
        assertThat(siteImages.list(site.getSiteId())).extracting(ImageResponse::representative).containsExactly(true, false);
        assertThat(siteImages.list(site.getSiteId())).extracting(ImageResponse::spatial).containsExactly(false, true);
        siteImages.setRepresentative(site.getSiteId(), b);
        assertThat(siteImages.list(site.getSiteId()).get(1)).satisfies(i -> { assertThat(i.representative()).isTrue(); assertThat(i.spatial()).isTrue(); });
        select(null); assertThat(layouts.load(site.getSiteId()).image()).isNull();
    }
    @Test void physicalSpatialColumnAndUniqueKeyUseNonReservedName() throws Exception {
        // Check the explicit mapping as well as Hibernate's actual generated test schema.
        // H2 accepting an unquoted MySQL keyword must not hide this regression again.
        var column = SiteImage.class.getDeclaredField("spatial").getAnnotation(jakarta.persistence.Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("spatial_image");
        var unique = Arrays.stream(SiteImage.class.getAnnotation(jakarta.persistence.Table.class).uniqueConstraints())
                .filter(key -> key.name().equals("uk_site_image_spatial")).findFirst().orElseThrow();
        assertThat(unique.columnNames()).containsExactly("site_id", "spatial_image");
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            List<String> columns = new ArrayList<>();
            try (var result = metadata.getColumns(null, null, "SITE_IMAGE", null)) {
                while (result.next()) columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            assertThat(columns).contains("spatial_image").doesNotContain("spatial");
            Map<String, List<String>> uniqueIndexes = new HashMap<>();
            try (var result = metadata.getIndexInfo(null, null, "SITE_IMAGE", true, false)) {
                while (result.next()) {
                    String name = result.getString("INDEX_NAME"), field = result.getString("COLUMN_NAME");
                    if (name != null && field != null)
                        uniqueIndexes.computeIfAbsent(name, key -> new ArrayList<>()).add(field.toLowerCase(Locale.ROOT));
                }
            }
            assertThat(uniqueIndexes.values()).anySatisfy(fields ->
                    assertThat(fields).containsExactlyInAnyOrder("site_id", "spatial_image"));
        }
    }
    @Test void databaseRejectsTwoSpatialImages() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            images.findById(b).orElseThrow().changeSpatial(true); images.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(siteImages.list(site.getSiteId())).filteredOn(ImageResponse::spatial).hasSize(1);
    }
    @Test void newUploadCanBeSelectedInCombinedSiteEdit() {
        SiteForm f = new SiteForm(); f.setName(site.getName()); f.setAddress("새 주소"); f.setLatitude(37.0); f.setLongitude(127.0); f.setMapLevel(3);
        f.setImageOrder("e:" + a + ",n:0,e:" + b); f.setImageSpatial("n:0"); f.setImageRepresentative("e:" + a);
        forms.updateSite(site.getSiteId(), f, List.of(file("new.png")));
        var selected = layouts.load(site.getSiteId()).image();
        assertThat(selected.originalFileName()).isEqualTo("new.png"); assertThat(selected.representative()).isFalse();
        assertThat(sites.findById(site.getSiteId()).orElseThrow().getAddress()).isEqualTo("새 주소");
    }
    @Test void unsubmittedSelectionAndPositionsDoNotWrite() {
        var edit = editForm("e:" + b); var draft = draft(); draft.getPositions().getFirst().setXPercent(BigDecimal.ONE);
        assertThat(layouts.load(site.getSiteId()).image().imageId()).isEqualTo(a);
        assertThat(positions.findBySiteImageImageId(a)).isEmpty();
    }
    @Test void insertMoveAndClearAreOneFinalState() {
        save(draft()); var before = positions.findBySiteImageImageId(a);
        assertThat(before).hasSize(2); var id = before.stream().filter(p -> p.getXPercent().compareTo(new BigDecimal("20.1235")) == 0).findFirst().orElseThrow().getPositionId();
        var next = draft(); next.setPositions(List.of(point(first.getSpotId(), "45", "51.8"), point(second.getSpotId(), null, null))); save(next);
        assertThat(positions.findBySiteImageImageId(a)).singleElement().satisfies(p -> {
            assertThat(p.getPositionId()).isEqualTo(id); assertThat(p.getXPercent()).isEqualByComparingTo("45"); assertThat(p.getYPercent()).isEqualByComparingTo("51.8");
        });
    }
    @Test void switchingImagesRetainsIndependentPositions() {
        save(draft()); select(b); assertThat(layouts.load(site.getSiteId()).spots()).allSatisfy(p -> assertThat(p.xPercent()).isNull());
        var other = draft(); other.setSiteImageId(b); save(other);
        select(a); assertThat(positions.findBySiteImageImageId(a)).hasSize(2); assertThat(positions.findBySiteImageImageId(b)).hasSize(2);
        assertThat(layouts.load(site.getSiteId()).spots().getFirst().xPercent()).isEqualByComparingTo("20.1235");
    }
    @Test void otherSiteSpotIsRejectedBeforeAnyMutation() {
        save(draft()); Site foreign = sites.saveAndFlush(new Site("Foreign " + UUID.randomUUID(), "주소", 0.0, 0.0, 3));
        var hc = courses.saveAndFlush(new HealingCourse(foreign, "HC", "외부", null, null, null));
        var hs = spots.saveAndFlush(new HealingSpot(hc, "HS", "외부", 0.0, 0.0));
        var f = draft(); f.getPositions().set(1, point(hs.getSpotId(), "10", "20"));
        assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(positions.findBySiteImageImageId(a)).hasSize(2);
    }
    @Test void foreignImageIsRejected() {
        Site foreign = sites.saveAndFlush(new Site("Foreign " + UUID.randomUUID(), "주소", 0.0, 0.0, 3));
        var image = images.saveAndFlush(new SiteImage(foreign, "foreign.png", "foreign.png", "image/png", 1, true));
        var f = draft(); f.setSiteImageId(image.getImageId()); assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"-0.00001", "100.00001", "1000"})
    void invalidCoordinatesRollBackWholeBatch(String invalid) {
        for (boolean x : List.of(true, false)) {
            var f = draft(); f.getPositions().set(1, point(second.getSpotId(), x ? invalid : "0", x ? "0" : invalid));
            assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
            assertThat(positions.findBySiteImageImageId(a)).isEmpty();
        }
    }
    @Test void partialCoordinateIsRejected() {
        var f = draft(); f.getPositions().set(0, point(first.getSpotId(), "10", null));
        assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateAndMissingSpotsAreRejected() {
        var f = draft(); f.getPositions().set(1, point(first.getSpotId(), "0", "0"));
        assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
        f.setPositions(List.of(point(first.getSpotId(), "0", "0"))); assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseRejectsDuplicateImageSpotPair() {
        save(draft());
        assertThatThrownBy(() -> positions.saveAndFlush(new SiteImageSpotPosition(images.findById(a).orElseThrow(), first, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(positions.findBySiteImageImageId(a)).hasSize(2);
    }
    @Test void staleImageAndAbaAndConcurrentSaveAreRejected() {
        var f = draft(); select(b); assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
        select(a); assertThatThrownBy(() -> save(f)).isInstanceOf(IllegalArgumentException.class);
        var current = draft(); save(current); assertThatThrownBy(() -> save(current)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void actualImageDeletionRemovesPositions(boolean staged) {
        save(draft());
        if (staged) { var f = editForm(""); f.setImageOrder("e:" + b); f.setImageDeleted("e:" + a); siteImages.edit(site.getSiteId(), List.of(), f); }
        else siteImages.delete(site.getSiteId(), a);
        assertThat(positions.findBySiteImageImageId(a)).isEmpty(); assertThat(images.existsById(a)).isFalse();
    }
    @Test void failedStorageDeletionPreservesImageAndPositions() {
        save(draft()); doThrow(new ImageStorageException("failed")).when(storage).delete(anyString());
        assertThatThrownBy(() -> siteImages.delete(site.getSiteId(), a)).isInstanceOf(ImageStorageException.class);
        assertThat(images.findById(a).orElseThrow().isSpatial()).isTrue(); assertThat(positions.findBySiteImageImageId(a)).hasSize(2);
    }
    @Test void databaseRollbackRestoresPositionsAndObjectAfterDeletion() {
        save(draft()); var keys = Set.copyOf(objects.keySet());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            siteImages.delete(site.getSiteId(), a); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(images.findById(a).orElseThrow().isSpatial()).isTrue(); assertThat(positions.findBySiteImageImageId(a)).hasSize(2);
        assertThat(objects.keySet()).isEqualTo(keys);
    }
    @Test void deletingSpotCleansPositionsWithoutBreakingDeletion() {
        save(draft()); spotService.deleteHealingSpot(first.getSpotId());
        assertThat(positions.findBySiteImageImageId(a)).hasSize(1); assertThat(spots.existsById(first.getSpotId())).isFalse();
    }
    @Test void movingCourseToAnotherSiteRemovesInvalidPositions() {
        save(draft()); Site other = sites.saveAndFlush(new Site("Move " + UUID.randomUUID(), "주소", 0.0, 0.0, 3));
        courseService.updateHealingCourse(course.getCourseId(), other.getSiteId(), "HC-A", "코스", null, null, null);
        assertThat(positions.findBySiteImageImageId(a)).isEmpty();
    }
    @Test void urlsUseExistingServicesAndMissingRepresentativeHasFallback() throws Exception {
        spotImages.upload(first.getSpotId(), List.of(file("hs.png")));
        var layout = layouts.load(site.getSiteId());
        assertThat(layout.image().readUrl()).isEqualTo("/admin/sites/" + site.getSiteId() + "/images/" + a + "/content");
        assertThat(layout.spots().getFirst().readUrl()).startsWith("/admin/spots/" + first.getSpotId() + "/images/");
        assertThat(layout.spots().get(1).readUrl()).isNull();
        mvc.perform(get(path()).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andExpect(content().string(containsString("HS2 · 숲"))).andExpect(content().string(containsString("spatial-image")));
    }
    @Test void noSpatialImageAndNoSpotsRenderGracefully() throws Exception {
        select(null);
        mvc.perform(get(path()).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andExpect(content().string(containsString("모니터링 이미지가 아직 지정되지 않았습니다.")));
        select(a); spotService.deleteHealingSpot(first.getSpotId()); spotService.deleteHealingSpot(second.getSpotId());
        mvc.perform(get(path()).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andExpect(content().string(containsString("등록된 HealingSpot이 없습니다.")));
    }
    @Test void postSavesFullStateAndRejectsMalformedCoordinates() throws Exception {
        mvc.perform(post(path()).sessionAttr("loginAdminId", 1L).with(csrf())
                .param("siteImageId", a.toString()).param("revision", draft().getRevision().toString())
                .param("positions[0].spotId", first.getSpotId().toString()).param("positions[0].xPercent", "37.4825").param("positions[0].yPercent", "62.1180")
                .param("positions[1].spotId", second.getSpotId().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        assertThat(positions.findBySiteImageImageId(a)).hasSize(1);
        mvc.perform(post(path()).sessionAttr("loginAdminId", 1L).with(csrf())
                .param("siteImageId", a.toString()).param("revision", draft().getRevision().toString())
                .param("positions[0].spotId", first.getSpotId().toString()).param("positions[0].xPercent", "NaN"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
    }
    @Test void accessRequiresLoginAndCsrf() throws Exception {
        mvc.perform(get(path())).andExpect(status().is3xxRedirection());
        mvc.perform(post(path()).sessionAttr("loginAdminId", 1L)).andExpect(status().isForbidden());
    }
    @Test void siteEditorShowsIndependentSpatialControls() throws Exception {
        mvc.perform(get("/admin/sites/" + site.getSiteId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(content().string(containsString("data-spatial-enabled=\"true\"")))
                .andExpect(content().string(containsString("data-spatial=\"true\"")));
    }
    @Test void simultaneousSavesAcceptExactlyOneDraft() throws Exception {
        var one = draft(); var two = draft();
        two.getPositions().getFirst().setXPercent(new BigDecimal("70"));
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> firstSave = () -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try { save(one); return true; } catch (IllegalArgumentException stale) { return false; }
            };
            java.util.concurrent.Callable<Boolean> secondSave = () -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try { save(two); return true; } catch (IllegalArgumentException stale) { return false; }
            };
            var resultOne = pool.submit(firstSave); var resultTwo = pool.submit(secondSave);
            assertThat(List.of(resultOne.get(10, java.util.concurrent.TimeUnit.SECONDS), resultTwo.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(positions.findBySiteImageImageId(a)).hasSize(2);
    }
    @Test void malformedSpatialKeyDoesNotUploadOrChangeSelection() {
        var form = editForm("e:99999999"); form.setImageOrder("e:" + a + ",e:" + b + ",n:0");
        var before = Set.copyOf(objects.keySet());
        assertThatThrownBy(() -> siteImages.edit(site.getSiteId(), List.of(file("new.png")), form))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(objects.keySet()).isEqualTo(before);
        assertThat(layouts.load(site.getSiteId()).image().imageId()).isEqualTo(a);
    }
    @Test void omittedSpatialFieldPreservesExistingSelection() {
        var form = editForm(null); siteImages.edit(site.getSiteId(), List.of(), form);
        assertThat(layouts.load(site.getSiteId()).image().imageId()).isEqualTo(a);
    }
    @Test void stalePostShowsReentryMessageAndDoesNotSave() throws Exception {
        var revision = draft().getRevision(); select(b);
        mvc.perform(post(path()).sessionAttr("loginAdminId", 1L).with(csrf())
                .param("siteImageId", a.toString()).param("revision", revision.toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("errorMessage", containsString("다시 진입")));
        assertThat(positions.findBySiteImageImageId(a)).isEmpty(); assertThat(positions.findBySiteImageImageId(b)).isEmpty();
    }
    @Test void movingSpotToAnotherSiteCleansOnlyItsPositions() {
        save(draft()); Site other = sites.saveAndFlush(new Site("Move spot " + UUID.randomUUID(), "주소", 0.0, 0.0, 3));
        var hc = courses.saveAndFlush(new HealingCourse(other, "HC-B", "다른 코스", null, null, null));
        spotService.updateHealingSpot(first.getSpotId(), hc.getCourseId(), "HS1", "정원", 0.0, 0.0);
        assertThat(positions.findBySiteImageImageId(a)).hasSize(1);
    }
    @Test void deleteAllImagesCleansPositionsInSameTransaction() {
        save(draft()); siteImages.deleteAll(site.getSiteId());
        assertThat(positions.findBySiteImageImageId(a)).isEmpty(); assertThat(siteImages.list(site.getSiteId())).isEmpty();
    }

    @Test void monitoringUsesRepresentativeAndRefreshesWithoutUsingSpatial() throws Exception {
        select(b);
        String firstUrl = siteImages.representativeReadUrl(site.getSiteId());
        String html = monitoringHtml();
        assertThat(html).contains("data-representative-image-url=\"" + firstUrl + "\"");
        assertThat(html).doesNotContain("/images/" + b + "/content");
        siteImages.setRepresentative(site.getSiteId(), b);
        assertThat(monitoringHtml()).contains("data-representative-image-url=\"" + siteImages.representativeReadUrl(site.getSiteId()) + "\"")
                .doesNotContain(firstUrl);
    }

    @Test void monitoringProvidesIndependentSiteUrlsAndEmptyPlaceholder() throws Exception {
        Site other = sites.saveAndFlush(new Site("Monitoring " + UUID.randomUUID(), "주소", 38.0, 128.0, 4));
        assertThat(siteImages.representativeReadUrl(other.getSiteId())).isNull();
        assertThat(monitoringHtml()).contains("id=\"siteImageEmpty\"", "등록된 이미지가 없습니다.", other.getName());
        siteImages.upload(other.getSiteId(), List.of(file("other.png")));
        assertThat(monitoringHtml()).contains(siteImages.representativeReadUrl(site.getSiteId()),
                siteImages.representativeReadUrl(other.getSiteId()));
        siteImages.deleteAll(site.getSiteId());
        assertThat(siteImages.representativeReadUrl(site.getSiteId())).isNull();
        assertThat(monitoringHtml()).contains("id=\"siteImageEmpty\"");
    }

    @Test void monitoringSpotApiReflectsRepresentativeChangesAndMissingImages() throws Exception {
        spotImages.upload(first.getSpotId(), List.of(file("one.png"), file("two.png")));
        var photos = spotImages.list(first.getSpotId());
        String api = "/api/sites/" + site.getSiteId() + "/spots";
        mvc.perform(get(api).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].representativeImageUrl").value(photos.getFirst().readUrl()))
                .andExpect(jsonPath("$[1].representativeImageUrl").isEmpty());
        spotImages.setRepresentative(first.getSpotId(), photos.getLast().imageId());
        mvc.perform(get(api).sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$[0].representativeImageUrl").value(photos.getLast().readUrl()));
        spotImages.deleteAll(first.getSpotId());
        mvc.perform(get(api).sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$[0].representativeImageUrl").isEmpty());
    }

    @Test void monitoringUsesStorageGeneratedUrlsAndKeepsThemPrivate() throws Exception {
        when(storage.createReadUrl(anyString(), anyString())).thenReturn("https://storage.example/signed?token=opaque");
        assertThat(monitoringHtml()).contains("https://storage.example/signed?token=opaque");
        spotImages.upload(first.getSpotId(), List.of(file("hs.png")));
        String api = "/api/sites/" + site.getSiteId() + "/spots";
        mvc.perform(get(api).sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$[0].representativeImageUrl").value("https://storage.example/signed?token=opaque"));
        mvc.perform(get(api)).andExpect(jsonPath("$[0].representativeImageUrl").isEmpty());
        verify(storage, atLeastOnce()).createReadUrl(anyString(), startsWith("/admin/sites/"));
        verify(storage, atLeastOnce()).createReadUrl(anyString(), startsWith("/admin/spots/"));
    }

    @Test void monitoringUxTemplatesKeepEditingPrimaryAndNavigationCompact() throws Exception {
        String html = mvc.perform(get(path()).sessionAttr("loginAdminId", 1L)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("공간 모니터링 설정", "spatial-navigation", "HS 위치 설정", "HS 위치 저장", "이미지 변경", "#place-images")
                .doesNotContain("공간 배치", "Site 수정 · 이미지 지정");
        String detail = mvc.perform(get("/admin/sites/" + site.getSiteId()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(detail).contains("site-management-actions");
        assertThat(detail.indexOf("HC 관리")).isLessThan(detail.indexOf("공간 모니터링 설정"));
        assertThat(detail.indexOf("공간 모니터링 설정")).isLessThan(detail.indexOf("Site 수정"));
        assertThat(detail.indexOf("Site 수정")).isLessThan(detail.indexOf("Site 삭제"));
        assertThat(detail.indexOf("Site 삭제")).isLessThan(detail.indexOf("Site 목록으로"));
        mvc.perform(get("/admin/sites/" + site.getSiteId() + "/edit").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/spatial-layout"))));
        select(null);
        mvc.perform(get(path()).sessionAttr("loginAdminId", 1L)).andExpect(content().string(containsString("모니터링 이미지 지정")))
                .andExpect(content().string(containsString("Site 수정 화면에서")));
    }

    @Test void monitoringLayoutApiUsesSpatialImageAndOnlyItsSavedPositions() throws Exception {
        save(draft());
        select(b);
        var form = draft(); form.setSiteImageId(b);
        form.setPositions(List.of(point(first.getSpotId(), "76", "12"), point(second.getSpotId(), null, null)));
        save(form);
        spotImages.upload(first.getSpotId(), List.of(file("old.png"), file("representative.png")));
        var representative = spotImages.list(first.getSpotId()).getLast();
        spotImages.setRepresentative(first.getSpotId(), representative.imageId());
        String spatialUrl = siteImages.list(site.getSiteId()).stream().filter(ImageResponse::spatial).findFirst().orElseThrow().readUrl();
        assertThat(spatialUrl).isNotEqualTo(siteImages.representativeReadUrl(site.getSiteId()));
        mvc.perform(get(path() + "/data").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.image.imageId").value(b))
                .andExpect(jsonPath("$.image.readUrl").value(spatialUrl))
                .andExpect(jsonPath("$.image.spatial").value(true))
                .andExpect(jsonPath("$.image.representative").value(false))
                .andExpect(jsonPath("$.spots[0].spotId").value(first.getSpotId()))
                .andExpect(jsonPath("$.spots[0].courseId").value(course.getCourseId()))
                .andExpect(jsonPath("$.spots[0].courseCode").value(course.getCode()))
                .andExpect(jsonPath("$.spots[0].courseName").value(course.getName()))
                .andExpect(jsonPath("$.spots[0].xPercent").value(76))
                .andExpect(jsonPath("$.spots[0].yPercent").value(12))
                .andExpect(jsonPath("$.spots[0].readUrl").value(representative.readUrl()))
                .andExpect(jsonPath("$.spots[1].xPercent").isEmpty())
                .andExpect(jsonPath("$.spots[1].readUrl").isEmpty());
        select(a);
        mvc.perform(get(path() + "/data").sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$.spots[0].xPercent").value(20.1235));
    }

    @Test void monitoringLayoutApiHandlesMissingImageAndUnplacedSpotsWithoutFallback() throws Exception {
        mvc.perform(get(path() + "/data").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.image.imageId").value(a))
                .andExpect(jsonPath("$.spots[0].xPercent").isEmpty());
        select(null);
        mvc.perform(get(path() + "/data").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.image").isEmpty())
                .andExpect(jsonPath("$.spots[0].xPercent").isEmpty());
        assertThat(siteImages.representativeReadUrl(site.getSiteId())).isNotNull();
    }

    @Test void monitoringLayoutApiRequiresAdminAndReturnsNotFoundForMissingSite() throws Exception {
        mvc.perform(get(path() + "/data")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get(path() + "/data").sessionAttr("loginMemberId", 1L)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/sites/9223372036854775807/spatial-layout/data").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isNotFound());
    }

    @Test void monitoringLayoutApiIsolatesSitesAndUsesStorageReadUrls() throws Exception {
        save(draft());
        Site other = sites.saveAndFlush(new Site("Other", "주소", 38.0, 128.0, 4));
        mvc.perform(get("/admin/sites/" + other.getSiteId() + "/spatial-layout/data").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.siteId").value(other.getSiteId()))
                .andExpect(jsonPath("$.image").isEmpty()).andExpect(jsonPath("$.spots").isEmpty());
        when(storage.createReadUrl(anyString(), anyString())).thenReturn("https://storage.example/signed?token=opaque");
        mvc.perform(get(path() + "/data").sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$.image.readUrl").value("https://storage.example/signed?token=opaque"));
    }

    @Test void monitoringTemplatePlacesMapAndLegendInsideSecondaryDialog() throws Exception {
        String html = monitoringHtml();
        assertThat(html).contains("공간 모니터링", "지도 보기", "id=\"monitoringCanvas\"", "/js/home-spatial.js", "/css/home-spatial.css",
                        "monitoring-effect-legend", "개선율 낮음", "개선율 높음", "유효 측정 중 개선된 횟수의 비율", "/js/home-course-overlay.js")
                .doesNotContain("id=\"hcStress\"", "id=\"hcEmotional\"")
                .doesNotContain("Demo 데이터", "스트레스 수준", "이완감 수준", "id=\"metricSelect\"");
        int dialogStart = html.indexOf("id=\"mapModal\"");
        assertThat(html.indexOf("id=\"map\"")).isGreaterThan(dialogStart);
        assertThat(html.indexOf("id=\"surveyLegendTitle\"")).isGreaterThan(dialogStart);
        assertThat(html.indexOf("id=\"monitoringCanvas\"")).isLessThan(dialogStart);
        assertThat(html).contains("id=\"closeMapButton\"", "aria-labelledby=\"mapModalTitle\"");
    }

    private String monitoringHtml() throws Exception {
        return mvc.perform(get("/admin/monitoring").sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void monitoringGalleryUsesOrderedStorageUrlsAndSiteScope() throws Exception {
        spotImages.upload(first.getSpotId(), List.of(file("first.png"), file("representative.png")));
        var gallery = spotImages.list(first.getSpotId());
        spotImages.setRepresentative(first.getSpotId(), gallery.getLast().imageId());
        String endpoint = "/api/sites/" + site.getSiteId() + "/spots";
        mvc.perform(get(endpoint).param("spotId", first.getSpotId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].courseCode").value(course.getCode()))
                .andExpect(jsonPath("$[0].representativeImageUrl").value(gallery.getLast().readUrl()))
                .andExpect(jsonPath("$[0].images[0].imageId").value(gallery.getFirst().imageId()))
                .andExpect(jsonPath("$[0].images[0].displayOrder").value(1))
                .andExpect(jsonPath("$[0].images[1].representative").value(true))
                .andExpect(jsonPath("$[0].images[1].readUrl").value(gallery.getLast().readUrl()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("objectKey"))));
        var otherSite = sites.saveAndFlush(new Site("Other gallery", "주소", 37.0, 127.0, 3));
        mvc.perform(get("/api/sites/" + otherSite.getSiteId() + "/spots")
                        .param("spotId", first.getSpotId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(endpoint).param("spotId", second.getSpotId().toString()).sessionAttr("loginAdminId", 1L))
                .andExpect(jsonPath("$[0].images").isEmpty());
        mvc.perform(get(endpoint).param("spotId", first.getSpotId().toString()).sessionAttr("loginMemberId", 1L))
                .andExpect(jsonPath("$[0].images").isEmpty())
                .andExpect(jsonPath("$[0].representativeImageUrl").isEmpty());
    }

    @Test void singleSpotDetailIncludesSiteCourseAndOrderedOriginalGallery() throws Exception {
        spotImages.upload(first.getSpotId(), List.of(file("first.png"), file("second.png"), file("third.png")));
        var gallery = spotImages.list(first.getSpotId());
        spotImages.move(first.getSpotId(), gallery.getLast().imageId(), "up");
        spotImages.setRepresentative(first.getSpotId(), gallery.get(1).imageId());
        String endpoint = "/api/sites/" + site.getSiteId() + "/spots/" + first.getSpotId();
        mvc.perform(get(endpoint).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.spotId").value(first.getSpotId()))
                .andExpect(jsonPath("$.code").value(first.getCode()))
                .andExpect(jsonPath("$.name").value(first.getName()))
                .andExpect(jsonPath("$.latitude").value(first.getLatitude()))
                .andExpect(jsonPath("$.longitude").value(first.getLongitude()))
                .andExpect(jsonPath("$.courseId").value(course.getCourseId()))
                .andExpect(jsonPath("$.courseCode").value(course.getCode()))
                .andExpect(jsonPath("$.courseName").value(course.getName()))
                .andExpect(jsonPath("$.siteId").value(site.getSiteId()))
                .andExpect(jsonPath("$.siteName").value(site.getName()))
                .andExpect(jsonPath("$.siteAddress").value(site.getAddress()))
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.images[0].imageId").value(gallery.get(0).imageId()))
                .andExpect(jsonPath("$.images[1].imageId").value(gallery.get(2).imageId()))
                .andExpect(jsonPath("$.images[2].imageId").value(gallery.get(1).imageId()))
                .andExpect(jsonPath("$.images[2].displayOrder").value(3))
                .andExpect(jsonPath("$.images[2].representative").value(true))
                .andExpect(jsonPath("$.images[2].readUrl").value(gallery.get(1).readUrl()))
                .andExpect(jsonPath("$.representativeImageUrl").value(gallery.get(1).readUrl()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/thumbnail"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("objectKey"))));
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/spots/" + second.getSpotId()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.representativeImageUrl").isEmpty());
        mvc.perform(get(endpoint).sessionAttr("loginMemberId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.representativeImageUrl").isEmpty());
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/spots").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].siteAddress").doesNotExist());
        String html = monitoringHtml();
        String modal = html.substring(html.indexOf("id=\"spotDetailModal\""), html.indexOf("id=\"mapModal\""));
        assertThat(modal).contains("id=\"spotSiteName\"", "id=\"spotSiteAddress\"", "측정 데이터")
                .doesNotContain("시연용 데이터", "방문 횟수");
    }

    @Test void singleSpotDetailRejectsForeignAndMissingSiteOrSpot() throws Exception {
        var other = sites.saveAndFlush(new Site("Detail scope " + UUID.randomUUID(), "다른 주소", 38.0, 128.0, 3));
        for (String path : List.of(
                "/api/sites/" + other.getSiteId() + "/spots/" + first.getSpotId(),
                "/api/sites/-1/spots/" + first.getSpotId(),
                "/api/sites/" + site.getSiteId() + "/spots/-1")) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
    }

}
