package com.bkstudio.catalog.image;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

/**
 * Pure image pipeline: validate, fully decode, apply EXIF orientation, resize, re-encode as lossy WebP.
 * Input is JPEG or PNG only: untrusted WebP input would be decoded by native libwebp (RCE risk); WebP is output only.
 * Input bytes are never copied to the output, so EXIF/ICC/trailing data cannot survive.
 *
 * <p>Size limits: 10 MB upload, 8000 px per side and {@link #MAX_PIXELS} total, all checked from the header before
 * decoding. The total cap exists for heap: a decoded ARGB image costs 4 bytes/pixel (40 MP = 160 MB), the
 * source stays alive while 3 renditions are built, and several uploads may run concurrently.
 *
 * <p>Any decoder warning fails the upload: ImageIO reports truncated/corrupt data as a warning and returns a
 * partly grey image instead of throwing.
 */
@Component
class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    static {
        // Untrusted uploads must never spill to java.io.tmpdir; we always read from memory.
        ImageIO.setUseCache(false);
    }

    static final List<Integer> WIDTHS = List.of(400, 1000, 2000);
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_DIMENSION = 8000;
    static final long MAX_PIXELS = 40_000_000L;
    private static final float QUALITY = 0.8f;
    private static final String JPEG_FORMAT = "javax_imageio_jpeg_image_1.0";

    ProcessedImage process(InputStream in) {
        byte[] bytes = readBounded(in);
        boolean jpeg = requireJpegOrPng(bytes);
        ImageReader reader = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw invalid();
            }
            reader = readers.next();
            // Defence in depth: the reader picked by ImageIO must be the one the magic bytes promise.
            if (!reader.getFormatName().equalsIgnoreCase(jpeg ? "jpeg" : "png")) {
                throw invalid();
            }
            // Metadata only for JPEG (EXIF orientation). PNG zTXt/iCCP are deflated and can expand without bound.
            reader.setInput(iis, true, !jpeg);
            // Header-only size check before allocating pixels (decompression bomb guard).
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION || (long) w * h > MAX_PIXELS) {
                throw invalid();
            }
            int orientation = jpeg ? exifOrientation(reader) : 1;
            boolean[] warned = {false};
            IIOReadWarningListener onWarning = (source, warning) -> warned[0] = true;
            reader.addIIOReadWarningListener(onWarning);
            BufferedImage src = reader.read(0);
            if (src == null || warned[0]) {
                throw invalid();
            }
            return encodeAll(src, orientation);
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("Image rejected: {}: {}", e.getClass().getName(), e.getMessage());
            throw invalid();
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private static ProcessedImage encodeAll(BufferedImage src, int orientation) throws IOException {
        boolean swap = orientation >= 5;
        int sw = swap ? src.getHeight() : src.getWidth();
        int sh = swap ? src.getWidth() : src.getHeight();
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int lastW = 0;
        int lastH = 0;
        for (int target : WIDTHS) {
            int tw = Math.min(target, sw); // never upscale
            int th = Math.max(1, Math.round((float) sh * tw / sw));
            BufferedImage scaled = new BufferedImage(tw, th, type);
            var g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                AffineTransform at = AffineTransform.getScaleInstance((double) tw / sw, (double) th / sh);
                at.concatenate(orientationTransform(orientation, src.getWidth(), src.getHeight()));
                g.drawImage(src, at, null);
            } finally {
                g.dispose();
            }
            out.put(target, encodeWebp(scaled));
            lastW = tw;
            lastH = th;
        }
        return new ProcessedImage(out, lastW, lastH);
    }

    private static byte[] encodeWebp(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
        try {
            WebPWriteParam param = new WebPWriteParam(writer.getLocale());
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(param.getCompressionTypes()[WebPWriteParam.LOSSY_COMPRESSION]);
            param.setCompressionQuality(QUALITY);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param); // null metadata: nothing carried over
            }
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Reads at most MAX_BYTES + 1 so an oversize upload is rejected without buffering it all. */
    private static byte[] readBounded(InputStream in) {
        try {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw invalid();
            }
            return bytes;
        } catch (IOException e) {
            throw invalid();
        }
    }

    /** Returns true for JPEG, false for PNG; anything else (incl. WebP, see class javadoc) is rejected. */
    private static boolean requireJpegOrPng(byte[] b) {
        boolean jpeg = b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean png = b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 'P'
                && b[2] == 'N'
                && b[3] == 'G'
                && b[4] == 0x0D
                && b[5] == 0x0A
                && b[6] == 0x1A
                && b[7] == 0x0A;
        if (!(jpeg || png)) {
            throw invalid();
        }
        return jpeg;
    }

    /** EXIF Orientation (1-8) from a JPEG APP1 segment; 1 for anything else or on any parse problem. */
    private static int exifOrientation(ImageReader reader) {
        try {
            IIOMetadata md = reader.getImageMetadata(0);
            if (md == null || !JPEG_FORMAT.equals(md.getNativeMetadataFormatName())) {
                return 1;
            }
            Node root = md.getAsTree(JPEG_FORMAT);
            Node seq = ((IIOMetadataNode) root)
                    .getElementsByTagName("markerSequence")
                    .item(0);
            for (Node n = seq == null ? null : seq.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof IIOMetadataNode node
                        && "unknown".equals(node.getNodeName())
                        && "225".equals(node.getAttribute("MarkerTag"))
                        && node.getUserObject() instanceof byte[] data) {
                    int o = parseOrientation(data);
                    if (o > 0) {
                        return o;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable metadata just means no rotation
        }
        return 1;
    }

    private static int parseOrientation(byte[] app1) {
        // "Exif\0\0" + TIFF header (byte order, 0x002A, IFD0 offset) + IFD0 entries
        if (app1.length < 14 || app1[0] != 'E' || app1[1] != 'x' || app1[2] != 'i' || app1[3] != 'f') {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(app1, 6, app1.length - 6).slice();
        buf.order(buf.get(0) == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int ifd = buf.getInt(4);
        if (ifd < 8 || ifd + 2 > buf.limit()) {
            return 0;
        }
        int count = buf.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > buf.limit()) {
                return 0;
            }
            if ((buf.getShort(e) & 0xFFFF) == 0x0112) {
                int v = buf.getShort(e + 8) & 0xFFFF;
                return v >= 1 && v <= 8 ? v : 0;
            }
        }
        return 0;
    }

    /** Maps source pixel coordinates to the upright orientation (w, h are the source dimensions). */
    private static AffineTransform orientationTransform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported or invalid image");
    }
}
