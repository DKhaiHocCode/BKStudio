package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The setting/terms change and its audit row share one transaction. A temporary CHECK on audit_log makes the audit
 * insert fail after the business rows were written; everything must roll back.
 */
class SettingsAuditRollbackIT extends SettingsTestBase {

    private static final String BLOCK = "chk_test_block_audit";

    private void blockAudit(String action) {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT " + BLOCK + " CHECK (action <> '" + action + "') NOT VALID");
    }

    @AfterEach
    void unblockAudit() {
        jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS " + BLOCK);
    }

    @Test
    void updateSettings_auditInsertFails_settingsRolledBack() throws Exception {
        int audits = auditRows();
        blockAudit("SETTINGS_UPDATED");

        expectProblem(putSettings(9, List.of("b.example.com"), Map.of("x", "https://x.com/y")), 500, "INTERNAL_ERROR");

        assertThat(settingRows()).isZero(); // the upserts ran before the audit insert and were rolled back
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void publishTerms_auditInsertFails_termsRowRolledBack() throws Exception {
        blockAudit("TERMS_PUBLISHED");

        expectProblem(post(TERMS, j("version", "2030-01-01", "contentMd", "x")), 500, "INTERNAL_ERROR");

        assertThat(count("SELECT count(*) FROM terms_document")).isZero();
    }
}
