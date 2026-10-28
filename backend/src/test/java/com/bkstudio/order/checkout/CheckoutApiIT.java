package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bkstudio.order.checkout.domain.CheckoutService;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class CheckoutApiIT extends CheckoutTestBase {

    // ---- happy paths ----

    @Test
    void checkout_inStock_createsOrderDecrementsStockAndStoresOnlyTokenHash() throws Exception {
        long v = stockVariant(12_345, 5);
        int versionBefore = version(v);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);

        Res r = place(order("IN_STOCK", v, 2));

        root.detachAppender(logs);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.cacheControl()).contains("no-store");
        assertThat(r.<String>json("$.code")).matches("BK-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}");
        assertThat(r.<String>json("$.status")).isEqualTo("AWAITING_PAYMENT");
        assertThat((int) r.json("$.subtotalCents")).isEqualTo(24_690);
        assertThat((int) r.json("$.depositCents")).isZero();
        String url = r.json("$.orderUrl");
        assertThat(url).startsWith("http://localhost:3000/orders/");
        String token = tokenOf(url);

        assertThat(stock(v)).isEqualTo(3);
        assertThat(version(v)).isEqualTo(versionBefore + 1);
        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM order_item");
        assertThat(item)
                .containsEntry("product_name", "Figure Name")
                .containsEntry("scale", "1/7")
                .containsEntry("unit_price_cents", 12_345)
                .containsEntry("quantity", 2);

        Map<String, Object> o = jdbc.queryForMap("SELECT * FROM customer_order");
        assertThat((byte[]) o.get("access_token_hash"))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes()))
                .isEqualTo(CheckoutService.hashToken(token));
        assertThat(o.get("terms_version")).isEqualTo(TERMS_V);
        assertThat(((java.sql.Timestamp) o.get("terms_accepted_at")).toInstant())
                .isEqualTo(clock.instant());
        assertThat(String.valueOf(o.get("terms_accepted_ip"))).startsWith("172.");
        assertThat(((Number) o.get("shipping_cents")).intValue()).isZero();
        assertThat(o.get("currency")).isEqualTo("USD");
        // the raw token is stored in no column of the order row
        assertThat(o.values().stream()
                        .filter(x -> x != null && !(x instanceof byte[]))
                        .map(String::valueOf))
                .noneMatch(s -> s.contains(token));

        // PII / secrets never reach the logs
        String all = String.join(
                "\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).doesNotContain("jane.secret", "Secret Street", "9123", token, "Jane Doe");
    }

    @Test
    void checkout_madeToOrder_reservesQuotaWithoutStockOrQueue_oddSubtotalDepositRoundsUp() throws Exception {
        long limited = variant(product("ACTIVE"), "1/7", 10_001, 3, true, 4);
        Res r = place(order("MADE_TO_ORDER", limited, 1));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.<String>json("$.status")).isEqualTo("AWAITING_DEPOSIT");
        assertThat((int) r.json("$.subtotalCents")).isEqualTo(10_001);
        assertThat((int) r.json("$.depositCents")).isEqualTo(5_001);
        assertThat(quota(limited)).isEqualTo(3);
        assertThat(stock(limited)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM queue_entry")).isZero();
        assertThat(count("SELECT coalesce(sum(allocated),0) FROM production_month"))
                .isZero();
    }

    @Test
    void checkout_madeToOrder_evenSubtotalDepositIsExactHalf() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 10_000, 0, true, null);
        Res r = place(order("MADE_TO_ORDER", v, 1));
        assertThat((int) r.json("$.depositCents")).isEqualTo(5_000);
    }

    @Test
    void checkout_madeToOrder_unlimitedQuotaStaysNull() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 10_000, 0, true, null);
        assertThat(place(order("MADE_TO_ORDER", v, 5)).status()).isEqualTo(201);
        assertThat(quota(v)).isNull();
        assertThat(stock(v)).isZero();
    }

    @Test
    void checkout_unlistedProduct_isOrderable() throws Exception {
        long v = variant(product("UNLISTED"), "1/7", 5_000, 1, false, null);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
    }

    @Test
    void checkout_serverPrice_ignoresClientSuppliedPrices() throws Exception {
        long v = stockVariant(10_000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        items(body).get(0).put("priceCents", 1);
        items(body).get(0).put("unitPriceCents", 1);
        body.put("subtotalCents", 1);
        Res r = place(body);
        assertThat(r.status()).isEqualTo(201);
        assertThat((int) r.json("$.subtotalCents")).isEqualTo(10_000);
        assertThat(count("SELECT subtotal_cents FROM customer_order")).isEqualTo(10_000);
        assertThat(count("SELECT unit_price_cents FROM order_item")).isEqualTo(10_000);
    }

    @Test
    void checkout_madeToOrderQuota_decrementBumpsVariantVersionByOne() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 1_000, 0, true, 4);
        int before = version(v);
        assertThat(place(order("MADE_TO_ORDER", v, 2)).status()).isEqualTo(201);
        assertThat(quota(v)).isEqualTo(2);
        assertThat(version(v)).isEqualTo(before + 1);
    }

    @Test
    void idempotency_madeToOrderReplay_200WithDepositAndAwaitingDeposit() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 10_001, 0, true, 4);
        UUID key = UUID.randomUUID();
        Res first = place(key, order("MADE_TO_ORDER", v, 1), nextIp());
        Res second = place(key, order("MADE_TO_ORDER", v, 1), nextIp());
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.<String>json("$.status")).isEqualTo("AWAITING_DEPOSIT");
        assertThat((int) second.json("$.depositCents")).isEqualTo(5_001);
        assertThat(second.<Object>json("$.orderUrl")).isNull();
        assertThat(quota(v)).isEqualTo(3);
    }

    @Test
    void idempotency_sameKeyDifferentItems_returnsOriginalOrder_itemsAreNotCompared() throws Exception {
        long a = stockVariant(1_000, 5);
        long b = stockVariant(2_000, 5);
        UUID key = UUID.randomUUID();
        Res first = place(key, order("IN_STOCK", a, 1), nextIp());
        Res second = place(key, order("IN_STOCK", b, 3), nextIp());
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.<String>json("$.code")).isEqualTo(first.<String>json("$.code"));
        assertThat((int) second.json("$.subtotalCents")).isEqualTo(1_000);
        assertThat(stock(b)).isEqualTo(5);
        assertThat(orders()).isEqualTo(1);
    }

    // ---- idempotency (sequential) ----

    @Test
    void idempotency_sameKeySameBody_replaysWithoutNewOrderOrStockChange() throws Exception {
        long v = stockVariant(1_000, 5);
        UUID key = UUID.randomUUID();
        Res first = place(key, order("IN_STOCK", v, 1), nextIp());
        Res second = place(key, order("IN_STOCK", v, 1), nextIp());

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.<String>json("$.code")).isEqualTo(first.<String>json("$.code"));
        assertThat(second.<Object>json("$.orderUrl")).isNull();
        assertThat(second.cacheControl()).contains("no-store");
        assertThat(stock(v)).isEqualTo(4);
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void idempotency_sameKeyDifferentFulfillmentType_conflict() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 1_000, 5, true, null);
        UUID key = UUID.randomUUID();
        place(key, order("IN_STOCK", v, 1), nextIp());
        Res r = place(key, order("MADE_TO_ORDER", v, 1), nextIp());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("CONFLICT");
        assertThat(stock(v)).isEqualTo(4);
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void idempotency_sameKeyDifferentEmail_conflict() throws Exception {
        long v = stockVariant(1_000, 5);
        UUID key = UUID.randomUUID();
        place(key, order("IN_STOCK", v, 1), nextIp());
        Map<String, Object> other = order("IN_STOCK", v, 1);
        sub(other, "customer").put("email", "other@example.com");
        Res r = place(key, other, nextIp());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("CONFLICT");
        assertThat(stock(v)).isEqualTo(4);
    }

    @Test
    void idempotency_sameKeyEmailDifferentCase_isStillAReplay() throws Exception {
        long v = stockVariant(1_000, 5);
        UUID key = UUID.randomUUID();
        place(key, order("IN_STOCK", v, 1), nextIp());
        Map<String, Object> same = order("IN_STOCK", v, 1);
        sub(same, "customer").put("email", " JANE.SECRET@example.com ");
        assertThat(place(key, same, nextIp()).status()).isEqualTo(200);
    }

    @Test
    void idempotencyKey_missing_400() throws Exception {
        long v = stockVariant(1_000, 5);
        raw(null, om.writeValueAsString(order("IN_STOCK", v, 1))).andExpect(status().isBadRequest());
        assertThat(orders()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"not-a-uuid", "", "12345678-1234-1234-1234-12345678901z", "123456781234123412341234567890123456"
            })
    void idempotencyKey_invalid_400(String bad) throws Exception {
        long v = stockVariant(1_000, 5);
        Res r = send(req(null, om.writeValueAsString(order("IN_STOCK", v, 1)), nextIp(), true)
                .header("Idempotency-Key", bad));
        assertThat(r.status()).isEqualTo(400);
        assertThat(orders()).isZero();
        assertThat(stock(v)).isEqualTo(5);
    }

    // ---- rollback ----

    @Test
    void checkout_secondLineOutOfStock_rollsBackFirstLineAndCreatesNoOrder() throws Exception {
        long a = stockVariant(1_000, 5);
        long b = stockVariant(2_000, 0);
        int versionA = version(a);
        Res r = place(order("IN_STOCK", a, 2, b, 1));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("OUT_OF_STOCK");
        assertThat(stock(a)).isEqualTo(5);
        assertThat(version(a)).isEqualTo(versionA);
        assertThat(orders()).isZero();
        assertThat(count("SELECT count(*) FROM order_item")).isZero();
    }

    @Test
    void checkout_totalOverflow_400AndNothingReserved() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 1_000_000_000, 10, false, null);
        Res r = place(order("IN_STOCK", v, 5));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.<String>json("$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(stock(v)).isEqualTo(10);
        assertThat(orders()).isZero();
    }

    // ---- not found / not orderable ----

    @Test
    void checkout_unknownVariant_404() throws Exception {
        assertThat(place(order("IN_STOCK", 999_999_999L, 1)).status()).isEqualTo(404);
    }

    @Test
    void checkout_inactiveVariant_404() throws Exception {
        long v = stockVariant(1_000, 5);
        jdbc.update("UPDATE product_variant SET active = false WHERE id = ?", v);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(404);
        assertThat(stock(v)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ARCHIVED"})
    void checkout_nonSellableProduct_404(String productStatus) throws Exception {
        long v = variant(product(productStatus), "1/7", 1_000, 5, false, null);
        Res r = place(order("IN_STOCK", v, 1));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.<String>json("$.code")).isEqualTo("NOT_FOUND");
        assertThat(stock(v)).isEqualTo(5);
    }

    @Test
    void checkout_madeToOrderOnVariantWithoutMto_outOfStock() throws Exception {
        long v = stockVariant(1_000, 5);
        Res r = place(order("MADE_TO_ORDER", v, 1));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void checkout_madeToOrderQuotaTooSmall_outOfStockAndQuotaUnchanged() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 1_000, 0, true, 1);
        Res r = place(order("MADE_TO_ORDER", v, 2));
        assertThat(r.status()).isEqualTo(409);
        assertThat(quota(v)).isEqualTo(1);
    }

    // ---- validation ----

    private void assertInvalid(Consumer<Map<String, Object>> mutate) throws Exception {
        long v = stockVariant(1_000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        mutate.accept(body);
        Res r = place(body);
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.<String>json("$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(stock(v)).isEqualTo(5);
        assertThat(orders()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VN", "sg", "SGP", "", "S", "au"})
    void checkout_countryOutsideSgAu_400(String country) throws Exception {
        assertInvalid(b -> sub(b, "shippingAddress").put("country", country));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SG", "AU"})
    void checkout_countrySgAndAu_ok(String country) throws Exception {
        long v = stockVariant(1_000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        sub(body, "shippingAddress").put("country", country);
        assertThat(place(body).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT ship_country FROM customer_order", String.class))
                .isEqualTo(country);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    void checkout_quantityOutOfRange_400(int qty) throws Exception {
        assertInvalid(b -> items(b).get(0).put("quantity", qty));
    }

    @Test
    void checkout_moreThanTenLines_400() throws Exception {
        assertInvalid(b -> {
            for (int i = 0; i < 10; i++) {
                items(b).add(new java.util.LinkedHashMap<>(Map.of("variantId", 1000L + i, "quantity", 1)));
            }
        });
    }

    @Test
    void checkout_exactlyTenLines_isNotRejectedByTheLimit() throws Exception {
        List<Object> args = new ArrayList<>();
        long first = 0;
        for (int i = 0; i < 10; i++) {
            long v = stockVariant(1_000, 5);
            first = v;
            args.add(v);
            args.add(1);
        }
        Res r = place(order("IN_STOCK", args.toArray()));
        assertThat(r.status()).isEqualTo(201);
        assertThat(stock(first)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM order_item")).isEqualTo(10);
    }

    @Test
    void checkout_duplicateVariantId_400() throws Exception {
        assertInvalid(b -> items(b).add(new java.util.LinkedHashMap<>(items(b).get(0))));
    }

    @Test
    void checkout_emptyItems_400() throws Exception {
        assertInvalid(b -> items(b).clear());
    }

    @Test
    void checkout_nullItems_400() throws Exception {
        assertInvalid(b -> b.remove("items"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nope", "a@b", "@b.com", "a b@c.com", "a@@b.com", ""})
    void checkout_badEmail_400(String email) throws Exception {
        assertInvalid(b -> sub(b, "customer").put("email", email));
    }

    @Test
    void checkout_nameOver120_400() throws Exception {
        assertInvalid(b -> sub(b, "customer").put("name", "x".repeat(121)));
    }

    @Test
    void checkout_nameExactly120_ok() throws Exception {
        long v = stockVariant(1_000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        sub(body, "customer").put("name", "x".repeat(120));
        assertThat(place(body).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\nb", "a\rb", "a\tb", "a\u001Bb"})
    void checkout_controlCharsInText_400(String bad) throws Exception {
        assertInvalid(b -> sub(b, "customer").put("name", bad));
        assertInvalid(b -> sub(b, "shippingAddress").put("line1", bad));
        assertInvalid(b -> sub(b, "shippingAddress").put("city", bad));
    }

    @Test
    void checkout_nulInEmail_400Not500() throws Exception {
        assertInvalid(b -> sub(b, "customer").put("email", "a b@example.com"));
    }

    @Test
    void checkout_bidiOverrideInName_400() throws Exception {
        assertInvalid(b -> sub(b, "customer").put("name", "Jane ‮eod"));
    }

    @Test
    void checkout_lineSeparatorInAddress_400() throws Exception {
        assertInvalid(b -> sub(b, "shippingAddress").put("line1", "1 Road Evil"));
    }

    @Test
    void checkout_formatCharInTermsVersion_400() throws Exception {
        assertInvalid(b -> b.put("acceptedTermsVersion", "2030-01-01​"));
    }

    @Test
    void checkout_unknownEnumValue_400() throws Exception {
        assertInvalid(b -> b.put("fulfillmentType", "COMMISSION"));
    }

    @Test
    void checkout_missingFulfillmentType_400() throws Exception {
        assertInvalid(b -> b.remove("fulfillmentType"));
    }

    @Test
    void checkout_malformedJson_400() throws Exception {
        raw(UUID.randomUUID(), "{not json").andExpect(status().isBadRequest());
    }

    @Test
    void checkout_blankAddressLine_400() throws Exception {
        assertInvalid(b -> sub(b, "shippingAddress").put("line1", "   "));
    }

    // ---- terms ----

    @Test
    void checkout_noTermsPublished_termsOutdated() throws Exception {
        jdbc.execute("TRUNCATE terms_document CASCADE");
        long v = stockVariant(1_000, 5);
        Res r = place(order("IN_STOCK", v, 1));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("TERMS_OUTDATED");
        assertThat(stock(v)).isEqualTo(5);
    }

    @Test
    void checkout_oldTermsVersion_termsOutdated() throws Exception {
        publishTerms("2030-02-01", clock.instant().plusSeconds(3600));
        clock.advance(java.time.Duration.ofHours(2));
        long v = stockVariant(1_000, 5);
        Res stale = place(order("IN_STOCK", v, 1));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.<String>json("$.code")).isEqualTo("TERMS_OUTDATED");

        Map<String, Object> current = order("IN_STOCK", v, 1);
        current.put("acceptedTermsVersion", "2030-02-01");
        assertThat(place(current).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT terms_version FROM customer_order", String.class))
                .isEqualTo("2030-02-01");
    }

    @Test
    void checkout_unknownTermsVersion_termsOutdated() throws Exception {
        long v = stockVariant(1_000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        body.put("acceptedTermsVersion", "1999-01-01");
        assertThat(place(body).<String>json("$.code")).isEqualTo("TERMS_OUTDATED");
    }

    @Test
    void checkout_afterNewerTermsPublished_previouslyValidVersionFails() throws Exception {
        long v = stockVariant(1_000, 5);
        assertThat(place(order("IN_STOCK", v, 1)).status()).isEqualTo(201);
        clock.advance(java.time.Duration.ofHours(1));
        publishTerms("2030-03-01");
        Res r = place(order("IN_STOCK", v, 1));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.<String>json("$.code")).isEqualTo("TERMS_OUTDATED");
    }

    // ---- csrf ----

    @Test
    void checkout_withoutCsrfToken_403() throws Exception {
        long v = stockVariant(1_000, 5);
        mvc.perform(req(UUID.randomUUID(), om.writeValueAsString(order("IN_STOCK", v, 1)), nextIp(), false))
                .andExpect(status().isForbidden());
        assertThat(orders()).isZero();
        assertThat(stock(v)).isEqualTo(5);
    }

    @Test
    void checkout_anonymousWithCsrf_noLoginNeeded() throws Exception {
        long v = stockVariant(1_000, 5);
        raw(UUID.randomUUID(), om.writeValueAsString(order("IN_STOCK", v, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }
}
