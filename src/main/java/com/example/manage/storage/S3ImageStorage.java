package com.example.manage.storage;

import com.example.manage.config.ImageStorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class S3ImageStorage implements ImageStorage, AutoCloseable {
    private final S3Client client;
    private final S3Presigner presigner;
    private final ImageStorageProperties properties;
    private final Clock clock;
    private final Duration safetyMargin;
    private final String cacheControl;
    // Bounded per storage instance (and therefore per bucket). Never log cached values.
    private final Map<String, CachedUrl> readUrls = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedUrl> eldest) {
            return size() > 10_000;
        }
    };
    private static final class CachedUrl {
        final String url;
        final Instant reusableUntil;
        CachedUrl(String url, Instant reusableUntil) { this.url = url; this.reusableUntil = reusableUntil; }
    }
    public S3ImageStorage(S3Client client, S3Presigner presigner, ImageStorageProperties properties) {
        this(client, presigner, properties, Clock.systemUTC());
    }
    S3ImageStorage(S3Client client, S3Presigner presigner, ImageStorageProperties properties, Clock clock) {
        this.client = client; this.presigner = presigner; this.properties = properties;
        this.clock = clock;
        safetyMargin = properties.readUrlDuration().dividedBy(6);
        // At most five minutes of browser freshness, also bounded by half the signing margin.
        cacheControl = "private, max-age=" + Math.min(300, safetyMargin.dividedBy(2).toSeconds());
    }
    @Override public synchronized void upload(String key, byte[] content, String contentType) {
        ImageFilePolicy.validateKey(key);
        try {
            client.putObject(b -> b.bucket(properties.bucket()).key(key).contentType(contentType)
                    .cacheControl(cacheControl), RequestBody.fromBytes(content));
        } catch (RuntimeException e) { throw new ImageStorageException("이미지 저장소 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.", e); }
        finally { readUrls.remove(key); }
    }
    @Override public boolean exists(String key) {
        ImageFilePolicy.validateKey(key);
        try {
            client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(properties.bucket()).key(key).build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw new ImageStorageException("이미지 존재 여부를 확인할 수 없습니다.", e);
        } catch (RuntimeException e) { throw new ImageStorageException("이미지 존재 여부를 확인할 수 없습니다.", e); }
    }
    @Override public synchronized void delete(String key) {
        ImageFilePolicy.validateKey(key);
        try { client.deleteObject(b -> b.bucket(properties.bucket()).key(key)); }
        catch (RuntimeException e) { throw new ImageStorageException("이미지 저장소 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요.", e); }
        finally { readUrls.remove(key); }
    }
    @Override public byte[] read(String key) {
        ImageFilePolicy.validateKey(key);
        try { return client.getObjectAsBytes(b -> b.bucket(properties.bucket()).key(key)).asByteArray(); }
        catch (RuntimeException e) { throw new ImageStorageException("이미지 저장소에서 파일을 읽을 수 없습니다.", e); }
    }
    @Override public synchronized String createReadUrl(String key, String localReadPath) {
        ImageFilePolicy.validateKey(key);
        try {
            Instant now = clock.instant();
            CachedUrl cached = readUrls.get(key);
            if (cached != null && now.isBefore(cached.reusableUntil)) return cached.url;
            readUrls.remove(key);
            var signed = presigner.presignGetObject(b -> b.signatureDuration(properties.readUrlDuration())
                    .getObjectRequest(r -> r.bucket(properties.bucket()).key(key)));
            Instant configuredExpiry = now.plus(properties.readUrlDuration());
            Instant expiry = signed.expiration().isBefore(configuredExpiry) ? signed.expiration() : configuredExpiry;
            Instant reusableUntil = expiry.minus(safetyMargin);
            // A slow signer or unexpectedly short signature must never yield an expired URL.
            if (!clock.instant().isBefore(reusableUntil))
                throw new ImageStorageException("이미지 조회 URL의 남은 유효시간이 부족합니다.");
            String url = signed.url().toString();
            readUrls.put(key, new CachedUrl(url, reusableUntil));
            return url;
        } catch (RuntimeException e) { throw new ImageStorageException("이미지 조회 URL을 만들 수 없습니다.", e); }
    }
    @Override public void close() { client.close(); presigner.close(); }
}
