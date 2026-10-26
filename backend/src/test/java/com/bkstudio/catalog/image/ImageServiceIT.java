package com.bkstudio.catalog.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import com.bkstudio.support.AbstractPostgresTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

class ImageServiceIT extends AbstractPostgresTest {

    private static final String BUCKET = "bkstudio-it";
    private static final String USER = "minioadmin";
    private static final String PASSWORD = "minioadmin-secret";

    // Static singleton like the Postgres container: started once per JVM, removed by Ryuk on exit.
    // Same distroless image as infra/docker-compose.dev.yml.
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
        r.add("app.storage.endpoint", ImageServiceIT::endpoint);
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

    @Autowired
    ImageService service;

    @Autowired
    ImageProcessor processor;

    @Autowired
    ObjectProvider<S3Client> appClient;

    @Autowired
    StorageProperties props;

    private static List<String> allKeys() {
        return s3.listObjectsV2(r -> r.bucket(BUCKET)).contents().stream()
                .map(S3Object::key)
                .toList();
    }

    private static HeadObjectResponse head(String key) {
        return s3.headObject(r -> r.bucket(BUCKET).key(key));
    }

    @Test
    void store_validJpeg_putsThreeWebpObjectsWithCacheHeaders() {
        StoredImage stored = service.store(new ByteArrayInputStream(TestImages.jpeg(3000, 2000)));

        assertThat(stored.storageKey()).matches("^products/[0-9a-f-]{36}$");
        assertThat(stored.width()).isEqualTo(2000);
        assertThat(stored.height()).isEqualTo(1333);
        for (int w : List.of(400, 1000, 2000)) {
            String key = stored.storageKey() + "/" + w + ".webp";
            HeadObjectResponse head = head(key);
            assertThat(head.contentType()).isEqualTo("image/webp");
            assertThat(head.cacheControl()).contains("immutable");
            byte[] body = s3.getObject(r -> r.bucket(BUCKET).key(key), ResponseTransformer.toBytes())
                    .asByteArray();
            assertThat(TestImages.isWebp(body)).isTrue();
            assertThat(TestImages.decode(body).getWidth()).isEqualTo(w);
        }
    }

    @Test
    void store_twice_returnsDifferentKeys() {
        byte[] jpeg = TestImages.jpeg(500, 300);
        StoredImage a = service.store(new ByteArrayInputStream(jpeg));
        StoredImage b = service.store(new ByteArrayInputStream(jpeg));

        assertThat(a.storageKey()).isNotEqualTo(b.storageKey());
    }

    @Test
    void store_invalidFile_throwsValidationFailedAndLeavesNoObjects() {
        List<String> before = allKeys();

        assertThatThrownBy(() -> service.store(
                        new ByteArrayInputStream("<?php system($_GET[0]); ?>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(allKeys()).isEqualTo(before);
    }

    @Test
    void delete_removesAllThreeRenditions() {
        StoredImage stored = service.store(new ByteArrayInputStream(TestImages.jpeg(1200, 800)));

        service.delete(stored.storageKey());

        for (int w : List.of(400, 1000, 2000)) {
            assertThatThrownBy(() -> head(stored.storageKey() + "/" + w + ".webp"))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void delete_missingKey_doesNotThrow() {
        assertThatCode(() -> service.delete("products/00000000-0000-0000-0000-000000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void store_whenBucketMissing_throwsInternalErrorAndLeavesNoObjects() {
        StorageProperties broken = new StorageProperties(
                props.endpoint(), props.region(), "no-such-bucket", USER, PASSWORD, props.pathStyle());
        ImageService failing = new ImageService(processor, new ImageStorage(appClient, broken));
        List<String> before = allKeys();

        assertThatThrownBy(() -> failing.store(new ByteArrayInputStream(TestImages.jpeg(500, 300))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(allKeys()).isEqualTo(before);
    }
}
