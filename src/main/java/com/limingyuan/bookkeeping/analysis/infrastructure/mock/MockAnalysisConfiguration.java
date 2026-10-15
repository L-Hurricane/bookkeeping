package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationAdmission;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.UserDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Present only when the mock switch is exactly true. Safety failures abort startup. */
@Configuration(proxyBeanMethods = false)
public class MockAnalysisConfiguration {
    @Bean
    @ConditionalOnProperty(name = "bookkeeping.analysis.mock.enabled", havingValue = "true")
    MonthlyAnalysisGenerator mockMonthlyAnalysisGenerator(Environment environment) {
        MockAnalysisPolicy.requireSafe(environment);
        String mode = environment.getProperty("bookkeeping.analysis.mock.mode", "success");
        return new LocalMockMonthlyAnalysisGenerator("failure".equals(mode == null ? "" : mode.trim()));
    }

    @Bean
    @ConditionalOnProperty(name = "bookkeeping.analysis.mock.enabled", havingValue = "true")
    AnalysisGenerationAdmission mockAnalysisAdmission(Environment environment, UserDirectory users,
            TrustedUserContext context) {
        MockAnalysisPolicy.requireSafe(environment);
        return new MockUserAdmission(MockAnalysisPolicy.usernames(
                environment.getProperty("bookkeeping.analysis.mock.allowed-usernames", "")), users, context);
    }
}
