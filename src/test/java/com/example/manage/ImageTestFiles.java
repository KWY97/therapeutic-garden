package com.example.manage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Small decodable upload fixtures plus a real tracked WebP asset. */
public final class ImageTestFiles {
    private static final byte[] PNG = image("png");
    private static final byte[] JPEG = image("jpeg");
    private static final byte[] WEBP = webpAsset();

    private ImageTestFiles() {}

    public static byte[] png() { return PNG.clone(); }
    public static byte[] jpeg() { return JPEG.clone(); }
    public static byte[] webp() { return WEBP.clone(); }

    private static byte[] image(String format) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new IllegalStateException("Missing image writer: " + format);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create image fixture", exception);
        }
    }

    private static byte[] webpAsset() {
        try (var input = ImageTestFiles.class.getResourceAsStream(
                "/static/images/landing/optimized/effect-hs1.webp")) {
            if (input == null) throw new IllegalStateException("Missing WebP fixture");
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read WebP fixture", exception);
        }
    }
}
