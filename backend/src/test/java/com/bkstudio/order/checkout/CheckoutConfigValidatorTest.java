package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bkstudio.order.checkout.domain.CheckoutProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CheckoutConfigValidatorTest {

    private static void validate(String baseUrl) throws Exception {
        Class<?> c = Class.forName("com.bkstudio.order.checkout.domain.CheckoutConfigValidator");
        Constructor<?> ctor = c.getDeclaredConstructor(CheckoutProperties.class);
        ctor.setAccessible(true);
        Object v = ctor.newInstance(new CheckoutProperties(baseUrl, 5, Duration.ofHours(1)));
        Method init = c.getDeclaredMethod("init");
        init.setAccessible(true);
        try {
            init.invoke(v);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "http://bkstudio.xxx", "ftp://x", "bkstudio.xxx"})
    void init_blankOrNonHttps_failsStartup(String url) {
        assertThatIllegalStateException().isThrownBy(() -> validate(url));
    }

    @Test
    void init_https_ok() {
        assertThatCode(() -> validate("https://bkstudio.xxx")).doesNotThrowAnyException();
    }
}
