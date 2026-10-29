package com.example.manage.storage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

@Component
public class ImageFilePolicy {
    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    public static final int MAX_FILES = 10;
    private static final long MAX_PIXELS = 25_000_000;
    private static final int MAX_DIMENSION = 10_000;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    public void validateBatch(List<MultipartFile> uploads) {
        if (uploads == null || uploads.isEmpty()) throw new IllegalArgumentException("이미지를 선택해 주세요.");
        if (uploads.size() > MAX_FILES) throw new IllegalArgumentException("한 번에 최대 10장까지 등록할 수 있습니다.");
        uploads.forEach(this::validate);
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("빈 파일은 등록할 수 없습니다.");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("이미지는 파일당 10MB 이하여야 합니다.");
        if (file.getContentType() == null || !EXTENSIONS.containsKey(file.getContentType()))
            throw new IllegalArgumentException("JPEG, PNG, WebP 이미지만 등록할 수 있습니다.");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_SIZE + 1);
            if (bytes.length > MAX_FILE_SIZE) throw new IllegalArgumentException("이미지는 파일당 10MB 이하여야 합니다.");
            switch (file.getContentType()) {
                case "image/jpeg" -> {
                    if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8
                            || (bytes[bytes.length - 2] & 0xff) != 0xff || (bytes[bytes.length - 1] & 0xff) != 0xd9)
                        throw invalidImage();
                    decode(bytes, "JPEG");
                }
                case "image/png" -> {
                    validatePngStructure(bytes);
                    decode(bytes, "PNG");
                }
                case "image/webp" -> validateWebpStructure(bytes);
                default -> throw invalidImage();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("이미지 파일을 읽을 수 없습니다.", exception);
        }
    }

    private void decode(byte[] bytes, String expectedFormat) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) throw invalidImage();
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                if (reader.read(0) == null) throw invalidImage();
            } catch (IOException | IndexOutOfBoundsException exception) {
                throw invalidImage();
            } finally {
                reader.dispose();
            }
        }
    }

    private void validatePngStructure(byte[] bytes) {
        if (bytes.length < 45 || !Arrays.equals(Arrays.copyOf(bytes, 8), PNG_SIGNATURE)) throw invalidImage();
        int position = 8;
        boolean imageData = false;
        boolean ended = false;
        int chunks = 0;
        while (position < bytes.length && ++chunks <= 10_000) {
            if (bytes.length - position < 12) throw invalidImage();
            long length = uint32BigEndian(bytes, position);
            long end = (long) position + 12 + length;
            if (length > Integer.MAX_VALUE || end > bytes.length) throw invalidImage();
            String type = fourCc(bytes, position + 4);
            CRC32 crc = new CRC32();
            crc.update(bytes, position + 4, (int) length + 4);
            if (crc.getValue() != uint32BigEndian(bytes, (int) end - 4)) throw invalidImage();
            if (chunks == 1) {
                if (!"IHDR".equals(type) || length != 13) throw invalidImage();
                checkDimensions(uint32BigEndian(bytes, position + 8), uint32BigEndian(bytes, position + 12));
            }
            if ("IDAT".equals(type)) imageData = true;
            if ("IEND".equals(type)) {
                if (length != 0 || !imageData || end != bytes.length) throw invalidImage();
                ended = true;
            }
            position = (int) end;
            if (ended) break;
        }
        if (!ended || position != bytes.length) throw invalidImage();
    }

    // The JDK does not ship a WebP ImageIO reader. Validate RIFF framing and image frame headers
    // without rejecting valid WebP files merely because no optional decoder is installed.
    private void validateWebpStructure(byte[] bytes) {
        if (bytes.length < 30 || !"RIFF".equals(fourCc(bytes, 0))
                || !"WEBP".equals(fourCc(bytes, 8)) || uint32LittleEndian(bytes, 4) + 8 != bytes.length)
            throw invalidImage();
        int position = 12;
        int chunks = 0;
        boolean hasFrame = false;
        String first = null;
        while (position < bytes.length && ++chunks <= 10_000) {
            if (bytes.length - position < 8) throw invalidImage();
            String type = fourCc(bytes, position);
            long length = uint32LittleEndian(bytes, position + 4);
            long end = (long) position + 8 + length;
            if (length > Integer.MAX_VALUE || end + (length & 1) > bytes.length) throw invalidImage();
            if (first == null) {
                first = type;
                if (!"VP8 ".equals(type) && !"VP8L".equals(type) && !"VP8X".equals(type))
                    throw invalidImage();
            }
            int data = position + 8;
            if ("VP8X".equals(type)) {
                if (length != 10 || chunks != 1) throw invalidImage();
                checkDimensions(uint24LittleEndian(bytes, data + 4) + 1L,
                        uint24LittleEndian(bytes, data + 7) + 1L);
            } else if ("VP8 ".equals(type) || "VP8L".equals(type)) {
                validateWebpFrame(bytes, data, (int) length, type);
                hasFrame = true;
            } else if ("ANMF".equals(type)) {
                if (length < 24) throw invalidImage();
                checkDimensions(uint24LittleEndian(bytes, data + 6) + 1L,
                        uint24LittleEndian(bytes, data + 9) + 1L);
                int frameChunk = data + 16;
                while (frameChunk < end) {
                    if (end - frameChunk < 8) throw invalidImage();
                    String frameType = fourCc(bytes, frameChunk);
                    long frameLength = uint32LittleEndian(bytes, frameChunk + 4);
                    long frameEnd = (long) frameChunk + 8 + frameLength + (frameLength & 1);
                    if (frameLength > Integer.MAX_VALUE || frameEnd > end) throw invalidImage();
                    if ("VP8 ".equals(frameType) || "VP8L".equals(frameType)) {
                        validateWebpFrame(bytes, frameChunk + 8, (int) frameLength, frameType);
                        hasFrame = true;
                    }
                    frameChunk = (int) frameEnd;
                }
            }
            position = (int) (end + (length & 1));
        }
        if (!hasFrame || position != bytes.length) throw invalidImage();
    }

    private void validateWebpFrame(byte[] bytes, int data, int length, String type) {
        if ("VP8 ".equals(type)) {
            if (length < 10 || (bytes[data] & 1) != 0 || (bytes[data + 3] & 0xff) != 0x9d
                    || (bytes[data + 4] & 0xff) != 0x01 || (bytes[data + 5] & 0xff) != 0x2a)
                throw invalidImage();
            int frameTag = (bytes[data] & 0xff) | ((bytes[data + 1] & 0xff) << 8)
                    | ((bytes[data + 2] & 0xff) << 16);
            int firstPartition = frameTag >>> 5;
            if (firstPartition == 0 || firstPartition > length - 10) throw invalidImage();
            checkDimensions(((bytes[data + 7] & 0x3f) << 8) | (bytes[data + 6] & 0xff),
                    ((bytes[data + 9] & 0x3f) << 8) | (bytes[data + 8] & 0xff));
        } else {
            if (length <= 5 || (bytes[data] & 0xff) != 0x2f) throw invalidImage();
            long packed = uint32LittleEndian(bytes, data + 1);
            checkDimensions((packed & 0x3fff) + 1, ((packed >>> 14) & 0x3fff) + 1);
        }
    }

    private void checkDimensions(long width, long height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || width * height > MAX_PIXELS)
            throw new IllegalArgumentException("이미지 해상도가 너무 큽니다. 최대 2,500만 픽셀까지 등록할 수 있습니다.");
    }

    private long uint32BigEndian(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xffL);
    }

    private long uint32LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((long) (bytes[offset + 1] & 0xff) << 8)
                | ((long) (bytes[offset + 2] & 0xff) << 16) | ((long) (bytes[offset + 3] & 0xff) << 24);
    }

    private long uint24LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((long) (bytes[offset + 1] & 0xff) << 8)
                | ((long) (bytes[offset + 2] & 0xff) << 16);
    }

    private String fourCc(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private IllegalArgumentException invalidImage() {
        return new IllegalArgumentException("이미지 파일 내용이 올바르지 않거나 선택한 형식과 일치하지 않습니다.");
    }

    public String createKey(boolean site, Long parentId, String contentType) {
        if (parentId == null || parentId <= 0 || !EXTENSIONS.containsKey(contentType))
            throw new IllegalArgumentException("올바르지 않은 이미지 정보입니다.");
        return (site ? "sites/" : "healing-spots/") + parentId + "/" + UUID.randomUUID()
                + "." + EXTENSIONS.get(contentType);
    }

    public String displayName(String original) {
        if (original == null) return "이미지";
        String clean = original.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return clean.substring(0, Math.min(clean.length(), 255));
    }

    public static void validateKey(String key) {
        if (key == null || !key.matches("(?:sites|healing-spots)/[1-9][0-9]*/[a-f0-9-]{36}\\.(?:jpg|png|webp)(?:\\.monitoring-v1-560-q82\\.jpg)?"))
            throw new ImageStorageException("올바르지 않은 이미지 저장 경로입니다.");
    }
}
