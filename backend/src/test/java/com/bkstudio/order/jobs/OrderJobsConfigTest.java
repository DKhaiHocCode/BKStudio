package com.bkstudio.order.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** T-050: a wrong retention setting must stop the application at startup, not delete data early. */
class OrderJobsConfigTest {

    private static OrderJobs jobs(int completeAfterDays, int retentionDays) {
        return new OrderJobs(null, null, null, null, null, null, null, true, completeAfterDays, retentionDays);
    }

    @Test
    void constructor_defaultsAndLowerBounds_accepted() {
        assertThatCode(() -> jobs(30, 730)).doesNotThrowAnyException();
        assertThatCode(() -> jobs(1, 365)).doesNotThrowAnyException();
    }

    @Test
    void constructor_retentionBelowOneYear_refusesToStart() {
        for (int days : new int[] {364, 30, 0, -1}) {
            assertThatThrownBy(() -> jobs(30, days))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.jobs.retention-days")
                    .hasMessageContaining(String.valueOf(days));
        }
    }

    @Test
    void constructor_completeAfterBelowOneDay_refusesToStart() {
        for (int days : new int[] {0, -5}) {
            assertThatThrownBy(() -> jobs(days, 730))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.jobs.complete-after-days");
        }
    }

    @Test
    void cleared_listsEveryPersonalColumnOnce() {
        assertThat(OrderJobs.CLEARED)
                .containsOnlyKeys(
                        "customer_name",
                        "customer_email",
                        "phone",
                        "ship_line1",
                        "ship_line2",
                        "ship_city",
                        "ship_region",
                        "ship_postal_code",
                        "terms_accepted_ip",
                        "admin_note",
                        "cancel_reason",
                        "tracking_number",
                        "access_token_hash");
    }
}
