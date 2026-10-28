package com.bkstudio.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.order.state.OrderAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class EmailTemplatesTest {

    private static final List<String> ALL = List.of(
            "ORDER_RECEIVED",
            "ORDER_PAYMENT_LINK",
            "ORDER_DEPOSIT_CONFIRMED",
            "ORDER_PRODUCTION_STARTED",
            "ORDER_BALANCE_REQUESTED",
            "ORDER_PAID",
            "ORDER_SHIPPED",
            "ORDER_COMPLETED",
            "ORDER_CANCELLED",
            "ORDER_FORFEITED",
            "ORDER_REMINDER",
            "ORDER_LOOKUP_LINK");

    private static final String PAY = "https://pay.example/x";
    private static final List<String> LINK_REQUIRED =
            List.of("ORDER_RECEIVED", "ORDER_PAYMENT_LINK", "ORDER_LOOKUP_LINK");

    private final EmailTemplates dev = new EmailTemplates(new MockEnvironment());
    private final EmailTemplates prod =
            new EmailTemplates(new MockEnvironment().withProperty("spring.profiles.active", "prod"));

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void render_everyTemplate_hasNonEmptySubjectHtmlText() {
        Map<String, Object> p = payload(
                "publicCode", "BK-ABC123",
                "status", "IN_PRODUCTION",
                "amountCents", 12345,
                "dueAt", "2030-01-01T00:00:10Z",
                "orderUrl", "https://shop.example/orders/tok",
                "paymentUrl", "https://pay.example/x");
        for (String t : ALL) {
            EmailTemplates.Rendered r = dev.render(t, p);
            assertThat(r.subject()).as(t).isNotBlank();
            assertThat(r.html()).as(t).isNotBlank();
            assertThat(r.text()).as(t).isNotBlank();
        }
    }

    @Test
    void render_everyOrderActionTemplate_isSupported() {
        for (OrderAction a : OrderAction.values()) {
            assertThat(ALL).as(a.name()).contains(a.emailTemplate());
            assertThatCode(() -> dev.render(a.emailTemplate(), payload("publicCode", "BK-1", "status", "PAID")))
                    .as(a.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void render_missingOptionalFields_doesNotThrow() {
        for (String t : ALL.stream().filter(t -> !LINK_REQUIRED.contains(t)).toList()) {
            assertThatCode(() -> dev.render(t, Map.of())).as(t).doesNotThrowAnyException();
            assertThatCode(() -> dev.render(t, payload("publicCode", null, "amountCents", null, "dueAt", null)))
                    .as(t)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void render_unknownTemplate_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> dev.render("NOPE", Map.of()));
    }

    @Test
    void render_htmlSpecialCharsInValues_areEscaped() {
        String evil = "<script>alert(\"x\")</script>'";
        EmailTemplates.Rendered r = dev.render("ORDER_DEPOSIT_CONFIRMED", payload("publicCode", evil, "status", evil));
        assertThat(r.html()).doesNotContain("<script>").doesNotContain("alert(\"x\")");
        assertThat(r.html()).contains("&lt;script&gt;").contains("&quot;").contains("&#39;");
    }

    @Test
    void render_controlCharsInValues_areStripped() {
        EmailTemplates.Rendered r = dev.render(
                "ORDER_RECEIVED",
                payload("publicCode", "BK-1\r\nBcc: evil@x.com\u0000\t", "orderUrl", "https://s.example/o/t"));
        for (String s : List.of(r.subject(), r.html(), r.text())) {
            assertThat(s).doesNotContain("\r").doesNotContain("\u0000");
        }
        assertThat(r.subject()).doesNotContain("\n").doesNotContain("\t");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "javascript:alert(1)",
                "http://evil.example/x",
                "data:text/html;base64,AAAA",
                "//evil.example/x",
                "https://",
                "https://a.example/\"onmouseover=\"x",
                "https://a.example/<b>",
                "https://a.example/ x",
                "HTTPS://a.example/x"
            })
    void render_unsafeLink_onRequiredLinkTemplate_throwsMissingLink(String url) {
        assertThatThrownBy(() -> prod.render("ORDER_RECEIVED", payload("publicCode", "BK-1", "orderUrl", url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing link");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://evil.example/x", "https://a.example/ x"})
    void render_unsafeLink_onOptionalLinkTemplate_isLeftOut(String url) {
        for (String t : List.of("ORDER_BALANCE_REQUESTED", "ORDER_REMINDER")) {
            EmailTemplates.Rendered r = prod.render(t, payload("publicCode", "BK-1", "paymentUrl", url));
            assertThat(r.html()).doesNotContain("href").doesNotContain(url);
            assertThat(r.text()).doesNotContain(url);
        }
    }

    @Test
    void render_requiredLinkTemplates_withoutLink_throwMissingLink() {
        for (String t : LINK_REQUIRED) {
            assertThatThrownBy(() -> dev.render(t, payload("publicCode", "BK-1")))
                    .as(t)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Missing link");
        }
    }

    @Test
    void render_httpsLink_isPresentAndEscapedInHtml() {
        String url = "https://shop.example/orders/tok?a=1&b=2";
        EmailTemplates.Rendered r = prod.render("ORDER_RECEIVED", payload("publicCode", "BK-1", "orderUrl", url));
        assertThat(r.html()).contains("href=\"https://shop.example/orders/tok?a=1&amp;b=2\"");
        assertThat(r.text()).contains(url);
    }

    @Test
    void render_paymentTemplate_usesPaymentUrl() {
        EmailTemplates.Rendered r =
                dev.render("ORDER_PAYMENT_LINK", payload("publicCode", "BK-1", "paymentUrl", "https://pay.example/p"));
        assertThat(r.html()).contains("href=\"https://pay.example/p\"");
    }

    @Test
    void render_localhostLink_allowedOutsideProdOnly() {
        Map<String, Object> p = payload("publicCode", "BK-1", "orderUrl", "http://localhost:3000/orders/tok");
        assertThat(dev.render("ORDER_RECEIVED", p).html()).contains("href=\"http://localhost:3000/orders/tok\"");
        assertThatThrownBy(() -> prod.render("ORDER_RECEIVED", p)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void render_amountCents_formattedAsUsd() {
        assertThat(dev.render("ORDER_PAYMENT_LINK", payload("amountCents", 12345, "paymentUrl", PAY))
                        .text())
                .contains("USD 123.45");
        assertThat(dev.render("ORDER_PAYMENT_LINK", payload("amountCents", 5, "paymentUrl", PAY))
                        .text())
                .contains("USD 0.05");
        assertThat(dev.render("ORDER_REMINDER", payload("amountCents", 10000L)).text())
                .contains("USD 100.00");
    }

    @Test
    void render_dueAt_formattedInUtcOrKeptWhenUnparseable() {
        assertThat(dev.render("ORDER_PAYMENT_LINK", payload("dueAt", "2030-01-01T00:00:10Z", "paymentUrl", PAY))
                        .text())
                .contains("1 Jan 2030 00:00 UTC");
        assertThat(dev.render("ORDER_PAYMENT_LINK", payload("dueAt", "soon", "paymentUrl", PAY))
                        .text())
                .contains("Please pay by: soon");
    }
}
