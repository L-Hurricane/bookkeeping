package com.limingyuan.bookkeeping.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    Clock identityClock() { return Clock.systemUTC(); }

    @Bean
    Duration sessionTtl(@Value("${bookkeeping.identity.session-ttl:P7D}") Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Session TTL must be between one second and 365 days");
        }
        return ttl;
    }
}
