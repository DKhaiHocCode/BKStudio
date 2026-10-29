package com.bkstudio.admin.auth;

import com.bkstudio.order.jobs.OrderJobs;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

/** T-027 fixtures: PENDING payment requests with an explicit due_at, relative to the test clock (2030-01-01). */
abstract class OrderJobsTestBase extends AdminOrderTestBase {

    @Autowired
    OrderJobs jobs;

    long dueRequest(long orderId, String type, Instant dueAt) {
        long id = pendingRequest(orderId, type);
        jdbc.update("UPDATE payment_request SET due_at = ? WHERE id = ?", Timestamp.from(dueAt), id);
        return id;
    }

    long overdue(long orderId, String type) {
        return dueRequest(orderId, type, clock.instant().minusSeconds(60));
    }

    long notYetDue(long orderId, String type) {
        return dueRequest(orderId, type, clock.instant().plusSeconds(3600));
    }

    int outboxTotal() {
        return count("SELECT count(*) FROM email_outbox");
    }

    int auditTotal() {
        return count("SELECT count(*) FROM audit_log");
    }

    int reminders(long orderId, String mark) {
        return count(
                "SELECT count(*) FROM email_outbox WHERE template = 'ORDER_REMINDER' AND dedupe_key LIKE ?",
                "order:" + orderId + ":pr:%:balance-reminder-" + mark);
    }

    int reminders(long orderId, long requestId, String mark) {
        return count(
                "SELECT count(*) FROM email_outbox WHERE template = 'ORDER_REMINDER' AND dedupe_key = ?",
                "order:" + orderId + ":pr:" + requestId + ":balance-reminder-" + mark);
    }
}
