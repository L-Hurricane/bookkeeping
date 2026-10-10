package com.limingyuan.bookkeeping.analysis.application;

/** One successful provider call. Token counts and the provider request id are absent when the provider omits them. */
public record MonthlyAnalysisGeneration(
        String provider,
        String model,
        String content,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        long latencyMillis,
        String providerRequestId) { }
