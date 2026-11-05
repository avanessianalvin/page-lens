package com.pagelens;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Dashboard REST API and the sync scheduler. Lives in {@code com.pagelens} so scanning covers
 * {@code com.pagelens.core}. Only this process enables scheduling, so the MCP process never syncs by itself.
 *
 * <p>{@code java -jar pagelens-web.jar --hash-password} prints a bcrypt hash for
 * {@code PAGELENS_WEB_PASSWORD_HASH} and exits without starting the app.
 */
@SpringBootApplication
@EnableScheduling
public class PageLensWebApplication {

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--hash-password")) {
            hashPassword();
            return;
        }
        SpringApplication.run(PageLensWebApplication.class, args);
    }

    private static void hashPassword() throws Exception {
        char[] password;
        Console console = System.console();
        if (console != null) {
            password = console.readPassword("Dashboard password: ");
            char[] again = console.readPassword("Repeat: ");
            if (!Arrays.equals(password, again)) {
                System.err.println("Passwords do not match.");
                System.exit(1);
            }
        } else {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            password = line == null ? new char[0] : line.toCharArray();
        }
        if (password.length < 10) {
            System.err.println("Use at least 10 characters.");
            System.exit(1);
        }
        String hash = new BCryptPasswordEncoder(12).encode(new String(password));
        Arrays.fill(password, '\0');
        System.out.println("PAGELENS_WEB_PASSWORD_HASH=" + hash);
        System.err.println("Add that line to .env (as-is, no quotes).");
    }
}
