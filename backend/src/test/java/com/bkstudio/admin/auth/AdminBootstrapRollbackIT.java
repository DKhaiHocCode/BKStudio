package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.bkstudio.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// Test-only key (32 zero bytes, base64); not a secret.
@TestPropertySource(properties = "app.admin.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AdminBootstrapRollbackIT extends AbstractPostgresTest {

    @Autowired
    AdminBootstrap bootstrap;

    @Autowired
    AdminUserRepository admins;

    @MockitoSpyBean
    RecoveryCodeRepository recoveryCodes;

    @Test
    void whenStoringRecoveryCodesFails_noAdminRowRemains() {
        doThrow(new IllegalStateException("boom")).when(recoveryCodes).insertAll(anyLong(), anyList());

        assertThatThrownBy(() -> bootstrap.create("rollback@example.com", "a-long-enough-password"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        assertThat(admins.findByEmail("rollback@example.com")).isEmpty();
    }
}
