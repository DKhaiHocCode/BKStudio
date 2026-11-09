package com.bkstudio.shared.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProxyConfigValidatorTest {

    private static AnnotationConfigApplicationContext ctx(String profile, String strategy, String proxies) {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().setActiveProfiles(profile);
        c.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "t",
                        Map.of(
                                "server.forward-headers-strategy", strategy,
                                "server.tomcat.remoteip.internal-proxies", proxies)));
        c.register(ProxyConfigValidator.class);
        return c;
    }

    @Test
    void prod_nativeWithProxies_ok() {
        try (var c = ctx("prod", "native", "10.0.0.1")) {
            assertThatCode(c::refresh).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @CsvSource({"native,' '", "none,'10.0.0.1'", "framework,'10.0.0.1'", "none,' '"})
    void prod_badCombination_failsStartup(String strategy, String proxies) {
        try (var c = ctx("prod", strategy, proxies)) {
            assertThatThrownBy(c::refresh).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void nonProd_unaffected() {
        try (var c = ctx("dev", "none", "")) {
            assertThatCode(c::refresh).doesNotThrowAnyException();
        }
    }
}
