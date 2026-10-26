package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * MockMvc does not enforce the multipart limits (Tomcat does), so the 10 MB limit is proven over real HTTP against
 * the embedded server. Needs no object store: an oversize upload is rejected before it reaches the image service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminProductUploadLimitIT extends AdminProductTestBase {

    private static final int TEN_MB = 10 * 1024 * 1024;
    private static final String BOUNDARY = "----bkstudio-test-boundary";

    @LocalServerPort
    int port;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();

    private HttpResponse<byte[]> send(String method, String path, String contentType, byte[] body, boolean csrf)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (!cookies.isEmpty()) {
            StringBuilder c = new StringBuilder();
            cookies.forEach((k, v) ->
                    c.append(c.length() == 0 ? "" : "; ").append(k).append('=').append(v));
            b.header("Cookie", c.toString());
        }
        if (csrf && cookies.containsKey("XSRF-TOKEN")) {
            b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        for (String setCookie : r.headers().allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            cookies.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return r;
    }

    /** Full two-step login over HTTP with a fresh admin (the MockMvc admin of the base class stays untouched). */
    private void loginOverHttp() throws Exception {
        TestAdmin a = newAdmin();
        send("GET", "/api/admin/auth/me", null, null, false);
        String json = "application/json";
        assertThat(send(
                                "POST",
                                "/api/admin/auth/login",
                                json,
                                credentials(a.email(), PASSWORD).getBytes(StandardCharsets.UTF_8),
                                true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(send(
                                "POST",
                                "/api/admin/auth/totp",
                                json,
                                ("{\"code\":\"" + code(a, 0) + "\"}").getBytes(StandardCharsets.UTF_8),
                                true)
                        .statusCode())
                .isEqualTo(200);
    }

    private static byte[] multipartBody(int fileBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes + 512);
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.jpg\"\r\n"
                        + "Content-Type: image/jpeg\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[fileBytes]);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private HttpResponse<byte[]> uploadOverHttp(long productId, int fileBytes) throws Exception {
        return send(
                "POST",
                PRODUCTS + "/" + productId + "/images",
                "multipart/form-data; boundary=" + BOUNDARY,
                multipartBody(fileBytes),
                true);
    }

    @Test
    void imageOver10Mb_is413PayloadTooLargeProblem_andNothingIsStored() throws Exception {
        long pid = createProduct("Too big " + token());
        loginOverHttp();
        int audits = auditRows();

        HttpResponse<byte[]> r = uploadOverHttp(pid, TEN_MB + 1);

        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = om.readTree(new String(r.body(), StandardCharsets.UTF_8));
        assertThat(body.path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.path("status").asInt()).isEqualTo(413);
        assertThat(body.path("type").asString()).isEqualTo("https://bkstudio.xxx/errors/payload-too-large");
        assertThat(body.path("requestId").asString())
                .isNotBlank()
                .isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(count("SELECT count(*) FROM product_image WHERE product_id = ?", pid))
                .isZero();
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void imageOfExactly10Mb_passesTheSizeLimit_andIsRejectedAsNotAnImage() throws Exception {
        long pid = createProduct("Exactly 10mb " + token());
        loginOverHttp();

        HttpResponse<byte[]> r = uploadOverHttp(pid, TEN_MB);

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(om.readTree(new String(r.body(), StandardCharsets.UTF_8))
                        .path("code")
                        .asString())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void oversizeUploadWithoutLogin_isStill401_notAnInformationLeak() throws Exception {
        long pid = createProduct("Anon big " + token());
        send("GET", "/api/admin/auth/me", null, null, false); // only to obtain a CSRF cookie

        HttpResponse<byte[]> r = uploadOverHttp(pid, 1024);

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(List.of("UNAUTHENTICATED"))
                .contains(om.readTree(new String(r.body(), StandardCharsets.UTF_8))
                        .path("code")
                        .asString());
    }
}
