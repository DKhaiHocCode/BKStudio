package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.catalog.image.StoredImage;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** T-029 POST /api/admin/orders/{id}/progress/{stage}: rules, validation, idempotency, storage failures, security. */
class AdminProgressApiIT extends ProgressTestBase {

    // ================= happy path =================

    @Test
    void approveRawPrint_storesRowImagesOutboxAndAudit_orderUntouched() throws Exception {
        long id = inProduction();
        int versionBefore = version(id);

        approve(id, "RAW_PRINT", "  first print  ", photos(3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("RAW_PRINT"))
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.note").value("first print"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.images[0].url").value(Matchers.endsWith("/1000.webp")));

        assertThat(progressRows(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT approved_by FROM order_progress WHERE order_id = ?", Long.class, id))
                .isEqualTo(admin.id());
        List<Integer> sorts = jdbc.queryForList(
                "SELECT sort_order FROM order_progress_image WHERE order_id = ? AND stage = 'RAW_PRINT'"
                        + " ORDER BY sort_order",
                Integer.class,
                id);
        assertThat(sorts).containsExactly(0, 1, 2);
        assertThat(jdbc.queryForList(
                        "SELECT storage_key FROM order_progress_image WHERE order_id = ?", String.class, id))
                .containsExactlyInAnyOrderElementsOf(storedKeys);

        assertThat(progressMails(id)).isEqualTo(1);
        Map<String, Object> mail = jdbc.queryForMap(
                "SELECT dedupe_key, payload::text AS payload FROM email_outbox WHERE template = 'ORDER_PROGRESS'");
        assertThat(mail.get("dedupe_key")).isEqualTo("order:" + id + ":progress:RAW_PRINT");
        String payload = (String) mail.get("payload");
        assertThat(JsonPath.<Map<String, Object>>read(payload, "$").keySet())
                .containsExactlyInAnyOrder("publicCode", "stage");
        assertThat(payload).doesNotContain(SECRET_EMAIL).doesNotContain("first print");

        assertThat(auditCount("PROGRESS_APPROVED", id)).isEqualTo(1);
        String after = auditAfter("PROGRESS_APPROVED", id);
        assertThat(JsonPath.<Integer>read(after, "$.imageCount")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(after, "$.noteLength")).isEqualTo("first print".length());
        assertThat(JsonPath.<String>read(after, "$.stage")).isEqualTo("RAW_PRINT");
        assertThat(after).doesNotContain("first print");

        assertThat(orderStatus(id)).isEqualTo("IN_PRODUCTION");
        assertThat(version(id)).isEqualTo(versionBefore);
    }

    @Test
    void approveWithoutNoteOrPhotos_isFine() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(0));
        assertThat(imageRows(id)).isZero();
        assertThat(progressRows(id)).isEqualTo(1);
    }

    // ================= ordering =================

    @Test
    void prepBeforeRawPrint_and_paintBeforePrep_409_nothingStored() throws Exception {
        long id = inProduction();
        expectProblem(approve(id, "PREP", null, photo()), 409, "INVALID_TRANSITION");
        expectProblem(approve(id, "PAINT", null, photo()), 409, "INVALID_TRANSITION");
        approve(id, "RAW_PRINT", null).andExpect(status().isOk());
        expectProblem(approve(id, "PAINT", null, photo()), 409, "INVALID_TRANSITION");

        assertThat(progressRows(id)).isEqualTo(1);
        assertThat(imageRows(id)).isZero();
        assertThat(progressMails(id)).isEqualTo(1);
    }

    @Test
    void stagesInOrder_allSucceed() throws Exception {
        long id = inProduction();
        for (String stage : List.of("RAW_PRINT", "PREP", "PAINT")) {
            approve(id, stage, null, photo()).andExpect(status().isOk());
        }
        assertThat(progressRows(id)).isEqualTo(3);
        assertThat(progressMails(id)).isEqualTo(3);
        assertThat(auditCount("PROGRESS_APPROVED", id)).isEqualTo(3);
        assertThat(orderStatus(id)).isEqualTo("IN_PRODUCTION");
    }

    // ================= idempotency =================

    @Test
    void secondApproveOfSameStage_200Replay_noExtraRowsMailsAuditOrFiles() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", "n", photo()).andExpect(status().isOk());
        int stored = storedKeys.size();

        approve(id, "RAW_PRINT", "other note", photo(), photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.note").value("n"))
                .andExpect(jsonPath("$.images.length()").value(1));

        assertThat(progressRows(id)).isEqualTo(1);
        assertThat(imageRows(id)).isEqualTo(1);
        assertThat(progressMails(id)).isEqualTo(1);
        assertThat(auditCount("PROGRESS_APPROVED", id)).isEqualTo(1);
        assertThat(storedKeys).hasSize(stored); // the replay never touched storage
    }

    @Test
    void replayAfterOrderMovedOn_stillOk() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", null, photo()).andExpect(status().isOk());
        act(id, "mark-finished", "version", version(id)).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("AWAITING_BALANCE");

        approve(id, "RAW_PRINT", null, photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
        assertThat(progressRows(id)).isEqualTo(1);
        assertThat(progressMails(id)).isEqualTo(1);

        // a stage that was never approved is still refused on the moved-on order
        expectProblem(approve(id, "PREP", null), 409, "INVALID_TRANSITION");
    }

    // ================= validation =================

    @Test
    void sixFiles_400FilesField_nothingStored() throws Exception {
        long id = inProduction();
        expectField(approve(id, "RAW_PRINT", null, photos(6)), "files");
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
        assertThat(imageRows(id)).isZero();
        assertThat(progressMails(id)).isZero();
    }

    @Test
    void fiveFiles_ok() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", null, photos(5)).andExpect(status().isOk());
        assertThat(imageRows(id)).isEqualTo(5);
    }

    @Test
    void emptyFile_400_nothingStored() throws Exception {
        long id = inProduction();
        expectProblem(approve(id, "RAW_PRINT", null, photo(), photo("e.png", new byte[0])), 400, "VALIDATION_FAILED");
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void noteTooLongOrWithControlChar_400NoteField() throws Exception {
        long id = inProduction();
        expectField(approve(id, "RAW_PRINT", "x".repeat(501), photo()), "note");
        expectField(approve(id, "RAW_PRINT", "a" + (char) 7 + "b", photo()), "note");
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void note500Chars_ok_andLineBreakAllowed() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", "y".repeat(500)).andExpect(status().isOk());
        long id2 = inProduction();
        approve(id2, "RAW_PRINT", "line1\nline2").andExpect(status().isOk());
    }

    @Test
    void badStage_400StageField() throws Exception {
        long id = inProduction();
        expectField(approve(id, "FOO", null), "stage");
        expectField(approve(id, "FINISHED", null), "stage");
        expectField(approve(id, "raw_print", null), "stage");
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void unknownOrder_404() throws Exception {
        expectProblem(approve(UNKNOWN_ID, "RAW_PRINT", null, photo()), 404, "NOT_FOUND");
        verify(imageService, never()).storeProgress(any());
    }

    @Test
    void jsonBodyInsteadOfMultipart_rejected() throws Exception {
        long id = inProduction();
        String csrf = csrfToken();
        mvc.perform(MockMvcRequestBuilders.post(path(id, "RAW_PRINT"))
                        .session(session)
                        .with(r -> {
                            r.setRemoteAddr(nextIp());
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
        assertThat(progressRows(id)).isZero();
    }

    // ================= order state =================

    @Test
    void inStockOrder_409() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        expectProblem(approve(id, "RAW_PRINT", null, photo()), 409, "INVALID_TRANSITION");
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void orderNotInProduction_409_whenStageNotYetApproved() throws Exception {
        for (String st : List.of("AWAITING_DEPOSIT", "QUEUED", "AWAITING_BALANCE", "SHIPPED", "CANCELLED")) {
            long id = order("MADE_TO_ORDER", st, 1);
            expectProblem(approve(id, "RAW_PRINT", null, photo()), 409, "INVALID_TRANSITION");
            assertThat(progressRows(id)).as(st).isZero();
        }
        verify(imageService, never()).storeProgress(any());
    }

    // ================= storage failure =================

    @Test
    void secondPhotoFailsToStore_firstPhotoFilesDeleted_noDbRows() throws Exception {
        long id = inProduction();
        StoredImage first = new StoredImage("progress/first-" + token(), 1000, 800);
        when(imageService.storeProgress(any()))
                .thenReturn(first)
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "bad image"));

        expectProblem(approve(id, "RAW_PRINT", "n", photos(2)), 400, "VALIDATION_FAILED");

        assertThat(deletedKeys).containsExactly(first.storageKey());
        assertThat(progressRows(id)).isZero();
        assertThat(imageRows(id)).isZero();
        assertThat(progressMails(id)).isZero();
        assertThat(auditCount("PROGRESS_APPROVED", id)).isZero();
    }

    @Test
    void unexpectedStorageError_500WithoutLeak_filesDeleted() throws Exception {
        long id = inProduction();
        StoredImage first = new StoredImage("progress/first-" + token(), 1000, 800);
        when(imageService.storeProgress(any())).thenReturn(first).thenThrow(new IllegalStateException("s3 down"));

        Res r = res(approve(id, "RAW_PRINT", null, photos(2)));

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.body()).doesNotContain("s3 down");
        assertThat(deletedKeys).containsExactly(first.storageKey());
        assertThat(progressRows(id)).isZero();
    }

    // ================= security =================

    @Test
    void unauthenticated_401_nothingStored() throws Exception {
        long id = inProduction();
        approveAs(null, id, "RAW_PRINT", null, photo()).andExpect(status().isUnauthorized());
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void passwordOnlySession_withoutTotp_401() throws Exception {
        long id = inProduction();
        approveAs(login(admin), id, "RAW_PRINT", null, photo()).andExpect(status().isUnauthorized());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void missingCsrf_403() throws Exception {
        long id = inProduction();
        mvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.POST, path(id, "RAW_PRINT"))
                        .file(photo())
                        .session(session)
                        .with(r -> {
                            r.setRemoteAddr(nextIp());
                            return r;
                        }))
                .andExpect(status().isForbidden());
        verify(imageService, never()).storeProgress(any());
        assertThat(progressRows(id)).isZero();
    }

    @Test
    void dedupeKeyIsPerOrder_twoOrdersBothGetTheirMail() throws Exception {
        long a = inProduction();
        long b = inProduction();
        approve(a, "RAW_PRINT", null).andExpect(status().isOk());
        approve(b, "RAW_PRINT", null).andExpect(status().isOk());
        assertThat(progressMails(a)).isEqualTo(1);
        assertThat(progressMails(b)).isEqualTo(1);
    }
}
