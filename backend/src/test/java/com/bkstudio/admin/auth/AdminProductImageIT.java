package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bkstudio.catalog.admin.domain.AdminImageService;
import com.bkstudio.catalog.image.ImageService;
import com.bkstudio.catalog.image.StoredImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Image upload / delete through the admin API against a real MinIO and the real ImageService. The long processing
 * wait makes the 25-parallel-upload test deterministic: queued uploads never time out into 429.
 */
@TestPropertySource(properties = "app.admin.upload.max-wait=60s")
class AdminProductImageIT extends AdminProductTestBase {

    private static final String BUCKET = "bkstudio-admin-it";
    private static final String USER = "minioadmin";
    private static final String PASSWORD = "minioadmin-secret";

    // Own container and bucket: no coupling to ImageServiceIT's bucket-content assertions.
    static final GenericContainer<?> MINIO = new GenericContainer<>("cgr.dev/chainguard/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        MINIO.start();
    }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry r) {
        r.add("app.storage.endpoint", AdminProductImageIT::endpoint);
        r.add("app.storage.bucket", () -> BUCKET);
        r.add("app.storage.access-key", () -> USER);
        r.add("app.storage.secret-key", () -> PASSWORD);
        r.add("app.storage.region", () -> "us-east-1");
        r.add("app.storage.path-style", () -> "true");
    }

    private static S3Client s3;

    @BeforeAll
    static void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @MockitoSpyBean
    ImageService imageService;

    @AfterEach
    void emptyBucket() {
        List<ObjectIdentifier> ids = bucketKeys().stream()
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .toList();
        if (!ids.isEmpty()) {
            s3.deleteObjects(
                    r -> r.bucket(BUCKET).delete(Delete.builder().objects(ids).build()));
        }
    }

    // ---- helpers ----

    private static List<String> bucketKeys() {
        return s3.listObjectsV2(r -> r.bucket(BUCKET)).contents().stream()
                .map(S3Object::key)
                .toList();
    }

    private String storageKeyOf(ResultActions uploaded) throws Exception {
        String url = read(uploaded, "$.urls['1000']");
        return url.substring(imageBase.length() + 1, url.length() - "/1000.webp".length());
    }

    private static List<String> renditions(String key) {
        return List.of(key + "/400.webp", key + "/1000.webp", key + "/2000.webp");
    }

    private ResultActions uploadJpeg(long pid) throws Exception {
        return upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), null);
    }

    private List<Integer> parallelUploadStatuses(long pid, int n) throws Exception {
        byte[] bytes = jpeg(100, 80);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Integer> task = () -> {
                    go.await();
                    MvcResult r = mvc.perform(multipart(
                                    PRODUCTS + "/" + pid + "/images",
                                    session,
                                    csrfToken(),
                                    null,
                                    new MockMultipartFile("file", "p.jpg", "image/jpeg", bytes)))
                            .andReturn();
                    return r.getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- valid uploads ----------------

    @Test
    void uploadJpeg_returns201_storesThreeWebpObjectsUnderTheReturnedKey_andTheAltText() throws Exception {
        long pid = createProduct("Upload jpeg " + token());

        ResultActions r = upload(pid, "front.jpg", "image/jpeg", jpeg(1200, 800), "front view")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.altText").value("front view"))
                .andExpect(jsonPath("$.width").value(1200))
                .andExpect(jsonPath("$.height").value(800));
        String key = storageKeyOf(r);

        assertThat(key).matches("^products/[0-9a-f-]{36}$");
        assertThat(bucketKeys()).containsExactlyInAnyOrderElementsOf(renditions(key));
        for (String objectKey : renditions(key)) {
            var head = s3.headObject(h -> h.bucket(BUCKET).key(objectKey));
            assertThat(head.contentType()).isEqualTo("image/webp");
            byte[] bytes = s3.getObject(g -> g.bucket(BUCKET).key(objectKey), ResponseTransformer.toBytes())
                    .asByteArray();
            assertThat(new String(bytes, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("RIFF");
        }
        String u400 = read(r, "$.urls['400']");
        String u2000 = read(r, "$.urls['2000']");
        assertThat(u400).isEqualTo(imageBase + "/" + key + "/400.webp");
        assertThat(u2000).isEqualTo(imageBase + "/" + key + "/2000.webp");
        assertThat(jdbc.queryForObject("SELECT storage_key FROM product_image WHERE product_id = ?", String.class, pid))
                .isEqualTo(key);
        assertThat(jdbc.queryForObject("SELECT alt_text FROM product_image WHERE product_id = ?", String.class, pid))
                .isEqualTo("front view");
    }

    @Test
    void uploadPng_returns201_andStoresThreeObjects() throws Exception {
        long pid = createProduct("Upload png " + token());

        ResultActions r = upload(pid, "front.png", "image/png", png(300, 200), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.altText").value(""));

        assertThat(bucketKeys()).containsExactlyInAnyOrderElementsOf(renditions(storageKeyOf(r)));
    }

    @Test
    void upload_ignoresTheClientFilenameAndContentType_theBytesDecide() throws Exception {
        long pid = createProduct("Sniffing " + token());

        upload(pid, "notes.txt", "text/plain", png(120, 90), null).andExpect(status().isCreated());
    }

    @Test
    void upload_altTextOf200CharsIsAccepted_201IsRejected() throws Exception {
        long pid = createProduct("Alt limits " + token());

        upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), "a".repeat(200)).andExpect(status().isCreated());
        expectProblem(upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), "a".repeat(201)), 400, "VALIDATION_FAILED");

        assertThat(count("SELECT count(*) FROM product_image WHERE product_id = ?", pid))
                .isEqualTo(1);
        assertThat(bucketKeys()).hasSize(3);
    }

    @Test
    void upload_sortOrderIncrements_andAppendsAfterTheCurrentMaxEvenAfterADelete() throws Exception {
        long pid = createProduct("Sort order " + token());
        long first = readId(uploadJpeg(pid).andExpect(jsonPath("$.sortOrder").value(0)), "$.id");
        long second = readId(uploadJpeg(pid).andExpect(jsonPath("$.sortOrder").value(1)), "$.id");
        long third = readId(uploadJpeg(pid).andExpect(jsonPath("$.sortOrder").value(2)), "$.id");

        call(HttpMethod.DELETE, "/api/admin/images/" + second, null).andExpect(status().isNoContent());
        long fourth = readId(uploadJpeg(pid).andExpect(jsonPath("$.sortOrder").value(3)), "$.id");

        fetch(PRODUCTS + "/" + pid)
                .andExpect(jsonPath("$.images[0].id").value(first))
                .andExpect(jsonPath("$.images[1].id").value(third))
                .andExpect(jsonPath("$.images[2].id").value(fourth));
    }

    @Test
    void upload_writesOneImageAddedAuditRow() throws Exception {
        long pid = createProduct("Audit upload " + token());
        int base = auditRows();

        ResultActions r =
                upload(pid, "a.jpg", "image/jpeg", jpeg(100, 80), "alt!").andExpect(status().isCreated());
        long imageId = readId(r, "$.id");

        List<Audit> rows = audits("IMAGE", imageId);
        assertThat(rows).hasSize(1);
        Audit a = rows.get(0);
        assertThat(a.action()).isEqualTo("IMAGE_ADDED");
        assertThat(a.actor()).isEqualTo("ADMIN");
        assertThat(a.actorId()).isEqualTo(admin.id());
        assertThat(a.before()).isNull();
        assertThat(a.requestId()).isEqualTo(r.andReturn().getResponse().getHeader("X-Request-Id"));
        assertThat(om.readTree(a.after()).propertyNames())
                .containsExactlyInAnyOrder("productId", "storageKey", "altText", "sortOrder");
        assertThat(auditRows()).isEqualTo(base + 1);
    }

    // ---------------- invalid uploads ----------------

    private void assertNothingStored(long pid) {
        assertThat(count("SELECT count(*) FROM product_image WHERE product_id = ?", pid))
                .isZero();
        assertThat(bucketKeys()).isEmpty();
    }

    @Test
    void upload_nonImageBytesRenamedJpg_is400() throws Exception {
        long pid = createProduct("Fake jpg " + token());
        byte[] php = "<?php system($_GET[0]); ?>".getBytes(StandardCharsets.UTF_8);

        expectProblem(upload(pid, "evil.jpg", "image/jpeg", php, null), 400, "VALIDATION_FAILED");

        assertNothingStored(pid);
    }

    @Test
    void upload_webpInput_is400() throws Exception {
        long pid = createProduct("Webp in " + token());

        expectProblem(upload(pid, "a.webp", "image/webp", webp(100, 80), null), 400, "VALIDATION_FAILED");
        expectProblem(upload(pid, "a.jpg", "image/jpeg", webp(100, 80), null), 400, "VALIDATION_FAILED");

        assertNothingStored(pid);
    }

    @Test
    void upload_truncatedJpeg_is400() throws Exception {
        long pid = createProduct("Truncated " + token());
        byte[] whole = jpeg(800, 600);

        expectProblem(
                upload(pid, "a.jpg", "image/jpeg", java.util.Arrays.copyOf(whole, whole.length / 2), null),
                400,
                "VALIDATION_FAILED");

        assertNothingStored(pid);
    }

    @Test
    void upload_emptyFile_is400() throws Exception {
        long pid = createProduct("Empty " + token());

        expectProblem(upload(pid, "a.jpg", "image/jpeg", new byte[0], null), 400, "VALIDATION_FAILED");

        assertNothingStored(pid);
    }

    @Test
    void upload_missingFilePart_is400() throws Exception {
        long pid = createProduct("No part " + token());

        expectProblem(
                mvc.perform(multipart(PRODUCTS + "/" + pid + "/images", session, csrfToken(), "alt only")),
                400,
                "VALIDATION_FAILED");
        expectProblem(
                mvc.perform(multipart(
                        PRODUCTS + "/" + pid + "/images",
                        session,
                        csrfToken(),
                        null,
                        new MockMultipartFile("wrongName", "a.jpg", "image/jpeg", jpeg(100, 80)))),
                400,
                "VALIDATION_FAILED");

        assertNothingStored(pid);
    }

    @Test
    void upload_jsonInsteadOfMultipart_is415() throws Exception {
        long pid = createProduct("Not multipart " + token());

        mvc.perform(withCsrf(HttpMethod.POST, PRODUCTS + "/" + pid + "/images", "{}", session, nextIp()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---------------- image limit ----------------

    @Test
    void upload_21stImage_is409Conflict_andLeavesNothingNewInTheBucket() throws Exception {
        long pid = createProduct("Limit " + token());
        for (int i = 0; i < 20; i++) {
            uploadJpeg(pid).andExpect(status().isCreated());
        }
        List<String> before = bucketKeys();
        assertThat(before).hasSize(60);
        int audits = auditRows();

        expectProblem(uploadJpeg(pid), 409, "CONFLICT");

        assertThat(bucketKeys()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(count("SELECT count(*) FROM product_image WHERE product_id = ?", pid))
                .isEqualTo(20);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void parallelUploads_25ToOneProduct_neverExceed20Rows_andLeaveNoOrphanObjects() throws Exception {
        long pid = createProduct("Parallel " + token());

        List<Integer> statuses = parallelUploadStatuses(pid, 25);

        int rows = count("SELECT count(*) FROM product_image WHERE product_id = ?", pid);
        long created = statuses.stream().filter(s -> s == 201).count();
        assertThat(rows).isEqualTo(20);
        assertThat(created).isEqualTo(20);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
        assertThat(bucketKeys()).hasSize(rows * 3);
        // every stored object belongs to a row, and sort orders are unique and gapless
        List<String> rowKeys =
                jdbc.queryForList("SELECT storage_key FROM product_image WHERE product_id = ?", String.class, pid);
        assertThat(bucketKeys())
                .containsExactlyInAnyOrderElementsOf(
                        rowKeys.stream().flatMap(k -> renditions(k).stream()).toList());
        assertThat(jdbc.queryForList(
                        "SELECT sort_order FROM product_image WHERE product_id = ? ORDER BY sort_order",
                        Integer.class,
                        pid))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, rows).boxed().toList());
    }

    @Test
    void parallelUploads_racingForTheLastTwoSlots_exactlyTwoWin_andLosersLeaveNoFiles() throws Exception {
        long pid = createProduct("Last slots " + token());
        for (int i = 0; i < 18; i++) {
            insertImageRow(pid, randomImageKey(), i);
        }

        List<Integer> statuses = parallelUploadStatuses(pid, 6);

        int rows = count("SELECT count(*) FROM product_image WHERE product_id = ?", pid);
        assertThat(rows).isEqualTo(20);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(4);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
        assertThat(bucketKeys()).hasSize((rows - 18) * 3);
    }

    // The 2-at-a-time limit is covered by AdminProductImageBusyIT.

    // ---------------- compensation ----------------

    @Test
    void dbInsertFailingAfterASuccessfulStore_deletesTheStoredFiles() throws Exception {
        long pid = createProduct("Compensate " + token());
        // The real store runs (3 objects land in MinIO), but the returned size violates
        // chk_product_image_width_positive.
        doAnswer(inv -> {
                    StoredImage real = (StoredImage) inv.callRealMethod();
                    return new StoredImage(real.storageKey(), 0, 0);
                })
                .when(imageService)
                .store(any());
        int audits = auditRows();

        expectProblem(uploadJpeg(pid), 500, "INTERNAL_ERROR");

        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(imageService).delete(deleted.capture());
        assertThat(deleted.getValue()).matches("^products/[0-9a-f-]{36}$");
        assertNothingStored(pid);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void storeFailure_isReported500_andLeavesNothing() throws Exception {
        long pid = createProduct("Store fails " + token());
        doThrow(new com.bkstudio.shared.web.ApiException(
                        com.bkstudio.shared.web.ErrorCode.INTERNAL_ERROR, "Image storage failed"))
                .when(imageService)
                .store(any());

        expectProblem(uploadJpeg(pid), 500, "INTERNAL_ERROR");

        assertNothingStored(pid);
    }

    // ---------------- delete ----------------

    @Test
    void deleteImage_removesTheRowAndAllThreeObjects_andAuditsIt() throws Exception {
        long pid = createProduct("Delete " + token());
        ResultActions up = uploadJpeg(pid).andExpect(status().isCreated());
        long imageId = readId(up, "$.id");
        String key = storageKeyOf(up);
        ResultActions other = uploadJpeg(pid);
        String otherKey = storageKeyOf(other);
        assertThat(bucketKeys()).hasSize(6);

        ResultActions del =
                call(HttpMethod.DELETE, "/api/admin/images/" + imageId, null).andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM product_image WHERE id = ?", imageId))
                .isZero();
        assertThat(bucketKeys()).containsExactlyInAnyOrderElementsOf(renditions(otherKey)); // the sibling stays
        assertThat(bucketKeys()).doesNotContainAnyElementsOf(renditions(key));
        List<Audit> rows = audits("IMAGE", imageId);
        assertThat(rows).extracting(Audit::action).containsExactly("IMAGE_ADDED", "IMAGE_DELETED");
        Audit a = rows.get(1);
        assertThat(a.after()).isNull();
        assertThat(om.readTree(a.before()).path("storageKey").asString()).isEqualTo(key);
        assertThat(a.requestId()).isEqualTo(del.andReturn().getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void deleteImageTwice_secondIs404() throws Exception {
        long pid = createProduct("Delete twice " + token());
        long imageId = readId(uploadJpeg(pid), "$.id");

        call(HttpMethod.DELETE, "/api/admin/images/" + imageId, null).andExpect(status().isNoContent());
        expectProblem(call(HttpMethod.DELETE, "/api/admin/images/" + imageId, null), 404, "NOT_FOUND");
    }

    private List<Integer> runParallel(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> http(HttpMethod method, String path, String body) {
        return () -> mvc.perform(withCsrf(method, path, body, session, nextIp()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void parallelDeletesOfTheSameImage_oneWins204_otherIs404_oneAuditRow_storageDeletedOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Mockito.clearInvocations(imageService);
            long pid = createProduct("Delete race " + round + " " + token());
            long imageId = readId(uploadJpeg(pid), "$.id");
            String path = "/api/admin/images/" + imageId;

            List<Integer> statuses = runParallel(List.of(
                    http(HttpMethod.DELETE, path, null),
                    http(HttpMethod.DELETE, path, null),
                    http(HttpMethod.DELETE, path, null)));

            assertThat(statuses).containsExactlyInAnyOrder(204, 404, 404);
            assertThat(audits("IMAGE", imageId))
                    .extracting(Audit::action)
                    .containsExactly("IMAGE_ADDED", "IMAGE_DELETED");
            verify(imageService, times(1)).delete(anyString());
            assertThat(bucketKeys()).isEmpty();
        }
    }

    @Test
    void altUpdateRacingADelete_neverReturns500() throws Exception {
        for (int round = 0; round < 10; round++) {
            long pid = createProduct("Alt race " + round + " " + token());
            long imageId = insertImageRow(pid, randomImageKey(), 0);
            String path = "/api/admin/images/" + imageId;

            List<Integer> statuses = runParallel(List.of(
                    http(HttpMethod.PATCH, path, j("altText", "new alt")),
                    http(HttpMethod.DELETE, path, null),
                    http(HttpMethod.PATCH, path, j("altText", "other alt"))));

            assertThat(statuses.get(1)).isEqualTo(204);
            assertThat(statuses.get(0)).isIn(200, 404);
            assertThat(statuses.get(2)).isIn(200, 404);
            // an audited alt change always belongs to a request that answered 200
            long altAudits = audits("IMAGE", imageId).stream()
                    .filter(a -> a.action().equals("IMAGE_ALT_UPDATED"))
                    .count();
            assertThat(altAudits)
                    .isEqualTo(statuses.stream().filter(x -> x == 200).count());
        }
    }

    @Test
    void deleteImage_whenStorageFails_stillReturns204_andLogsNoSecrets() throws Exception {
        long pid = createProduct("Delete fails " + token());
        long imageId = readId(uploadJpeg(pid), "$.id");
        doThrow(new RuntimeException("S3 exploded; secret=" + PASSWORD + " user=" + USER))
                .when(imageService)
                .delete(anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(AdminImageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call(HttpMethod.DELETE, "/api/admin/images/" + imageId, null).andExpect(status().isNoContent());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(count("SELECT count(*) FROM product_image WHERE id = ?", imageId))
                .isZero();
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent e : appender.list) {
            String text = e.getFormattedMessage() + e.getThrowableProxy();
            assertThat(text).doesNotContain(PASSWORD, USER, "exploded", "secret");
        }
        // the row is gone and the files are orphaned (documented shortcut); nothing else is lost
        assertThat(bucketKeys()).hasSize(3);
    }

    @Test
    void deleteImage_withAStorageKeyThatIsNotOurs_stillDeletesTheRowAndReturns204() throws Exception {
        long pid = createProduct("Odd key " + token());
        long imageId = insertImageRow(pid, "legacy/not-a-uuid", 0);

        call(HttpMethod.DELETE, "/api/admin/images/" + imageId, null).andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM product_image WHERE id = ?", imageId))
                .isZero();
    }
}
