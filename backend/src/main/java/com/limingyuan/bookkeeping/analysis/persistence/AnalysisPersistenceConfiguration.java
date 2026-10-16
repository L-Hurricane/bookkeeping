package com.limingyuan.bookkeeping.analysis.persistence;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class AnalysisPersistenceConfiguration {
    @Bean
    AnalysisGenerationStore analysisGenerationStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcAnalysisGenerationStore(jdbc, transactionManager, Clock.systemUTC(), JdbcAnalysisGenerationStore.LEASE);
    }
}
