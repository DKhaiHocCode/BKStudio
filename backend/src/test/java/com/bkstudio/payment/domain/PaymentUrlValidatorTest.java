package com.bkstudio.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.shared.web.ApiException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentUrlValidatorTest {

    private static final List<String> ALLOWED = List.of("artistree.io", "shopee.sg");

    private static void assertRejectedOnPaymentUrl(String url, List<String> allowed) {
        assertThatThrownBy(() -> PaymentUrlValidator.validate(url, allowed))
                .as(url)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.field()).isEqualTo("paymentUrl"));
    }

    @Test
    void acceptsExactAndSubdomainHosts() {
        for (String url : List.of(
                "https://artistree.io/pay/1",
                "https://ARTISTREE.io/pay/1",
                "https://shop.artistree.io/pay?a=b#c",
                "https://shopee.sg")) {
            assertThatCode(() -> PaymentUrlValidator.validate(url, ALLOWED)).doesNotThrowAnyException();
        }
    }

    @Test
    void allowListEntryIsTrimmedAndLowercased() {
        assertThatCode(() -> PaymentUrlValidator.validate("https://artistree.io/x", List.of(" Artistree.io ", " ")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSubdomainMixedCaseAndTrailingPathQuery() {
        for (String url : List.of(
                "https://a.artistree.io",
                "https://a.b.artistree.io/x",
                "https://ArtisTree.IO/Pay/1?x=1&y=2#frag",
                "https://artistree.io/" + "a".repeat(479))) { // exactly 500 characters
            assertThatCode(() -> PaymentUrlValidator.validate(url, ALLOWED)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsBadLinks() {
        for (String url : List.of(
                "http://artistree.io/x",
                "HTTPS://artistree.io/x",
                "https://user@artistree.io/x",
                "https://artistree.io:8443/x",
                "https://evilartistree.io/x",
                "https://artistree.io.evil.com/x",
                "https://evil.com/?u=artistree.io",
                "https://artistree.io./x",
                "https://127.0.0.1/x",
                "https://[::1]/x",
                "https://artistree.io/a b",
                "https://artistree.io/a\nb",
                "https:///x",
                "https://artistree.io/" + "a".repeat(500),
                "",
                "javascript:alert(1)")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
        }
    }

    @Test
    void rejectsMissingOrWrongScheme() {
        for (String url : List.of(
                "artistree.io/pay/1",
                "//artistree.io/pay/1",
                "ftp://artistree.io/x",
                "data:text/html,<script>alert(1)</script>",
                "javascript://artistree.io/%0Aalert(1)",
                "mailto:a@artistree.io")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
        }
    }

    @Test
    void rejectsUserInfoPortAndLookalikeHosts() {
        for (String url : List.of(
                "https://artistree.io@evil.com/x",
                "https://evil.com@artistree.io/x",
                "https://artistree.io:pw@artistree.io/x",
                "https://artistree.io:443/x",
                "https://evil-artistree.io/x",
                "https://evilartistree.io/x",
                "https://artistree.io.evil.com/x",
                "https://artistree.io%2eevil.com/x")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
        }
    }

    @Test
    void rejectsIpLiteralsEvenWhenListed() {
        for (String url : List.of(
                "https://1.2.3.4/x",
                "https://127.0.0.1/x",
                "https://2130706433/x",
                "https://[::1]/x",
                "https://[2001:db8::1]/x")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
            assertRejectedOnPaymentUrl(url, List.of("1.2.3.4", "127.0.0.1", "2130706433"));
        }
    }

    @Test
    void rejectsEmptyBlankOversizeAndControlOrWhitespace() {
        for (String url : List.of(
                "",
                " ",
                "   \t",
                "https://artistree.io/" + "a".repeat(480), // 501 characters
                " https://artistree.io/x",
                "https://artistree.io/x ",
                "https://artistree.io/a\tb",
                "https://artistree.io/a\rb",
                "https://artistree.io/a\u0000b",
                "https://artistree.io/a\u007fb",
                "https://artistree.io/a b", // no-break space
                "https://artistree.io/a b")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
        }
        assertThatThrownBy(() -> PaymentUrlValidator.validate(null, ALLOWED)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnicodeLookalikesAndIdn() {
        for (String url : List.of(
                "https://аrtistree.io/x", // Cyrillic a
                "https://artistree.iо/x", // Cyrillic o
                "https://artistree。io/x", // ideographic full stop
                "https://ａrtistree.io/x", // fullwidth a
                "https://xn--rtistree-d9d.io/x", // punycode form, not in the list
                "https://münchen.de/x")) {
            assertRejectedOnPaymentUrl(url, ALLOWED);
        }
    }

    @Test
    void emptyAllowListRejectsEverything() {
        assertThatThrownBy(() -> PaymentUrlValidator.validate("https://artistree.io/x", List.of()))
                .isInstanceOf(ApiException.class);
        assertRejectedOnPaymentUrl("https://artistree.io/x", List.of(""));
        assertRejectedOnPaymentUrl("https://artistree.io/x", Arrays.asList((String) null));
    }

    /** Documents the known limit: an entry matches the exact host or "." + entry, with no public-suffix list. */
    @Test
    void publicSuffixEntry_allowsEverySubdomain_butNotLookalikes() {
        List<String> suffix = List.of("com.sg");
        assertThatCode(() -> PaymentUrlValidator.validate("https://com.sg/x", suffix))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaymentUrlValidator.validate("https://anyone.com.sg/x", suffix))
                .doesNotThrowAnyException();
        assertRejectedOnPaymentUrl("https://evilcom.sg/x", suffix);
        assertRejectedOnPaymentUrl("https://sg/x", suffix);
        assertRejectedOnPaymentUrl("https://com.sg.evil.com/x", suffix);
    }
}
