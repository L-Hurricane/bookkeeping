package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationAdmission;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.OpenAnalysisAdmission;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** Default test profile keeps the Qwen generator. Mock is absent. */
@SpringBootTest
@ActiveProfiles("test")
class DefaultGeneratorAssemblyTest {
    @Autowired ApplicationContext context;
    @Autowired MonthlyAnalysisGenerator generator;
    @Autowired AnalysisGenerationAdmission admission;
    @Autowired DataSource dataSource;

    @BeforeEach
    void requireTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_test");
        }
    }

    @Test void missingMockSwitchUsesQwenAndOpenAdmission() {
        assertThat(context.getBeansOfType(MonthlyAnalysisGenerator.class)).hasSize(1);
        assertThat(generator.getClass().getName()).endsWith("QwenMonthlyAnalysisGenerator");
        assertThat(admission).isInstanceOf(OpenAnalysisAdmission.class);
    }
}
