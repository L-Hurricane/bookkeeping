package com.limingyuan.bookkeeping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Authentication uses our database sessions, never Boot's generated development user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BookkeepingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookkeepingApplication.class, args);
    }
}
