package com.bkstudio.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailSendExceptionTest {

    @Test
    void message_emailAddressesAreRedacted() {
        var e = new EmailSendException("rejected jane.doe+x@example.com and <bob@mail.example.org>, retry", true);
        assertThat(e.getMessage()).doesNotContain("jane").doesNotContain("bob").doesNotContain("@");
        assertThat(e.getMessage()).contains("[redacted]").contains("retry");
        assertThat(e.transientFailure()).isTrue();
    }

    @Test
    void message_isTruncatedTo200() {
        assertThat(new EmailSendException("x".repeat(500), false).getMessage()).hasSize(200);
    }

    @Test
    void safe_nullIsEmpty_andShortTextUnchanged() {
        assertThat(EmailSendException.safe(null)).isEmpty();
        assertThat(EmailSendException.safe("Resend HTTP 500 x")).isEqualTo("Resend HTTP 500 x");
    }
}
