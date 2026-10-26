package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.bkstudio.catalog.admin.infra.AdminProductRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The audit row and the business change share one transaction. Each test lets the write and the audit insert run,
 * then makes the NEXT repository call fail, so the whole request must roll back (audit row included).
 */
class AdminProductAuditRollbackIT extends AdminProductTestBase {

    @MockitoSpyBean
    AdminProductRepository repo;

    private static final IllegalStateException BOOM = new IllegalStateException("boom after audit");

    @Test
    void createProduct_failureAfterAuditInsert_rollsBackProductAndAudit() throws Exception {
        String name = "Rollback create " + token();
        int audits = auditRows();
        doThrow(BOOM).when(repo).findProduct(anyLong()); // create reads the product back after auditing

        expectProblem(post(PRODUCTS, j("name", name)), 500, "INTERNAL_ERROR");

        verify(repo)
                .insertProduct(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(name),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()); // the insert did run
        assertThat(count("SELECT count(*) FROM product WHERE name = ?", name)).isZero();
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void updateProduct_failureAfterAuditInsert_rollsBackUpdateAndAudit() throws Exception {
        String name = "Rollback update " + token();
        long pid = createProduct(name);
        int audits = auditRows();
        // 1st call: the "before" read; 2nd call: the read-back after the audit insert
        doCallRealMethod().doThrow(BOOM).when(repo).findProduct(anyLong());

        expectProblem(
                put(PRODUCTS + "/" + pid, j("name", "Changed", "status", "ACTIVE", "version", 0)),
                500,
                "INTERNAL_ERROR");

        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid))
                .isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, pid))
                .isEqualTo("DRAFT");
        assertThat(count("SELECT version FROM product WHERE id = ?", pid)).isZero();
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void reorderImages_failureAfterAuditInsert_rollsBackOrderAndAudit() throws Exception {
        long pid = createProduct("Rollback reorder " + token());
        long a = insertImageRow(pid, randomImageKey(), 0);
        long b = insertImageRow(pid, randomImageKey(), 1);
        int audits = auditRows();
        doCallRealMethod().doThrow(BOOM).when(repo).listImages(anyLong());

        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", List.of(b, a))), 500, "INTERNAL_ERROR");

        assertThat(count("SELECT sort_order FROM product_image WHERE id = ?", a))
                .isZero();
        assertThat(count("SELECT sort_order FROM product_image WHERE id = ?", b))
                .isEqualTo(1);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void updateAlt_failureRightAfterTheUpdate_rollsBackAltAndAudit() throws Exception {
        long pid = createProduct("Rollback alt " + token());
        String key = randomImageKey();
        long img = insertImageRow(pid, key, 0);
        int audits = auditRows();
        org.mockito.Mockito.doAnswer(inv -> {
                    inv.callRealMethod(); // the UPDATE really runs, then the request blows up
                    throw BOOM;
                })
                .when(repo)
                .updateAlt(anyLong(), org.mockito.ArgumentMatchers.any());

        expectProblem(
                call(HttpMethod.PATCH, "/api/admin/images/" + img, j("altText", "changed")), 500, "INTERNAL_ERROR");

        assertThat(jdbc.queryForObject("SELECT alt_text FROM product_image WHERE id = ?", String.class, img))
                .isEqualTo("alt " + key);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void afterARolledBackRequest_theNextRequestStillWorks() throws Exception {
        doThrow(BOOM).when(repo).findProduct(anyLong());
        expectProblem(post(PRODUCTS, j("name", "First " + token())), 500, "INTERNAL_ERROR");
        org.mockito.Mockito.reset(repo);

        post(PRODUCTS, j("name", "Second " + token()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isCreated());

        assertThat(count("SELECT count(*) FROM product")).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }
}
