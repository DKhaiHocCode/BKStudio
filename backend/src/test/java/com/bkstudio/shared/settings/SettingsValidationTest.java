package com.bkstudio.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.shared.settings.api.SettingsDtos;
import com.bkstudio.shared.settings.api.SocialUrl;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsValidationTest {

    private static final Pattern HOST = Pattern.compile(SettingsDtos.HOSTNAME_REGEX);
    private static final Pattern VERSION = Pattern.compile(SettingsDtos.TERMS_VERSION_REGEX);

    private static boolean reddit(String url) {
        return SocialUrl.Validator.matches(url, "reddit.com");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://reddit.com/r/bkstudio",
                "https://www.reddit.com/user/x",
                "https://old.reddit.com/r/x",
                "https://WWW.REDDIT.COM/r/x",
                "https://a.b.reddit.com"
            })
    void social_acceptsOwnDomainAndSubdomains(String url) {
        assertThat(reddit(url)).isTrue();
    }

    /** BUG (T-020): scheme comparison is case-sensitive, but URL schemes are case-insensitive (RFC 3986 3.1). */
    @Test
    void social_uppercaseScheme_isAccepted() {
        assertThat(reddit("HTTPS://www.reddit.com/r/x")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://reddit.com/r/x",
                "https://evilreddit.com",
                "https://reddit.com.evil.com",
                "https://user@reddit.com",
                "https://user:pw@reddit.com",
                "javascript:alert(1)",
                "data:text/html,hi",
                "https://reddit.com/a b",
                "https://reddit.com/a\tb",
                "https://reddit.com/a\nb",
                "https://reddit.com/a\rb",
                "https://reddit.com/a\u0000b",
                "https://reddit.com\\@evil.com",
                "https://reddit.com\\evil.com",
                "https://reddit.com:evil",
                "https://reddit.com:443/r/x",
                "https://reddit.com:8443",
                "//reddit.com/r/x",
                "reddit.com",
                "https://",
                "https://notreddit.com",
                "https://facebook.com/x"
            })
    void social_rejectsEverythingElse(String url) {
        assertThat(reddit(url)).isFalse();
    }

    @Test
    void social_length300ok_301rejected() {
        String base = "https://reddit.com/";
        assertThat(reddit(base + "a".repeat(300 - base.length()))).isTrue();
        assertThat(reddit(base + "a".repeat(301 - base.length()))).isFalse();
    }

    @Test
    void social_xAcceptsXAndTwitterOnly() {
        assertThat(SocialUrl.Validator.matches("https://x.com/u", "x.com", "twitter.com"))
                .isTrue();
        assertThat(SocialUrl.Validator.matches("https://twitter.com/u", "x.com", "twitter.com"))
                .isTrue();
        assertThat(SocialUrl.Validator.matches("https://mobile.twitter.com/u", "x.com", "twitter.com"))
                .isTrue();
        assertThat(SocialUrl.Validator.matches("https://notx.com/u", "x.com", "twitter.com"))
                .isFalse();
        assertThat(SocialUrl.Validator.matches("https://reddit.com/u", "x.com", "twitter.com"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "a.b.example.co", "xn--abc.com", "a-b.com", "1.2.com", "shop.artistree.com"})
    void hostname_accepts(String h) {
        assertThat(HOST.matcher(h).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Example.com",
                "https://example.com",
                "example.com/path",
                "example.com:443",
                "*.example.com",
                "localhost",
                "-a.com",
                "a-.com",
                "a.-b.com",
                "a.com-",
                "a..com",
                ".a.com",
                "a.com.",
                "a b.com",
                "",
                "a_b.com"
            })
    void hostname_rejects(String h) {
        assertThat(HOST.matcher(h).matches()).isFalse();
    }

    @Test
    void hostname_labelLengthAndTotalLength() {
        assertThat(HOST.matcher("a".repeat(63) + ".com").matches()).isTrue();
        assertThat(HOST.matcher("a".repeat(64) + ".com").matches()).isFalse();
        String l = "a".repeat(61);
        String h251 = l + "." + l + "." + l + "." + l + ".com";
        assertThat(h251).hasSize(251);
        assertThat(HOST.matcher(h251).matches()).isTrue();
        assertThat(HOST.matcher("aa" + h251).matches()).isTrue(); // 253 chars, first label 63
        assertThat(HOST.matcher("a.aa" + h251).matches()).isFalse(); // 255 chars
        assertThat(HOST.matcher("a." + h251 + "b").matches()).isFalse(); // 254 chars, labels fine
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01", "2026-11-01-1", "2026-11-01-999", "2026-13-45"})
    void termsVersion_formatAccepts(String v) {
        // 2026-13-45 passes the shape; the calendar check lives in the service
        assertThat(VERSION.matcher(v).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "abcd",
                "2026-1-01",
                "2026-11-01-1234",
                "2026-11-01-",
                "v2026-11-01",
                " 2026-11-01",
                "2026-11-01\n"
            })
    void termsVersion_formatRejects(String v) {
        assertThat(VERSION.matcher(v).matches()).isFalse();
    }
}
