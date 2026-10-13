package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:local-image-http;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false"})
@ActiveProfiles({"test", "local"})
@Import(LocalImageHttpTests.TestEndpoints.class)
class LocalImageHttpTests {
    private static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("manage-local-image-http-"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.local-directory", () -> ROOT.resolve("uploads").toString());
        registry.add("storage.recovery-directory", () -> ROOT.resolve("recovery").toString());
    }
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired HealingEffectImportBatchRepository effectBatches;
    @Autowired SiteImageService siteImages;
    @Autowired HealingSpotImageService spotImages;
    HttpClient client;
    String csrf;

    // Test-only endpoint: the production application never imports this configuration.
    @TestConfiguration static class TestEndpoints {
        @Bean TestLogin testLogin() { return new TestLogin(); }
    }
    @RestController static class TestLogin {
        @GetMapping("/test-image-session") String login(HttpServletRequest request) {
            request.getSession().setAttribute("loginAdminId", 1L);
            request.getSession().setAttribute("loginMemberId", 1L);
            return ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        }
    }
    @BeforeEach void setup() throws Exception {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        csrf = get("/test-image-session").body();
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    Long parent(boolean site) {
        Site s = sites.saveAndFlush(new Site("HTTP " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        if (site) return s.getSiteId();
        var c = courses.saveAndFlush(new HealingCourse(s, "HC-A", "회복", 37.0, 127.0, 40.0));
        return spots.saveAndFlush(new HealingSpot(c, "HS1", "곶자왈원", 37.0, 127.0)).getSpotId();
    }
    HttpResponse<String> upload(String path, int bytes, boolean headerToken) throws Exception {
        return upload(path, bytes, headerToken, Map.of());
    }
    HttpResponse<String> upload(String path, int bytes, boolean headerToken, Map<String, String> fields) throws Exception {
        String boundary = "ImageTestBoundary";
        StringBuilder parameters = new StringBuilder();
        fields.forEach((name, value) -> parameters.append("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        String body = parameters + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"test.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n" + "a".repeat(bytes) + "\r\n--" + boundary + "--\r\n";
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "text/html");
        if (headerToken) builder.header("X-CSRF-TOKEN", csrf);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void localMultipartAndAuthenticatedContentWork(boolean site) throws Exception {
        Long id = parent(site); String path = "/admin/" + (site ? "sites/" : "spots/") + id + "/images";
        assertThat(upload(path, 30, false).statusCode()).isEqualTo(302);
        var images = site ? siteImages.list(id) : spotImages.list(id);
        assertThat(images).hasSize(1);
        var content = get(images.getFirst().readUrl());
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).isEqualTo("a".repeat(30));
        assertThat(content.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(content.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(content.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        Long other = parent(site);
        assertThat(get("/admin/" + (site ? "sites/" : "spots/") + other + "/images/"
                + images.getFirst().imageId() + "/content").statusCode()).isEqualTo(404);
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + images.getFirst().readUrl())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(get(path.replace("/images", "/edit")).body()).contains("새 이미지 추가", "test.png", "대표 이미지");
    }
    @Test void thumbnailRouteChecksOwnershipAndAuthenticationAndKeepsOriginalRoute() throws Exception {
        Long id = parent(false);
        byte[] original = MonitoringThumbnailTests.photo(1200, 800);
        spotImages.upload(id, List.of(new org.springframework.mock.web.MockMultipartFile(
                "files", "photo.png", "image/png", original)));
        var photo = spotImages.list(id).getFirst();
        String url = spotImages.monitoringReadUrl(id);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
        var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
        assertThat(decoded.getWidth()).isEqualTo(560);
        var full = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + photo.readUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(full.body()).isEqualTo(original);
        assertThat(get("/admin/spots/" + parent(false) + "/images/" + photo.imageId() + "/thumbnail").statusCode()).isEqualTo(404);
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        spotImages.delete(id, photo.imageId());
        assertThat(get(url).statusCode()).isEqualTo(404);
    }

    @Test void memberMonitoringImagesUseDatasetSiteAndPrivateNoStoreResponses() throws Exception {
        effectBatches.deleteAll();
        Site dataset = sites.saveAndFlush(new Site("Dataset " + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        var course = courses.saveAndFlush(new HealingCourse(dataset, "HC-A", "회복", 37.0, 127.0, 40.0));
        var spot = spots.saveAndFlush(new HealingSpot(course, "HS1", "곶자왈원", 37.0, 127.0));
        var disallowedCode = spots.saveAndFlush(new HealingSpot(course, "HS7", "범위 밖 HS", 37.0, 127.0));
        Site outside = sites.saveAndFlush(new Site("Outside " + UUID.randomUUID(), "주소", 38.0, 128.0, 3));
        var outsideCourse = courses.saveAndFlush(new HealingCourse(outside, "HC-B", "외부", 38.0, 128.0, 40.0));
        var outsideSpot = spots.saveAndFlush(new HealingSpot(outsideCourse, "HS1", "외부 HS", 38.0, 128.0));

        byte[] sitePhoto = MonitoringThumbnailTests.photo(900, 600);
        siteImages.upload(dataset.getSiteId(), List.of(new org.springframework.mock.web.MockMultipartFile(
                "files", "site.png", "image/png", sitePhoto)));
        var spatial = siteImages.list(dataset.getSiteId()).getFirst();
        var siteEdit = new com.example.manage.dto.ImageEditForm();
        siteEdit.setImageOrder("e:" + spatial.imageId());
        siteEdit.setImageRepresentative("e:" + spatial.imageId());
        siteEdit.setImageSpatial("e:" + spatial.imageId());
        siteImages.edit(dataset.getSiteId(), List.of(), siteEdit);

        byte[] spotPhoto = MonitoringThumbnailTests.photo(1200, 800);
        spotImages.upload(spot.getSpotId(), List.of(
                new org.springframework.mock.web.MockMultipartFile("files", "spot.png", "image/png", spotPhoto),
                new org.springframework.mock.web.MockMultipartFile("files", "spot-two.png", "image/png", spotPhoto)));
        spotImages.upload(outsideSpot.getSpotId(), List.of(new org.springframework.mock.web.MockMultipartFile(
                "files", "outside.png", "image/png", spotPhoto)));
        effectBatches.saveAndFlush(new HealingEffectImportBatch("a".repeat(64), "member-images.xlsx",
                java.time.LocalDateTime.now(), 0, 0, dataset.getSiteId()));

        var adminSite = getBytes(siteImages.list(dataset.getSiteId()).getFirst().readUrl());
        var memberSite = getBytes("/member/api/monitoring/site-image");
        assertThat(memberSite.statusCode()).isEqualTo(200);
        assertThat(memberSite.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(memberSite.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
                assertThat(value).contains("private", "no-store"));
        assertThat(memberSite.headers().firstValueAsLong("Content-Length"))
                .hasValue(adminSite.headers().firstValueAsLong("Content-Length").orElseThrow());

        var adminSpot = getBytes(spotImages.monitoringReadUrl(spot.getSpotId()));
        var memberSpot = getBytes("/member/api/monitoring/spots/" + spot.getSpotId() + "/thumbnail");
        assertThat(memberSpot.statusCode()).isEqualTo(200);
        assertThat(memberSpot.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(memberSpot.headers().firstValueAsLong("Content-Length"))
                .hasValue(adminSpot.headers().firstValueAsLong("Content-Length").orElseThrow());

        var gallery = spotImages.list(spot.getSpotId());
        var detail = get("/member/api/monitoring/spots/" + spot.getSpotId());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
                assertThat(value).contains("no-store"));
        assertThat(detail.body()).contains(
                "\"spotId\":" + spot.getSpotId(),
                "\"siteId\":" + dataset.getSiteId(),
                "\"siteName\":\"" + dataset.getName() + "\"",
                "/member/api/monitoring/spots/" + spot.getSpotId() + "/images/"
                        + gallery.getFirst().imageId() + "/content",
                "/member/api/monitoring/spots/" + spot.getSpotId() + "/images/"
                        + gallery.getLast().imageId() + "/content")
                .doesNotContain("/admin/");

        var adminOriginal = getBytes(gallery.getFirst().readUrl());
        String memberContentUrl = "/member/api/monitoring/spots/" + spot.getSpotId() + "/images/"
                + gallery.getFirst().imageId() + "/content";
        var memberOriginal = getBytes(memberContentUrl);
        assertThat(memberOriginal.statusCode()).isEqualTo(200);
        assertThat(memberOriginal.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(memberOriginal.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
                assertThat(value).contains("private", "no-store"));
        assertThat(memberOriginal.headers().firstValueAsLong("Content-Length"))
                .hasValue(adminOriginal.headers().firstValueAsLong("Content-Length").orElseThrow());

        assertThat(get("/member/api/monitoring/spots/" + outsideSpot.getSpotId() + "/thumbnail").statusCode())
                .isEqualTo(404);
        assertThat(get("/member/api/monitoring/spots/" + disallowedCode.getSpotId() + "/thumbnail").statusCode())
                .isEqualTo(404);
        assertThat(get("/member/api/monitoring/spots/9223372036854775807/thumbnail").statusCode())
                .isEqualTo(404);
        Long outsideImageId = spotImages.list(outsideSpot.getSpotId()).getFirst().imageId();
        assertThat(get("/member/api/monitoring/spots/" + outsideSpot.getSpotId()).statusCode()).isEqualTo(404);
        assertThat(get("/member/api/monitoring/spots/" + outsideSpot.getSpotId() + "/images/"
                + outsideImageId + "/content").statusCode()).isEqualTo(404);
        assertThat(get("/member/api/monitoring/spots/" + spot.getSpotId() + "/images/"
                + outsideImageId + "/content").statusCode()).isEqualTo(404);
        assertThat(get("/member/api/monitoring/spots/" + spot.getSpotId()
                + "/images/9223372036854775807/content").statusCode()).isEqualTo(404);
        var anonymous = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/member/api/monitoring/site-image"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location")).hasValueSatisfying(value ->
                assertThat(value).endsWith("/member/login"));
        var anonymousDetail = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + memberContentUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymousDetail.statusCode()).isEqualTo(302);
        assertThat(anonymousDetail.headers().firstValue("Location")).hasValueSatisfying(value ->
                assertThat(value).endsWith("/member/login"));
    }

    HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void oversizedMultipartShowsFriendlyError() throws Exception {
        String path = "/admin/sites/" + parent(true) + "/images";
        var response = upload(path, 10 * 1024 * 1024 + 1, false);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("10MB", "파일 크기를 확인해 주세요");
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void integratedParentRegistrationAndEditUseRealMultipart(boolean site) throws Exception {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", "HTTP 등록 " + UUID.randomUUID());
        fields.put("latitude", "37.0"); fields.put("longitude", "127.0");
        if (site) { fields.put("address", "주소"); fields.put("mapLevel", "3"); }
        else {
            Long existing = parent(false);
            fields.put("courseId", spots.findById(existing).orElseThrow().getHealingCourse().getCourseId().toString());
            fields.put("code", "HS2");
        }
        String base = "/admin/" + (site ? "sites" : "spots");
        HttpResponse<String> created = upload(base + "/new", 30, false, fields);
        assertThat(created.statusCode()).isEqualTo(302);
        String detail = created.headers().firstValue("Location").orElseThrow();
        Long id = Long.valueOf(detail.substring(detail.lastIndexOf('/') + 1));
        String path = base + "/" + id;
        assertThat(get(path).body()).contains("data-gallery-main", "test.png").doesNotContain(">이미지 관리</a>");
        fields.put("name", "HTTP 수정 " + UUID.randomUUID());
        var existingImages = site ? siteImages.list(id) : spotImages.list(id);
        fields.put("imageOrder", "n:0,e:" + existingImages.getFirst().imageId());
        fields.put("imageRepresentative", "n:0");
        assertThat(upload(path + "/edit", 40, false, fields).statusCode()).isEqualTo(302);
        assertThat(site ? siteImages.list(id) : spotImages.list(id)).hasSize(2);
        assertThat(get(path).body()).contains(fields.get("name"), "class=\"image-thumbnails\"");
        assertThat(get(path + "/edit").body()).contains("data-image-editor", "새 이미지 추가").doesNotContain("data-image-action");
    }

    @AfterAll static void cleanup() throws Exception {
        // Only this test's isolated temporary directory is removed.
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
