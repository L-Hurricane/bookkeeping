package com.limingyuan.bookkeeping.identity.api;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.annotation.JsonDeserialize;

public record LoginRequest(@NotBlank @Size(max = 100) @JsonDeserialize(using = StrictStringDeserializer.class) String username,
                           @NotBlank @Size(max = 72) @JsonDeserialize(using = StrictStringDeserializer.class) String password) {
    public LoginRequest {
        if (username != null) username = username.trim();
    }

    @AssertTrue
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
