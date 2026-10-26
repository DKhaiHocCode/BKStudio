package com.bkstudio.admin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController {

    record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password) {}

    record TotpRequest(
            @Size(max = 16) String code, @Size(max = 64) String recoveryCode) {

        @AssertTrue(message = "provide exactly one of code or recoveryCode")
        boolean isExactlyOne() {
            return hasText(code) != hasText(recoveryCode);
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }

    record LoginResponse(boolean totpRequired) {}

    record AdminInfo(String email) {}

    private final AdminAuthService auth;

    AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        auth.login(request, body.email(), body.password());
        return new LoginResponse(true);
    }

    @PostMapping("/totp")
    AdminInfo totp(@Valid @RequestBody TotpRequest body, HttpServletRequest request, HttpServletResponse response) {
        boolean useCode = body.code() != null && !body.code().isBlank();
        String email = auth.completeLogin(
                request, response, useCode ? body.code().trim() : null, useCode ? null : body.recoveryCode());
        return new AdminInfo(email);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        auth.logout(request);
    }

    @GetMapping("/me")
    AdminInfo me(Principal principal) {
        return new AdminInfo(auth.emailOf(principal.getName()));
    }
}
