package com.limingyuan.bookkeeping.analysis.infrastructure.dashscope;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalysisProviderConfiguration {
    @Bean
    MonthlyAnalysisGenerator monthlyAnalysisGenerator(
            @Value("${bookkeeping.analysis.dashscope.base-url:}") String baseUrl,
            @Value("${bookkeeping.analysis.dashscope.api-key:}") String apiKey,
            @Value("${bookkeeping.analysis.dashscope.model:qwen3.7-flash}") String model,
            @Value("${bookkeeping.analysis.dashscope.timeout:PT60S}") Duration timeout,
            @Value("${bookkeeping.analysis.dashscope.max-completion-tokens:2048}") int maxCompletionTokens) {
        Duration connect = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
        return new QwenMonthlyAnalysisGenerator(QwenMonthlyAnalysisGenerator.httpClient(connect), baseUrl, apiKey, model,
                timeout, maxCompletionTokens);
    }
}
