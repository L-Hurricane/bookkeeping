package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed.Kind;
import com.limingyuan.bookkeeping.analysis.application.ModelOutputValidator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed local output. It has no HTTP client and cannot call a provider. */
final class LocalMockMonthlyAnalysisGenerator implements MonthlyAnalysisGenerator {
    static final String PROVIDER = "local-mock";
    static final String MODEL = "deterministic-acceptance";
    static final String SURPLUS = "本地测试专用：这段模拟结余解释不应进入最终报告。";
    static final String POINTS = "【本地模拟】本月分析基于当前账本数据生成，请结合实际交易明细进行核对。";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final boolean fail;

    LocalMockMonthlyAnalysisGenerator(boolean fail) { this.fail = fail; }

    @Override
    public MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt) {
        if (input == null) throw new IllegalArgumentException("model input is required");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("system prompt is required");
        if (fail) throw new AnalysisGenerationFailed(Kind.PROVIDER_ERROR, null);
        String content = content();
        ModelOutputValidator.parse(content);
        return new MonthlyAnalysisGeneration(PROVIDER, MODEL, content, null, null, null, 0L, null);
    }

    static String content() {
        ObjectNode node = JSON.createObjectNode();
        node.put("surplusReasons", SURPLUS);
        node.put("pointsToWatch", POINTS);
        return JSON.writeValueAsString(node);
    }
}
