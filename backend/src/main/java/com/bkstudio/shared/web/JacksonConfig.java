package com.bkstudio.shared.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.EnumFeature;

@Configuration
class JacksonConfig {

    /** Reject JSON numbers for enum fields (e.g. {"status":1}); otherwise Jackson maps them to ordinals. */
    @Bean
    JsonMapperBuilderCustomizer rejectNumbersForEnums() {
        return builder -> builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
