package com.bkstudio.queue;

import com.bkstudio.queue.domain.QueueService;
import com.bkstudio.support.AbstractPostgresTest;
import com.bkstudio.support.MutableClock;
import com.bkstudio.support.TestClockConfig;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real Postgres + fixed clock (2030-01) + empty queue tables before and after each test. */
@Import(TestClockConfig.class)
abstract class QueueTestBase extends AbstractPostgresTest {

    @Autowired
    QueueService queue;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanBefore() {
        clock.set(Instant.parse("2030-01-01T00:00:10Z"));
        QueueFixtures.wipe(jdbc);
    }

    @AfterEach
    void cleanAfter() {
        QueueFixtures.wipe(jdbc);
    }

    int allocated(int offset) {
        return jdbc.queryForObject(
                "SELECT allocated FROM production_month WHERE month = ?",
                Integer.class,
                java.sql.Date.valueOf(QueueFixtures.month(offset)));
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int openEntries() {
        return count("SELECT count(*) FROM queue_entry WHERE status IN ('QUEUED','IN_PRODUCTION')");
    }
}
