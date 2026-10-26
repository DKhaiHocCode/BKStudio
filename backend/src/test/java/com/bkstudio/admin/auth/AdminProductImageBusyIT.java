package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.catalog.image.ImageService;
import com.bkstudio.catalog.image.StoredImage;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The 2-at-a-time image-processing limit, configured through app.admin.upload (2 permits, 200 ms wait).
 * ImageService is mocked: no storage is needed to hold a "processing" slot.
 */
@TestPropertySource(properties = {"app.admin.upload.max-concurrent=2", "app.admin.upload.max-wait=200ms"})
class AdminProductImageBusyIT extends AdminProductTestBase {

    @MockitoBean
    ImageService imageService;

    private static StoredImage fake() {
        return new StoredImage("products/" + UUID.randomUUID(), 100, 80);
    }

    /** Parks two uploads inside store(), shows the third gets 429 quickly, then lets the two finish with 201. */
    private void twoFitAndTheThirdIsRejected(long pid) throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
                    entered.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return fake();
                })
                .when(imageService)
                .store(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> a = pool.submit(() ->
                    upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), null).andReturn());
            Future<MvcResult> b = pool.submit(() ->
                    upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), null).andReturn());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); // both permits are held

            long start = System.nanoTime();
            expectProblem(upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), null), 429, "RATE_LIMITED")
                    .andExpect(header().string("Retry-After", "1")); // max(1, configured wait in seconds)
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(150)).isLessThan(Duration.ofSeconds(3));

            release.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(b.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void thirdUploadWhileTwoAreProcessing_waitsThenGets429WithRetryAfter() throws Exception {
        long pid = createProduct("Busy " + token());

        twoFitAndTheThirdIsRejected(pid);

        assertThat(count("SELECT count(*) FROM product_image WHERE product_id = ?", pid))
                .isEqualTo(2);
    }

    @Test
    void failedUploads_alwaysGiveTheirPermitBack() throws Exception {
        long pid = createProduct("Permits " + token());
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "Not an image."))
                .when(imageService)
                .store(any());
        for (int i = 0; i < 5; i++) {
            upload(pid, "bad.jpg", "image/jpeg", jpeg(100, 80), null).andExpect(status().isBadRequest());
        }

        // a leaked permit would keep the second parked upload out of store(), failing the latch inside
        twoFitAndTheThirdIsRejected(pid);
    }
}
