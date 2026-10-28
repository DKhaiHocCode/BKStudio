package com.bkstudio.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotificationConfigValidatorTest {

    private static NotificationProperties props(String from, String key) {
        return new NotificationProperties(from, key, new NotificationProperties.Outbox(false));
    }

    @ParameterizedTest
    @CsvSource(
            value = {"NIL,k", "'',k", "' ',k", "BK <a@b.c>,NIL", "BK <a@b.c>,''", "BK <a@b.c>,' '", "NIL,NIL"},
            nullValues = "NIL")
    void init_blankKeyOrFrom_failsStartup(String from, String key) {
        var v = new NotificationConfigValidator(props(from, key));
        assertThatIllegalStateException().isThrownBy(v::init);
    }

    @Test
    void init_bothSet_ok() {
        var v = new NotificationConfigValidator(props("BK <a@b.c>", "re_key"));
        assertThatCode(v::init).doesNotThrowAnyException();
    }

    @Test
    void nonProdProfile_blankConfig_contextStartsWithoutValidator() {
        new ApplicationContextRunner()
                .withBean(NotificationProperties.class, () -> props("", ""))
                .withUserConfiguration(NotificationConfigValidator.class)
                .withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(NotificationConfigValidator.class));
    }

    @Test
    void prodProfile_blankConfig_contextFails() {
        new ApplicationContextRunner()
                .withBean(NotificationProperties.class, () -> props("", ""))
                .withUserConfiguration(NotificationConfigValidator.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
