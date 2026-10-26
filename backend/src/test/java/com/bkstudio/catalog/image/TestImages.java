package com.bkstudio.catalog.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** In-memory sample image builders shared by the image pipeline tests. */
final class TestImages {

    static final String GPS_MARKER = "GPSMARK";

    private TestImages() {}

    /** Left half red, right half blue. */
    static BufferedImage redBlue(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, bos)) {
                throw new IllegalStateException("no writer for " + format);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] jpeg(int w, int h) {
        return encode(redBlue(w, h, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    static byte[] png(BufferedImage img) {
        return encode(img, "png");
    }

    static byte[] webp(BufferedImage img) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), null);
            }
            writer.dispose();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JPEG with an EXIF APP1 (Orientation + GPS IFD with a lat tag and a marker string) right after SOI. */
    static byte[] jpegWithExif(BufferedImage img, int orientation) {
        return jpegWithExif(img, orientation, false);
    }

    static byte[] jpegWithExif(BufferedImage img, int orientation, boolean littleEndian) {
        byte[] jpeg = encode(img, "jpg");
        byte[] app1 = exifApp1(orientation, littleEndian);
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + app1.length);
        // ImageIO's reader insists JFIF APP0 directly follows SOI, so insert after it.
        int at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        out.put(jpeg, 0, at).put(app1).put(jpeg, at, jpeg.length - at);
        return out.array();
    }

    private static byte[] exifApp1(int orientation, boolean littleEndian) {
        ByteBuffer t = ByteBuffer.allocate(112); // TIFF, offsets relative to its start
        if (littleEndian) {
            t.order(ByteOrder.LITTLE_ENDIAN);
            t.put(new byte[] {'I', 'I', 42, 0});
        } else {
            t.put(new byte[] {'M', 'M', 0, 42});
        }
        t.putInt(8);
        // IFD0 @8: Orientation, GPS IFD pointer
        t.putShort((short) 2);
        t.putShort((short) 0x0112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0);
        t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(38);
        t.putInt(0);
        // GPS IFD @38: LatitudeRef, Latitude (3 rationals @80), ProcessingMethod (marker @104)
        t.putShort((short) 3);
        t.putShort((short) 1).putShort((short) 2).putInt(2).put(new byte[] {'N', 0, 0, 0});
        t.putShort((short) 2).putShort((short) 5).putInt(3).putInt(80);
        t.putShort((short) 0x1B).putShort((short) 7).putInt(8).putInt(104);
        t.putInt(0);
        for (int[] r : new int[][] {{1, 1}, {2, 1}, {3, 1}}) {
            t.putInt(r[0]).putInt(r[1]);
        }
        t.put((GPS_MARKER + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuffer seg = ByteBuffer.allocate(2 + 2 + 6 + t.capacity());
        seg.put(new byte[] {(byte) 0xFF, (byte) 0xE1}).putShort((short) (2 + 6 + t.capacity()));
        seg.put(new byte[] {'E', 'x', 'i', 'f', 0, 0}).put(t.array());
        return seg.array();
    }

    /** White image with a 100x100 red square in the top-left corner (asymmetric, so flips are detectable). */
    static BufferedImage cornerMarked(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        return img;
    }

    /** Valid small PNG with a zTXt chunk (inserted before IDAT) that inflates to {@code inflatedBytes} of zeros. */
    static byte[] pngWithZtxtBomb(long inflatedBytes) {
        byte[] png = png(redBlue(200, 100, BufferedImage.TYPE_INT_RGB));
        int idat = -1;
        for (int i = 8; i + 4 < png.length && idat < 0; i++) {
            if (png[i] == 'I' && png[i + 1] == 'D' && png[i + 2] == 'A' && png[i + 3] == 'T') {
                idat = i - 4; // start of the chunk's length field
            }
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes("Comment".getBytes(StandardCharsets.ISO_8859_1));
        data.write(0); // keyword terminator
        data.write(0); // compression method: deflate
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
        byte[] zeros = new byte[1 << 20];
        byte[] buf = new byte[1 << 16];
        for (long left = inflatedBytes; left > 0; left -= zeros.length) {
            deflater.setInput(zeros, 0, (int) Math.min(zeros.length, left));
            while (!deflater.needsInput()) {
                data.write(buf, 0, deflater.deflate(buf));
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            data.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        byte[] chunkData = data.toByteArray();
        byte[] type = "zTXt".getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(chunkData);
        ByteBuffer chunk = ByteBuffer.allocate(12 + chunkData.length);
        chunk.putInt(chunkData.length).put(type).put(chunkData).putInt((int) crc.getValue());
        ByteBuffer out = ByteBuffer.allocate(png.length + chunk.capacity());
        out.put(png, 0, idat).put(chunk.array()).put(png, idat, png.length - idat);
        return out.array();
    }

    static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean isWebp(byte[] b) {
        return b.length > 12
                && new String(b, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP");
    }
}
