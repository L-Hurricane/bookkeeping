package com.limingyuan.bookkeeping.acceptance.data;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit local command. Absent unless ACCEPTANCE_DATA_ENABLED=true, and still refuses non-local profiles. */
@Component
@Order(200)
@ConditionalOnProperty(name = "bookkeeping.acceptance-data.enabled", havingValue = "true")
public class AcceptanceDataRunner implements CommandLineRunner {
    private final Environment environment;
    private final AcceptanceDataSeeder seeder;

    public AcceptanceDataRunner(Environment environment, AcceptanceDataSeeder seeder) {
        this.environment = environment;
        this.seeder = seeder;
    }

    static boolean allows(boolean enabled, String... profiles) {
        if (!enabled) return false;
        boolean local = false;
        for (String profile : profiles) {
            if ("prod".equals(profile) || "production".equals(profile)) return false;
            if ("local".equals(profile)) local = true;
        }
        return local;
    }

    @Override
    public void run(String... args) {
        if (!allows(true, environment.getActiveProfiles())) {
            throw new IllegalStateException("Acceptance data seeder refuses to run outside the local profile");
        }
        String password = environment.getProperty("ACCEPTANCE_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("ACCEPTANCE_TEST_PASSWORD is required");
        }
        String action = environment.getProperty("bookkeeping.acceptance-data.action", "");
        switch (action) {
            case "reset" -> seeder.reset();
            case "seed" -> seeder.seed(password);
            case "reset-and-seed" -> seeder.resetAndSeed(password);
            default -> throw new IllegalStateException("ACCEPTANCE_DATA_ACTION must be reset, seed, or reset-and-seed");
        }
        System.out.println("acceptance-data " + action + " ok");
        System.out.println("accept_empty empty");
        System.out.println("accept_zero opening-zero");
        System.out.println("accept_positive opening-positive");
        System.out.println("accept_negative opening-negative");
        System.out.println("accept_ledger ledger");
    }
}
