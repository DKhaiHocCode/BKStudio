package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
        properties = {
            "app.checkout.rate-limit-max=2",
            "app.checkout.rate-limit-window=1h",
            "app.checkout.email-limit-max=3",
            "app.checkout.email-limit-window=1h"
        })
class CheckoutRateLimitIT extends CheckoutTestBase {

    @Test
    void thirdOrderFromSameIp_429WithRetryAfter_replayAndOtherIpUnaffected() throws Exception {
        long v = stockVariant(1_000, 10);
        String ip = nextIp();
        UUID first = UUID.randomUUID();

        assertThat(place(first, order("IN_STOCK", v, 1), ip).status()).isEqualTo(201);
        // a replay does not consume an attempt
        assertThat(place(first, order("IN_STOCK", v, 1), ip).status()).isEqualTo(200);
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip).status())
                .isEqualTo(201);

        Res limited = place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.<String>json("$.code")).isEqualTo("RATE_LIMITED");
        assertThat(limited.retryAfter()).isNotNull();
        assertThat(Long.parseLong(limited.retryAfter())).isBetween(1L, 3600L);
        assertThat(stock(v)).isEqualTo(8);

        // a blocked address is refused up front (before any DB work), even for an earlier key
        Res blockedReplay = place(first, order("IN_STOCK", v, 1), ip);
        assertThat(blockedReplay.status()).isEqualTo(429);
        assertThat(blockedReplay.retryAfter()).isNotNull();
        assertThat(orders()).isEqualTo(2);
        // another address is not limited
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), nextIp()).status())
                .isEqualTo(201);

        // window passes -> allowed again
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip).status())
                .isEqualTo(201);
    }

    @Test
    void fourthOrderForSameEmailFromOtherIps_429SameAsIpLimit_otherEmailAndNextWindowAllowed() throws Exception {
        long v = stockVariant(1_000, 10);
        for (int i = 0; i < 3; i++) {
            assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
        }

        // email differs only by case: still the same customer
        var sameEmail = order("IN_STOCK", v, 1);
        sub(sameEmail, "customer").put("email", "JANE.secret@example.com");
        Res limited = place(sameEmail);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.<String>json("$.code")).isEqualTo("RATE_LIMITED");
        assertThat(limited.<String>json("$.detail")).isEqualTo("Too many orders from this address. Try again later.");
        assertThat(limited.body()).doesNotContainIgnoringCase("jane.secret");
        // always the whole window, however long ago the first order was: no hint about this mailbox's activity
        assertThat(limited.retryAfter()).isEqualTo("3600");
        clock.advance(Duration.ofMinutes(30));
        assertThat(place(sameEmail).retryAfter()).isEqualTo("3600");
        clock.advance(Duration.ofMinutes(-30));
        assertThat(stock(v)).isEqualTo(7);
        assertThat(orders()).isEqualTo(3);

        var otherEmail = order("IN_STOCK", v, 1);
        sub(otherEmail, "customer").put("email", "bob@example.com");
        assertThat(place(otherEmail).status()).isEqualTo(201);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
    }

    @Test
    void plusTagAndCaseVariantsOfOneMailbox_shareTheCap_dotsDoNot() throws Exception {
        long v = stockVariant(1_000, 10);
        for (String email :
                List.of("jane.secret@example.com", "jane.secret+a@example.com", "Jane.Secret+b+c@Example.com")) {
            var body = order("IN_STOCK", v, 1);
            sub(body, "customer").put("email", email);
            assertThat(place(body).status()).as(email).isEqualTo(201);
        }

        var tagged = order("IN_STOCK", v, 1);
        sub(tagged, "customer").put("email", "jane.secret+new@example.com");
        assertThat(place(tagged).status()).isEqualTo(429);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(429);
        assertThat(orders()).isEqualTo(3);

        // dots are not folded: a different mailbox outside Gmail
        var dotless = order("IN_STOCK", v, 1);
        sub(dotless, "customer").put("email", "janesecret@example.com");
        assertThat(place(dotless).status()).isEqualTo(201);
    }

    @Test
    void replayOfExistingKeyAtEmailCap_stillReturns200() throws Exception {
        long v = stockVariant(1_000, 10);
        UUID first = UUID.randomUUID();
        assertThat(place(first, order("IN_STOCK", v, 1), nextIp()).status()).isEqualTo(201);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(429);

        Res replay = place(first, order("IN_STOCK", v, 1), nextIp());

        assertThat(replay.status()).isEqualTo(200);
        assertThat(orders()).isEqualTo(3);
    }

    @Test
    void parallelCheckoutsForOneMailbox_neverOvershootTheCap() throws Exception {
        long v = stockVariant(1_000, 20);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var body = order("IN_STOCK", v, 1);
            sub(body, "customer")
                    .put("email", i % 2 == 0 ? "jane.secret@example.com" : "JANE.secret+" + i + "@example.com");
            calls.add(() -> place(body)); // own key and own IP each: only the email cap can stop them
        }

        List<Res> res = concurrently(calls);

        assertThat(res.stream().filter(r -> r.status() == 201)).hasSize(3);
        assertThat(res.stream().filter(r -> r.status() == 429)).hasSize(5);
        assertThat(orders()).isEqualTo(3);
        assertThat(stock(v)).isEqualTo(17);
    }
}
