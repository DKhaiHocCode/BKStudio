package com.bkstudio.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Restricting controllers avoids loading the catalog controllers (and their service beans) into this slice.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiExceptionBecomesProblemDetailWithCodeAndRequestId() throws Exception {
        mvc.perform(get("/test/out-of-stock").header("X-Request-Id", "req-12345678"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Request-Id", "req-12345678"))
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Pneuma 1/7 has only 0 ready units left."))
                .andExpect(jsonPath("$.type").value("https://bkstudio.xxx/errors/out-of-stock"))
                .andExpect(jsonPath("$.requestId").value("req-12345678"));
    }

    @Test
    void unexpectedExceptionHidesDetailsButKeepsRequestId() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void dataAccessExceptionLogsClassAndSqlStateOnly_neverTheMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            mvc.perform(get("/test/db"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).hasSize(1);
        ILoggingEvent e = logs.list.get(0);
        assertThat(e.getFormattedMessage()).contains("DataIntegrityViolationException", "sqlState=23514");
        assertThat(e.getThrowableProxy()).isNull();
        assertThat(e.getFormattedMessage()).doesNotContain("pii@example.com");
    }

    @Test
    void validationFailureListsFieldErrors() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownPathIsNotFoundProblem() throws Exception {
        mvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void wrongMethodIsClientErrorWithFallbackCode() throws Exception {
        mvc.perform(post("/test/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @TestConfiguration
    @RestController
    static class ThrowingController {

        @GetMapping("/test/out-of-stock")
        void outOfStock() {
            throw new ApiException(ErrorCode.OUT_OF_STOCK, "Pneuma 1/7 has only 0 ready units left.");
        }

        @GetMapping("/test/db")
        void db() {
            throw new DataIntegrityViolationException(
                    "Failing row contains (pii@example.com)",
                    new SQLException("Failing row contains (pii@example.com)", "23514"));
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Payload payload) {}

        record Payload(@NotBlank String name) {}
    }
}
