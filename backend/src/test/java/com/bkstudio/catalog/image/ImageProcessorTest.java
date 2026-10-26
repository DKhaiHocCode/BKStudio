package com.bkstudio.catalog.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    private ProcessedImage process(byte[] bytes) {
        return processor.process(new ByteArrayInputStream(bytes));
    }

    private void assertRejected(byte[] bytes) {
        assertThatThrownBy(() -> process(bytes))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private void assertRenditions(ProcessedImage p, int srcW, int srcH) {
        assertThat(p.renditions().keySet()).containsExactlyElementsOf(List.of(400, 1000, 2000));
        p.renditions().forEach((target, bytes) -> {
            assertThat(TestImages.isWebp(bytes)).isTrue();
            BufferedImage img = TestImages.decode(bytes);
            int w = Math.min(target, srcW);
            assertThat(img.getWidth()).isEqualTo(w);
            assertThat((double) img.getHeight()).isCloseTo((double) srcH * w / srcW, within(1.0));
        });
    }

    @Test
    void validJpeg_producesThreeWebpRenditionsKeepingRatio() {
        assertRenditions(process(TestImages.jpeg(3000, 2000)), 3000, 2000);
    }

    @Test
    void validPng_producesThreeWebpRenditions() {
        byte[] png = TestImages.png(TestImages.redBlue(1500, 1000, BufferedImage.TYPE_INT_RGB));
        assertRenditions(process(png), 1500, 1000);
    }

    @Test
    void validWebpInput_isRejected() {
        assertRejected(TestImages.webp(TestImages.redBlue(1200, 800, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void pngWithAlpha_keepsTransparency() {
        BufferedImage src = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        for (int x = 300; x < 600; x++) {
            for (int y = 0; y < 400; y++) {
                src.setRGB(x, y, 0xFFFF0000);
            }
        }
        ProcessedImage p = process(TestImages.png(src));
        BufferedImage out = TestImages.decode(p.renditions().get(400));
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        assertThat(out.getRGB(50, 200) >>> 24).isLessThan(255);
        assertThat(out.getRGB(350, 200) >>> 24).isEqualTo(255);
    }

    @Test
    void smallSource_isNeverUpscaled() {
        ProcessedImage p = process(TestImages.jpeg(300, 200));
        assertRenditions(p, 300, 200);
        assertThat(p.width()).isEqualTo(300);
        assertThat(p.height()).isEqualTo(200);
    }

    @Test
    void phpScriptRenamedToJpg_isRejected() {
        assertRejected("<?php system($_GET[0]); ?>".repeat(50).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void jpegMagicFollowedByGarbage_isRejected() {
        byte[] b = new byte[2000];
        Arrays.fill(b, (byte) 'A');
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        assertRejected(b);
    }

    @Test
    void emptyStream_isRejected() {
        assertRejected(new byte[0]);
    }

    @Test
    void largerThanTenMegabytes_isRejected() {
        byte[] b = new byte[ImageProcessor.MAX_BYTES + 1];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        assertRejected(b);
    }

    @Test
    void widthOver8000_isRejected() {
        assertRejected(TestImages.png(new BufferedImage(8001, 100, BufferedImage.TYPE_BYTE_GRAY)));
    }

    @Test
    void heightOver8000_isRejected() {
        assertRejected(TestImages.png(new BufferedImage(100, 8001, BufferedImage.TYPE_BYTE_GRAY)));
    }

    @Test
    void widthExactly8000_isAccepted() {
        ProcessedImage p = process(TestImages.png(new BufferedImage(8000, 10, BufferedImage.TYPE_INT_RGB)));
        assertThat(TestImages.decode(p.renditions().get(2000)).getWidth()).isEqualTo(2000);
    }

    @Test
    void over40Megapixels_isRejectedBeforeDecoding() {
        // 7000x6000 = 42 MP; 1-bit image keeps the test cheap (~5 MB).
        assertRejected(TestImages.png(new BufferedImage(7000, 6000, BufferedImage.TYPE_BYTE_BINARY)));
    }

    @Test
    void exactly40Megapixels_isAccepted() {
        ProcessedImage p = process(TestImages.png(new BufferedImage(8000, 5000, BufferedImage.TYPE_BYTE_BINARY)));
        assertThat(p.renditions()).hasSize(3);
    }

    @Test
    void truncatedJpeg_isRejected() {
        byte[] full = TestImages.jpeg(800, 600);
        assertRejected(Arrays.copyOf(full, full.length / 2));
    }

    @Test
    void exifGps_isStrippedFromEveryRendition() {
        byte[] in = TestImages.jpegWithExif(TestImages.redBlue(3000, 2000, BufferedImage.TYPE_INT_RGB), 1);
        assertThat(TestImages.contains(in, "Exif")).isTrue();
        assertThat(TestImages.contains(in, TestImages.GPS_MARKER)).isTrue();

        ProcessedImage p = process(in);

        assertThat(p.renditions()).hasSize(3);
        p.renditions().values().forEach(out -> {
            assertThat(TestImages.contains(out, "Exif")).isFalse();
            assertThat(TestImages.contains(out, "EXIF")).isFalse();
            assertThat(TestImages.contains(out, TestImages.GPS_MARKER)).isFalse();
        });
    }

    /** Source is 600x300, left half red. Checks output shape and which side the red half ends up on. */
    private void assertOrientation(int orientation, boolean portrait, String redAt) {
        byte[] in = TestImages.jpegWithExif(TestImages.redBlue(600, 300, BufferedImage.TYPE_INT_RGB), orientation);
        BufferedImage out = TestImages.decode(process(in).renditions().get(2000));
        assertThat(out.getWidth()).isEqualTo(portrait ? 300 : 600);
        assertThat(out.getHeight()).isEqualTo(portrait ? 600 : 300);
        int w = out.getWidth();
        int h = out.getHeight();
        int[][] pts = {{w / 4, h / 2}, {3 * w / 4, h / 2}, {w / 2, h / 4}, {w / 2, 3 * h / 4}};
        String[] names = {"left", "right", "top", "bottom"};
        for (int i = 0; i < 4; i++) {
            int rgb = out.getRGB(pts[i][0], pts[i][1]);
            boolean red = ((rgb >> 16) & 0xFF) > 150 && (rgb & 0xFF) < 100;
            boolean blue = (rgb & 0xFF) > 150 && ((rgb >> 16) & 0xFF) < 100;
            // Only the two points on the red/blue axis are meaningful; the cross-axis points sit on the boundary.
            boolean onAxis = portrait ? i >= 2 : i < 2;
            if (!onAxis) {
                continue;
            }
            assertThat(red || blue)
                    .as("%s pixel %06x is red or blue", names[i], rgb & 0xFFFFFF)
                    .isTrue();
            assertThat(red).as("orientation %d: %s red?", orientation, names[i]).isEqualTo(names[i].equals(redAt));
        }
    }

    @Test
    void exifOrientation6_rotatesClockwise_redHalfOnTop() {
        assertOrientation(6, true, "top");
    }

    @Test
    void exifOrientation3_rotates180_redHalfOnRight() {
        assertOrientation(3, false, "right");
    }

    @Test
    void exifOrientation8_rotatesCounterClockwise_redHalfOnBottom() {
        assertOrientation(8, true, "bottom");
    }

    /** Source has a red square top-left; returns which output corner (TL/TR/BL/BR) holds it. */
    private String markerCorner(byte[] in) {
        BufferedImage out = TestImages.decode(process(in).renditions().get(2000));
        int w = out.getWidth();
        int h = out.getHeight();
        int[][] pts = {{20, 20}, {w - 20, 20}, {20, h - 20}, {w - 20, h - 20}};
        String[] names = {"TL", "TR", "BL", "BR"};
        String found = "";
        for (int i = 0; i < 4; i++) {
            int rgb = out.getRGB(pts[i][0], pts[i][1]);
            if (((rgb >> 16) & 0xFF) > 150 && (rgb & 0xFF) < 100) {
                found += names[i];
            }
        }
        return found;
    }

    @Test
    void exifOrientations5to8_moveTopLeftMarkerToExpectedCorner() {
        BufferedImage src = TestImages.cornerMarked(600, 300);
        assertThat(markerCorner(TestImages.jpegWithExif(src, 5))).isEqualTo("TL");
        assertThat(markerCorner(TestImages.jpegWithExif(src, 6))).isEqualTo("TR");
        assertThat(markerCorner(TestImages.jpegWithExif(src, 7))).isEqualTo("BR");
        assertThat(markerCorner(TestImages.jpegWithExif(src, 8))).isEqualTo("BL");
    }

    @Test
    void exifLittleEndian_orientation6_isHonoured() {
        byte[] in = TestImages.jpegWithExif(TestImages.cornerMarked(600, 300), 6, true);
        assertThat(markerCorner(in)).isEqualTo("TR");
    }

    @Test
    void pngWithHugeZtxtChunk_doesNotInflateMetadata() {
        byte[] bomb = TestImages.pngWithZtxtBomb(300L * 1024 * 1024);
        assertThat(bomb.length).isLessThan(ImageProcessor.MAX_BYTES);

        ProcessedImage p = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> process(bomb));

        p.renditions()
                .values()
                .forEach(out -> assertThat(TestImages.contains(out, "Comment")).isFalse());
    }

    @Test
    void exifOrientation1_leavesImageUnchanged() {
        assertOrientation(1, false, "left");
    }
}
