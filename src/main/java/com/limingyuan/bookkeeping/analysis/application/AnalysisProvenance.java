package com.limingyuan.bookkeeping.analysis.application;

/** Source metadata stored with a successful snapshot. Token counts may be absent. */
public record AnalysisProvenance(
        String provider,
        String model,
        String promptVersion,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        long latencyMillis,
        String providerRequestId) { }
