package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockHttpSession;

/** T-029 races: two admins approving the same stage, and approving a stage against a cancel by the shop. */
class AdminProgressConcurrencyIT extends ProgressTestBase {

    private static final int ROUNDS = 15;

    @Test
    @Timeout(300)
    void twoAdminsApproveSameStage_oneRowOneMailWinnerPhotosOnly() throws Exception {
        TestAdmin other = newAdmin();
        MockHttpSession otherSession = fullLogin(other);

        for (int round = 0; round < ROUNDS; round++) {
            resetKeys();
            long id = inProduction();

            List<Res> r = concurrently(List.<Callable<Res>>of(
                    () -> res(approveAs(session, id, "RAW_PRINT", "a", photos(2))),
                    () -> res(approveAs(otherSession, id, "RAW_PRINT", "b", photos(2)))));

            assertThat(r)
                    .as("round %d", round)
                    .allSatisfy(x -> assertThat(x.status()).isEqualTo(200));
            List<Boolean> replay = r.stream()
                    .map(x -> JsonPath.<Boolean>read(x.body(), "$.idempotentReplay"))
                    .toList();
            assertThat(replay).as("round %d: exactly one winner", round).containsExactlyInAnyOrder(false, true);

            assertThat(progressRows(id)).isEqualTo(1);
            assertThat(progressMails(id)).isEqualTo(1);
            assertThat(auditCount("PROGRESS_APPROVED", id)).isEqualTo(1);

            List<String> referenced = jdbc.queryForList(
                    "SELECT storage_key FROM order_progress_image WHERE order_id = ?", String.class, id);
            assertThat(referenced).hasSize(2).isSubsetOf(storedKeys);
            // every stored file that no row references was deleted, and no referenced file was deleted
            List<String> orphans = new ArrayList<>(storedKeys);
            orphans.removeAll(referenced);
            assertThat(deletedKeys).containsExactlyInAnyOrderElementsOf(orphans);
            assertThat(deletedKeys).doesNotContainAnyElementsOf(referenced);

            // both responses show the winner's photos
            List<String> urls0 = JsonPath.read(r.get(0).body(), "$.images[*].url");
            List<String> urls1 = JsonPath.read(r.get(1).body(), "$.images[*].url");
            assertThat(urls0).containsExactlyInAnyOrderElementsOf(urls1);
            assertThat(orderStatus(id)).isEqualTo("IN_PRODUCTION");
        }
    }

    @Test
    @Timeout(300)
    void approvePaintVsCancelByShop_consistent_neverServerError() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetKeys();
            long id = inProduction();
            seedStage(id, "RAW_PRINT", "", 0);
            seedStage(id, "PREP", "", 0);

            List<Res> r = concurrently(List.<Callable<Res>>of(
                    () -> res(approve(id, "PAINT", null, photo())),
                    () -> res(act(id, "cancel-by-shop", "version", 0, "reason", "race", "refundRef", "RF-1"))));
            Res approve = r.get(0);
            Res cancel = r.get(1);

            assertThat(cancel.status()).as("round %d: %s", round, r).isEqualTo(200); // approving never bumps version
            assertThat(approve.status()).as("round %d: %s", round, r).isIn(200, 409);
            assertThat(orderStatus(id)).isEqualTo("CANCELLED_REFUNDED");

            boolean approved = approve.status() == 200;
            int paintRows = count("SELECT count(*) FROM order_progress WHERE order_id = ? AND stage = 'PAINT'", id);
            assertThat(paintRows).as("round %d", round).isEqualTo(approved ? 1 : 0);
            assertThat(progressMails(id)).isEqualTo(approved ? 1 : 0);
            assertThat(auditCount("PROGRESS_APPROVED", id)).isEqualTo(approved ? 1 : 0);
            assertThat(imageRows(id)).isEqualTo(approved ? 1 : 0);
            if (approved) {
                assertThat(deletedKeys).isEmpty();
            } else {
                assertThat(deletedKeys)
                        .containsExactlyInAnyOrderElementsOf(storedKeys); // lost the race: no leftover file
            }
        }
    }
}
