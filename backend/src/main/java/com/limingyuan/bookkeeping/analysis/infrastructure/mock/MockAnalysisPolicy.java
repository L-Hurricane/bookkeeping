package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Explicit local mock gate. A disabled switch does not inspect the rest of the configuration. */
public final class MockAnalysisPolicy {
    private MockAnalysisPolicy() { }

    public static void requireSafe(Environment environment) {
        requireSafe(environment.getActiveProfiles(), environment.getDefaultProfiles(),
                environment.getProperty("bookkeeping.analysis.dashscope.api-key", ""),
                environment.getProperty("bookkeeping.analysis.mock.allowed-usernames", ""),
                environment.getProperty("bookkeeping.analysis.mock.mode", "success"));
    }

    static void requireSafe(String[] active, String[] defaults, String apiKey, String usernames, String mode) {
        String[] effective = active == null || active.length == 0 ? defaults : active;
        if (effective == null) effective = new String[0];
        boolean local = false;
        boolean production = false;
        for (String profile : effective) {
            if ("local".equals(profile)) local = true;
            if ("prod".equals(profile) || "production".equals(profile)) production = true;
        }
        if (production) throw new IllegalStateException("monthly analysis mock refuses a production profile");
        if (!local) throw new IllegalStateException("monthly analysis mock requires the local profile");
        if (apiKey != null && !apiKey.isBlank()) {
            throw new IllegalStateException("monthly analysis mock refuses to start while a DashScope API key is configured");
        }
        if (usernames(usernames).isEmpty()) {
            throw new IllegalStateException("monthly analysis mock requires bookkeeping.analysis.mock.allowed-usernames");
        }
        String selected = mode == null || mode.isBlank() ? "success" : mode.trim();
        if (!"success".equals(selected) && !"failure".equals(selected)) {
            throw new IllegalStateException("monthly analysis mock mode must be success or failure");
        }
    }

    public static Set<String> usernames(String raw) {
        var names = new LinkedHashSet<String>();
        if (raw == null || raw.isBlank()) return Set.of();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return Set.copyOf(names);
    }
}
