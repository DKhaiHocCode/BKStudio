package com.bkstudio;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BkstudioBackendApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BkstudioBackendApplication.class);
        // create-admin CLI mode (T-012): no web server; exit once the runner is done.
        boolean cli = Arrays.stream(args).anyMatch(a -> a.startsWith("--create-admin="));
        if (cli) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext ctx = app.run(args);
        if (cli) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
