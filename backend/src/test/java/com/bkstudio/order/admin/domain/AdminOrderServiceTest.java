package com.bkstudio.order.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminOrderServiceTest {

    @Test
    void restockable_onlyForfeitedMadeToOrderNotYetRestocked() {
        assertThat(AdminOrderService.restockable("FORFEITED", "MADE_TO_ORDER", false))
                .isTrue();
        assertThat(AdminOrderService.restockable("FORFEITED", "MADE_TO_ORDER", true))
                .isFalse();
        assertThat(AdminOrderService.restockable("FORFEITED", "IN_STOCK", false))
                .isFalse();
        assertThat(AdminOrderService.restockable("QUEUED", "MADE_TO_ORDER", false))
                .isFalse();
    }
}
