package com.example.manage.service;

import com.example.manage.storage.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.function.Supplier;

/** Disposable derived objects: originals and DB metadata remain authoritative. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitoringThumbnailService {

    public static final int MAX_EDGE = 560;
    public static final float QUALITY = 0.82f;
    private final ImageStorage storage;
    // Bounded lock storage; concurrent requests in this JVM do not decode the same original twice.
    private final Object[] locks = java.util.stream.IntStream.range(0, 32).mapToObj(i -> new Object()).toArray();

    public static String key(String original) {
        ImageFilePolicy.validateKey(original);
        return original + ".monitoring-v1-560-q82.jpg";
    }

    public String readUrl(String original, String localPath, Supplier<String> fallback) {
        try {
            return storage.createReadUrl(ensure(original), localPath);
        } catch (RuntimeException e) {
            log.warn("Monitoring thumbnail unavailable; using original objectKey={}", original);
            return fallback.get();
        }
    }

    public HealingSpotImageService.ImageContent content(String original, String contentType) {
        try {
            return new HealingSpotImageService.ImageContent(storage.read(ensure(original)), "image/jpeg");
        } catch (RuntimeException e) {
            log.warn("Monitoring thumbnail unavailable; using original objectKey={}", original);
            return new HealingSpotImageService.ImageContent(storage.read(original), contentType);
        }
    }

    private String ensure(String original) {
        synchronized (lock(original)) {
            String variant = key(original);
            if (!storage.exists(variant)) {
                storage.upload(variant, render(storage.read(original)), "image/jpeg");
            }
            return variant;
        }
    }

    public void deleteAfterCommit(String original) {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Thumbnail cleanup requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                synchronized (lock(original)) {
                    try {
                        storage.delete(key(original));
                    } catch (RuntimeException e) {
                        // Derived file cleanup must not break a committed original deletion.
                        log.warn("Thumbnail cleanup required; objectKey={}", key(original));
                    }
                }
            }
        });
    }

    private Object lock(String original) {
        return locks[Math.floorMod(original.hashCode(), locks.length)];
    }

    public static byte[] render(byte[] source) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new IOException("Unsupported image decoder");
            ImageReader reader = readers.next();
            BufferedImage decoded;
            int width, height;
            try {
                reader.setInput(input);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                var params = reader.getDefaultReadParam();
                // Decode near the target size, avoiding a full-resolution raster for large originals.
                int sample = Math.max(1, Math.max(width, height) / MAX_EDGE);
                params.setSourceSubsampling(sample, sample, 0, 0);
                decoded = reader.read(0, params);
            } finally {
                reader.dispose();
            }
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, w, h);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(decoded, 0, 0, w, h, null);
            } finally {
                graphics.dispose();
                decoded.flush();
            }
            BufferedImage result = orient(scaled, jpegOrientation(source));
            var bytes = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (var output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                var params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(QUALITY);
                writer.write(null, new IIOImage(result, null, null), params);
            } finally {
                writer.dispose();
                result.flush();
                scaled.flush();
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new ImageStorageException("모니터링 썸네일을 생성할 수 없습니다.", e);
        }
    }

    // JPEG EXIF orientation is baked into pixels because JPEG output drops original metadata.
    private static int jpegOrientation(byte[] data) {
        if (data.length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216)
            return 1;
        try {
            for (int p = 2; p + 4 <= data.length;) {
                if ((data[p++] & 255) != 255)
                    return 1;
                int marker = data[p++] & 255;
                if (marker == 218 || marker == 217)
                    return 1;
                int length = ((data[p] & 255) << 8) | (data[p + 1] & 255);
                if (length < 2 || p + length > data.length)
                    return 1;
                if (marker == 225 && length >= 16 && data[p + 2] == 'E' && data[p + 3] == 'x'
                        && data[p + 4] == 'i' && data[p + 5] == 'f' && data[p + 6] == 0 && data[p + 7] == 0) {
                    var tiff = java.nio.ByteBuffer.wrap(data, p + 8, length - 8).slice();
                    short order = tiff.getShort(0);
                    if (order != 0x4949 && order != 0x4d4d)
                        return 1;
                    tiff.order(order == 0x4949 ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
                    if (tiff.getShort(2) != 42)
                        return 1;
                    int offset = tiff.getInt(4);
                    int count = Short.toUnsignedInt(tiff.getShort(offset));
                    for (int i = 0; i < count; i++) {
                        int entry = offset + 2 + i * 12;
                        if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x112
                                && tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1)
                            return Short.toUnsignedInt(tiff.getShort(entry + 8));
                    }
                }
                p += length;
            }
        } catch (IndexOutOfBoundsException e) {
            /* Malformed optional EXIF: use unrotated pixels. */
        }
        return 1;
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8)
            return source;
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage result = new BufferedImage(orientation >= 5 ? h : w,
                orientation >= 5 ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int dx = switch (orientation) {
                    case 2, 3 -> w - 1 - x;
                    case 5, 8 -> y;
                    case 6, 7 -> h - 1 - y;
                    default -> x;
                };
                int dy = switch (orientation) {
                    case 3, 4 -> h - 1 - y;
                    case 5, 6 -> x;
                    case 7, 8 -> w - 1 - x;
                    default -> y;
                };
                result.setRGB(dx, dy, source.getRGB(x, y));
            }
        return result;
    }
}
