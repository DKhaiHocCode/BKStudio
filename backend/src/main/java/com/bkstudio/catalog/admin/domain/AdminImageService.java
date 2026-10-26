package com.bkstudio.catalog.admin.domain;

import com.bkstudio.catalog.admin.api.AdminProductDtos.Image;
import com.bkstudio.catalog.image.ImageService;
import com.bkstudio.catalog.image.StoredImage;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates image upload and delete around the storage calls. Deliberately NOT transactional: image processing
 * and storage I/O must not hold a DB connection; the DB work is in short {@link AdminProductService} transactions.
 */
@Service
public class AdminImageService {

    private static final Logger log = LoggerFactory.getLogger(AdminImageService.class);
    // Decoding a large image is memory hungry; limited per instance (default 2 at a time, 5 s wait).
    private final Semaphore processing;
    private final Duration wait;
    private final AdminProductService products;
    private final ImageService images;

    public AdminImageService(AdminProductService products, ImageService images, UploadProperties limits) {
        this.products = products;
        this.images = images;
        this.processing = new Semaphore(limits.maxConcurrent());
        this.wait = limits.maxWait();
    }

    public Image upload(long productId, InputStream file, String altText) {
        products.assertCanAddImage(productId);
        StoredImage stored = store(file);
        try {
            return products.addImage(productId, stored, altText);
        } catch (RuntimeException e) {
            // DB insert failed or the limit was hit by a parallel upload: do not leave files behind.
            deleteQuietly(stored.storageKey());
            throw e;
        }
    }

    public void delete(long imageId) {
        String key = products.deleteImage(imageId); // committed when this returns
        // shortcut: if this fails the files stay in the bucket as orphans; add a cleanup job when it matters.
        deleteQuietly(key);
    }

    private StoredImage store(InputStream file) {
        boolean acquired;
        try {
            acquired = processing.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try (file) {
            return images.store(file);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unreadable upload.");
        } finally {
            processing.release();
        }
    }

    private void deleteQuietly(String key) {
        try {
            images.delete(key);
        } catch (RuntimeException e) {
            log.warn(
                    "Image files not deleted (key={}, error={})",
                    key,
                    e.getClass().getSimpleName());
        }
    }

    private ApiException busy() {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many uploads in progress. Try again shortly.", wait);
    }
}
