package com.bkstudio.shared.settings.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Optional https URL whose host is one of {@link #hosts()} or a true subdomain of one. Null/blank = unset. */
@Documented
@Constraint(validatedBy = SocialUrl.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SocialUrl {

    int MAX_LENGTH = 300;

    String message() default "must be an https URL on the platform's own domain";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String[] hosts();

    class Validator implements ConstraintValidator<SocialUrl, String> {

        private String[] hosts;

        @Override
        public void initialize(SocialUrl a) {
            this.hosts = a.hosts();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return value == null || value.isBlank() || matches(value, hosts);
        }

        /** Strict check: https only, no userinfo/port/whitespace/control chars, host equals or is a subdomain of a host. */
        public static boolean matches(String url, String... hosts) {
            if (url.length() > MAX_LENGTH) {
                return false;
            }
            for (int i = 0; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c)) {
                    return false;
                }
            }
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                return false;
            }
            // getHost() is null for malformed authorities such as "reddit.com:evil", so those fail too.
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            for (String h : hosts) {
                if (host.equals(h) || host.endsWith("." + h)) {
                    return true;
                }
            }
            return false;
        }
    }
}
