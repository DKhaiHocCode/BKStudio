package com.bkstudio.admin.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.bkstudio.catalog.image.ImageService;
import com.bkstudio.catalog.image.StoredImage;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * T-029 fixtures. ImageService is mocked (no storage needed): storeProgress hands out unique keys and records them,
 * delete records the deleted keys. Lives in admin.auth only to reuse the package-private order/admin test bases.
 */
abstract class ProgressTestBase extends CustomerOrderTestBase {

    @MockitoBean
    ImageService imageService;

    /** Keys handed out by storeProgress and keys passed to delete, since the last reset. */
    final List<String> storedKeys = Collections.synchronizedList(new ArrayList<>());

    final List<String> deletedKeys = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void stubStorage() {
        when(imageService.storeProgress(any())).thenAnswer(inv -> newStored());
        doAnswer(inv -> {
                    deletedKeys.add(inv.getArgument(0));
                    return null;
                })
                .when(imageService)
                .delete(anyString());
    }

    StoredImage newStored() {
        String key = "progress/" + UUID.randomUUID();
        storedKeys.add(key);
        return new StoredImage(key, 1000, 800);
    }

    void resetKeys() {
        storedKeys.clear();
        deletedKeys.clear();
    }

    // ---- fixtures ----

    long inProduction() {
        return order("MADE_TO_ORDER", "IN_PRODUCTION", 1);
    }

    /** Inserts an approved stage directly, with n photos. */
    void seedStage(long orderId, String stage, String note, int photos) {
        jdbc.update(
                "INSERT INTO order_progress (order_id, stage, note, approved_by) VALUES (?, ?, ?, ?)",
                orderId,
                stage,
                note,
                admin.id());
        for (int i = 0; i < photos; i++) {
            jdbc.update(
                    "INSERT INTO order_progress_image (order_id, stage, storage_key, width, height, sort_order)"
                            + " VALUES (?, ?, ?, 1000, 800, ?)",
                    orderId,
                    stage,
                    "progress/" + UUID.randomUUID(),
                    i);
        }
    }

    // ---- requests ----

    static String path(long orderId, String stage) {
        return "/api/admin/orders/" + orderId + "/progress/" + stage;
    }

    ResultActions approve(long orderId, String stage, String note, MockMultipartFile... files) throws Exception {
        return approveAs(session, orderId, stage, note, files);
    }

    ResultActions approveAs(MockHttpSession s, long orderId, String stage, String note, MockMultipartFile... files)
            throws Exception {
        String csrf = csrfToken();
        var b = MockMvcRequestBuilders.multipart(HttpMethod.POST, path(orderId, stage))
                .with(r -> {
                    r.setRemoteAddr(nextIp());
                    return r;
                })
                .cookie(new Cookie("XSRF-TOKEN", csrf))
                .header("X-XSRF-TOKEN", csrf);
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        if (note != null) {
            b.param("note", note);
        }
        return mvc.perform(s == null ? b : b.session(s));
    }

    static MockMultipartFile photo() {
        return photo("a.png", png(60, 40));
    }

    static MockMultipartFile photo(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/png", bytes);
    }

    static MockMultipartFile[] photos(int n) {
        MockMultipartFile[] out = new MockMultipartFile[n];
        for (int i = 0; i < n; i++) {
            out[i] = photo("p" + i + ".png", png(60, 40));
        }
        return out;
    }

    ResultActions progressOf(String token) throws Exception {
        return mvc.perform(getFrom("/api/orders/" + token + "/progress", null, nextIp()));
    }

    // ---- reads ----

    int progressRows(long orderId) {
        return count("SELECT count(*) FROM order_progress WHERE order_id = ?", orderId);
    }

    int imageRows(long orderId) {
        return count("SELECT count(*) FROM order_progress_image WHERE order_id = ?", orderId);
    }

    int progressMails(long orderId) {
        return outbox("ORDER_PROGRESS", orderId);
    }
}
