package com.example.manage;

import com.example.manage.config.*;
import com.example.manage.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.nio.file.*;
import java.time.Duration;
import java.net.URI;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageStorageTests {
    @TempDir Path temporary;
    private final ImageFilePolicy policy = new ImageFilePolicy();

    @ParameterizedTest @CsvSource({"image/jpeg,jpg", "image/png,png", "image/webp,webp"})
    void allowedTypesUseSafeUniqueKeys(String type, String extension) {
        byte[] bytes = switch (type) {
            case "image/jpeg" -> ImageTestFiles.jpeg();
            case "image/png" -> ImageTestFiles.png();
            case "image/webp" -> ImageTestFiles.webp();
            default -> throw new IllegalArgumentException(type);
        };
        var file = new MockMultipartFile("files", "../../malicious.exe", type, bytes);
        policy.validate(file);
        String key = policy.createKey(true, 1L, type);
        assertThat(key).startsWith("sites/1/").endsWith("." + extension).doesNotContain("malicious");
        assertThat(policy.createKey(true, 1L, type)).isNotEqualTo(key);
        ImageFilePolicy.validateKey(key);
    }
    @Test void emptyFileRejected() {
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", new byte[0])))
                .hasMessageContaining("빈 파일");
    }
    @ParameterizedTest @ValueSource(strings = {"image/gif", "text/html", "application/octet-stream", ""})
    void unsupportedContentTypeRejected(String type) {
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "test.jpg", type, new byte[]{1})))
                .hasMessageContaining("JPEG");
    }
    @Test void tenMegabyteBoundary() {
        assertThatCode(() -> policy.validate(new MockMultipartFile("files", "test.jpg", "image/jpeg",
                ImageTestFiles.jpeg()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "test.jpg", "image/jpeg",
                new byte[(int) ImageFilePolicy.MAX_FILE_SIZE + 1]))).hasMessageContaining("10MB");
    }
    @Test void disguisedNonImageAndMismatchedTypeAreRejected() {
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "fake.jpg", "image/jpeg",
                "<html>not an image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .hasMessageContaining("이미지 파일 내용");
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "wrong.jpg", "image/jpeg",
                ImageTestFiles.png()))).hasMessageContaining("일치하지");
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "wrong.webp", "image/webp",
                ImageTestFiles.jpeg()))).hasMessageContaining("일치하지");
    }
    @Test void truncatedOrCorruptImagesAreRejected() {
        byte[] jpeg = ImageTestFiles.jpeg();
        byte[] png = ImageTestFiles.png();
        byte[] webp = ImageTestFiles.webp();
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "cut.jpg", "image/jpeg",
                Arrays.copyOf(jpeg, jpeg.length - 2)))).hasMessageContaining("이미지 파일 내용");
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "cut.png", "image/png",
                Arrays.copyOf(png, png.length - 12)))).hasMessageContaining("이미지 파일 내용");
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "cut.webp", "image/webp",
                Arrays.copyOf(webp, webp.length - 2)))).hasMessageContaining("이미지 파일 내용");
        webp[20] = 0;
        webp[21] = 0;
        webp[22] = 0; // Invalid VP8 first-partition length despite intact RIFF framing.
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "corrupt.webp", "image/webp",
                webp))).hasMessageContaining("이미지 파일 내용");
        png[png.length - 5] ^= 1; // IEND CRC must still match the chunk contents.
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "corrupt.png", "image/png",
                png))).hasMessageContaining("이미지 파일 내용");
    }
    @Test void excessivePixelDimensionsAreRejectedBeforeDecode() {
        byte[] png = ImageTestFiles.png();
        png[18] = 0x27;
        png[19] = 0x11; // IHDR width: 10,001 pixels.
        var crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        long value = crc.getValue();
        for (int index = 0; index < 4; index++) png[29 + index] = (byte) (value >>> (24 - index * 8));
        assertThatThrownBy(() -> policy.validate(new MockMultipartFile("files", "large.png", "image/png", png)))
                .hasMessageContaining("해상도");
    }
    @Test void originalNameIsOnlySanitizedMetadata() {
        assertThat(policy.displayName("C:\\fakepath\\name.png")).isEqualTo("name.png");
        assertThat(policy.displayName("a".repeat(300))).hasSize(255);
        assertThat(policy.displayName("line\n.png")).isEqualTo("line.png");
    }
    @Test void localUploadReadDeleteRoundTrip() throws Exception {
        var storage = new LocalImageStorage(temporary);
        String key = policy.createKey(false, 2L, "image/png");
        storage.upload(key, new byte[]{1, 2}, "image/png");
        assertThat(storage.read(key)).containsExactly(1, 2);
        assertThat(storage.createReadUrl(key, "/admin/spots/2/images/1/content")).isEqualTo("/admin/spots/2/images/1/content");
        storage.delete(key); assertThat(temporary.resolve(key)).doesNotExist();
        storage.delete(key); // Idempotent compensation.
    }
    @ParameterizedTest @ValueSource(strings = {"../secret", "/etc/passwd", "sites/1/../../secret", "images/site1/site1.png"})
    void arbitraryObjectPathsRejected(String key) {
        var storage = new LocalImageStorage(temporary);
        assertThatThrownBy(() -> storage.upload(key, new byte[]{1}, "image/png")).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ImageStorageException.class);
    }
    @Test void localSymlinkCannotEscapeUploadDirectory() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.createSymbolicLink(root.resolve("sites"), outside);
        var storage = new LocalImageStorage(root);
        assertThatThrownBy(() -> storage.upload(policy.createKey(true, 1L, "image/png"), new byte[]{1}, "image/png"))
                .isInstanceOf(ImageStorageException.class);
    }
    private ImageStorageProperties properties(String mode) {
        return new ImageStorageProperties(mode, "", "", "", "auto", "", Duration.ofHours(1), temporary, temporary);
    }
    @Test void missingCredentialsLeaveApplicationUsableButImageOperationsFailClearly() {
        ImageStorage storage = new ImageStorageConfig().imageStorage(properties("s3"));
        assertThat(storage).isInstanceOf(DisabledImageStorage.class);
        assertThatThrownBy(() -> storage.upload("key", new byte[]{1}, "image/jpeg")).hasMessageContaining("설정되지");
        assertThatThrownBy(() -> storage.delete("key")).hasMessageContaining("설정되지");
        assertThatThrownBy(() -> storage.createReadUrl("key", "/admin/sites/1/images/1/content")).hasMessageContaining("설정되지");
    }
    @Test void localModeDoesNotNeedCredentials() {
        assertThat(new ImageStorageConfig().imageStorage(properties("local"))).isInstanceOf(LocalImageStorage.class);
    }
    @Test void s3VariantUsesConfiguredBucketAndOnly404MeansMissing() {
        var client = mock(S3Client.class);
        var storage = new S3ImageStorage(client, mock(S3Presigner.class), properties("s3"));
        String key = com.example.manage.service.MonitoringThumbnailService.key(policy.createKey(false, 2L, "image/png"));
        var request = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class);
        assertThat(storage.exists(key)).isTrue();
        verify(client).headObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(properties("s3").bucket());
        assertThat(request.getValue().key()).isEqualTo(key);
        when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        assertThat(storage.exists(key)).isFalse();
        when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).build());
        assertThatThrownBy(() -> storage.exists(key)).isInstanceOf(ImageStorageException.class);
        when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(500).build());
        assertThatThrownBy(() -> storage.exists(key)).isInstanceOf(ImageStorageException.class);
    }

    @Test void s3SigningIsOfflineVirtualHostedAndExpiresInOneHour() {
        // Synthetic test-only credentials. Signing is local; client is mocked, no network call.
        var p = new ImageStorageProperties("s3", "unit-test-bucket", "test-access", "test-secret", "auto",
                "https://storage.example.invalid", Duration.ofHours(1), temporary, temporary);
        try (var presigner = S3Presigner.builder().endpointOverride(URI.create(p.endpoint())).region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKeyId(), p.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build()).build()) {
            S3Client client = mock(S3Client.class);
            String key = com.example.manage.service.MonitoringThumbnailService.key(policy.createKey(false, 1L, "image/jpeg"));
            String url = new S3ImageStorage(client, presigner, p).createReadUrl(key, "/admin/sites/1/images/1/content");
            assertThat(URI.create(url).getHost()).isEqualTo("unit-test-bucket.storage.example.invalid");
            assertThat(URI.create(url).getPath()).isEqualTo("/" + key);
            assertThat(url.contains("X-Amz-Expires=3600")).isTrue();
            assertThat(url.contains("X-Amz-Signature=")).isTrue();
            verifyNoInteractions(client);
        }
    }
}
