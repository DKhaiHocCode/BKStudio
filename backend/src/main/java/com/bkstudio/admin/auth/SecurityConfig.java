package com.bkstudio.admin.auth;

import com.bkstudio.shared.web.SecurityProblemHandler;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Admin security (SECURITY.md T1-T3). Authentication is done by {@link AdminAuthService}, not by a Spring login
 * filter, so there is no form login / basic auth here. Sessions are the Tomcat in-memory store.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SecurityConfig {

    static final String AUTH_PATH = "/api/admin/auth/";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Clock clock) throws Exception {
        SecurityProblemHandler problems = new SecurityProblemHandler();
        return http
                // Cookie XSRF-TOKEN (readable by JS) -> header X-XSRF-TOKEN, per the Spring Security SPA guide.
                .csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(a -> a
                        // Login steps and logout are reachable before full authentication (still CSRF-protected).
                        .requestMatchers(HttpMethod.POST, AUTH_PATH + "login", AUTH_PATH + "totp", AUTH_PATH + "logout")
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                // A 401 must not create a session for anonymous callers.
                .requestCache(rc -> rc.disable())
                .logout(l -> l.disable())
                .addFilterBefore(new AbsoluteSessionTimeoutFilter(clock), SecurityContextHolderFilter.class)
                .build();
    }

    // Defining one stops Boot from generating (and logging) a random default user password.
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException("no users");
        };
    }
}
