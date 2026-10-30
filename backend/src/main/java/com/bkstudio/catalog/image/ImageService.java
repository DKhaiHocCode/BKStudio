package com.bkstudio.catalog.image;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Validates, re-encodes and stores an uploaded product image. Callers persist the returned key themselves. */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    private static final Pattern KEY =
            Pattern.compile("^(products|progress)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ImageProcessor processor;
    private final ImageStorage storage;

    ImageService(ImageProcessor processor, ImageStorage storage) {
        this.processor = processor;
        this.storage = storage;
    }

    /** The caller owns and closes {@code in}. */
    public StoredImage store(InputStream in) {
        return store("products", in);
    }

    /** Same as {@link #store(InputStream)} but under the {@code progress/} key prefix (order progress photos). */
    public StoredImage storeProgress(InputStream in) {
        return store("progress", in);
    }

    private StoredImage store(String prefix, InputStream in) {
        ProcessedImage image = processor.process(in);
        String storageKey = prefix + "/" + UUID.randomUUID();
        try {
            for (Map.Entry<Integer, byte[]> e : image.renditions().entrySet()) {
                storage.put(storageKey, e.getKey(), e.getValue());
            }
        } catch (RuntimeException ex) {
            log.error("Image upload failed for {}", storageKey, ex);
            try {
                // Unconditional: a put may have succeeded server-side yet failed client-side. Delete is idempotent.
                storage.delete(storageKey, ImageProcessor.WIDTHS);
            } catch (RuntimeException cleanup) {
                log.warn("Cleanup after failed upload also failed for {}", storageKey);
            }
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Image storage failed");
        }
        return new StoredImage(storageKey, image.width(), image.height());
    }

    public void delete(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        storage.delete(storageKey, ImageProcessor.WIDTHS);
    }
}
