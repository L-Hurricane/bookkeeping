package com.limingyuan.bookkeeping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Phase 0 has no users or login. Avoid Boot's generated development credentials.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BookkeepingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookkeepingApplication.class, args);
    }
}
