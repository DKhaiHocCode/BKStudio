package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.order.customer.domain.CustomerOrderLimiters;
import com.bkstudio.order.jobs.OrderJobs;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * T-050: "email me my link" racing the retention job. lookup() reads the customer's email, then rotates the token;
 * if the order is anonymised in between, it must not put a live token back or mail the address it read earlier.
 */
class CustomerOrderLookupRaceIT extends CustomerOrderTestBase {

    /** allowRotation() is called exactly between lookup's SELECT and its UPDATE: the hook for the race. */
    @MockitoSpyBean
    CustomerOrderLimiters limiters;

    @Autowired
    OrderJobs jobs;

    @Test
    void lookup_orderAnonymisedBetweenReadAndRotate_noMailAndNoLiveToken() throws Exception {
        long id = order("IN_STOCK", "CANCELLED", 1);
        String oldToken = tokenFor(id);
        jdbc.update(
                "UPDATE customer_order SET updated_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(731))),
                id);
        doAnswer(inv -> {
                    // Another thread = another connection: the job commits before lookup's UPDATE runs.
                    int done = CompletableFuture.supplyAsync(jobs::anonymiseOld).get(30, TimeUnit.SECONDS);
                    assertThat(done).isEqualTo(1);
                    return inv.callRealMethod();
                })
                .when(limiters)
                .allowRotation(id);

        lookup(codeOf(id), SECRET_EMAIL).andExpect(status().isAccepted());

        verify(limiters).allowRotation(id); // the email matched at read time, so the race window was really entered
        assertThat(count("SELECT count(*) FROM customer_order WHERE id = ? AND anonymised_at IS NOT NULL", id))
                .isEqualTo(1);
        assertThat(lookupMails(id)).isZero();
        assertThat(count("SELECT count(*) FROM email_outbox WHERE to_email = ?", SECRET_EMAIL))
                .isZero();
        assertThat(auditCount("ORDER_LINK_REISSUED", id)).isZero();
        assertThat(version(id)).isEqualTo(1); // the anonymise bump only, no rotation
        expectProblem(viewOrder(oldToken), 404, "NOT_FOUND");
    }
}
