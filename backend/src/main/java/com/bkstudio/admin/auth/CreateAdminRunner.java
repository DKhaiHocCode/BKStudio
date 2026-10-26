package com.bkstudio.admin.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * CLI: {@code --create-admin=<email>} with env ADMIN_INITIAL_PASSWORD. Prints the TOTP secret and recovery
 * codes once to stdout (never to the logger), never the email or password. Failures throw, so exit code != 0.
 */
@Component
@ConditionalOnProperty("create-admin")
class CreateAdminRunner implements ApplicationRunner {

    private final AdminBootstrap bootstrap;
    private final Environment env;

    CreateAdminRunner(AdminBootstrap bootstrap, Environment env) {
        this.bootstrap = bootstrap;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = args.getOptionValues("create-admin").getFirst();
        String password = env.getProperty("ADMIN_INITIAL_PASSWORD");
        if (password == null || password.length() < AdminBootstrap.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_INITIAL_PASSWORD must be set and at least " + AdminBootstrap.MIN_PASSWORD_LENGTH + " chars");
        }
        AdminBootstrap.Created c = bootstrap.create(email, password);
        System.out.println("Admin created. Save the following NOW; it is shown only once.");
        System.out.println("TOTP secret (Base32): " + c.totpSecretBase32());
        System.out.println("otpauth URI: " + c.otpauthUri());
        System.out.println("Recovery codes (single use):");
        c.recoveryCodes().forEach(code -> System.out.println("  " + code));
    }
}
