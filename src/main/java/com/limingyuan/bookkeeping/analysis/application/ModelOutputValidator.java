package com.limingyuan.bookkeeping.analysis.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contract v1. Exactly two non-blank strings. Field order is ignored. Schema validation does not prove the prose. */
public final class ModelOutputValidator {
    public static final int MAX_LENGTH = 2000;
    private static final Set<String> FIELDS = Set.of("surplusReasons", "pointsToWatch");
    private static final Pattern LABELED_AMOUNT = Pattern.compile(
            "(收入净额|支出净额|净收入|净支出|结余)(?:为|是)?[：:]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*元");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ModelOutputValidator() { }

    public static ModelInterpretation parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException ex) {
            throw new InvalidModelOutput("模型输出不是 JSON 对象");
        }
        if (root == null || !root.isObject()) throw new InvalidModelOutput("模型输出必须是 JSON 对象");
        var names = new java.util.HashSet<String>();
        for (var field : root.properties()) names.add(field.getKey());
        if (!names.equals(FIELDS)) throw new InvalidModelOutput("模型输出必须恰好包含 surplusReasons 和 pointsToWatch");
        return new ModelInterpretation(text(root, "surplusReasons"), text(root, "pointsToWatch"));
    }

    /**
     * Rejects a labeled total in pointsToWatch whose signed value differs from the ledger fact.
     * The model surplusReasons field is not displayed, so a wrong restatement there does not fail the report.
     */
    public static void requireConsistentAmounts(ModelInterpretation interpretation, String netIncome, String netExpense,
            String surplus) {
        var expected = Map.of("净收入", new BigDecimal(netIncome), "收入净额", new BigDecimal(netIncome),
                "净支出", new BigDecimal(netExpense), "支出净额", new BigDecimal(netExpense),
                "结余", new BigDecimal(surplus));
        var matcher = LABELED_AMOUNT.matcher(interpretation.pointsToWatch());
        while (matcher.find()) {
            if (new BigDecimal(matcher.group(2)).compareTo(expected.get(matcher.group(1))) != 0) {
                throw new InvalidModelOutput("模型文字中的" + matcher.group(1) + "与账本事实不一致");
            }
        }
    }

    private static String text(JsonNode root, String name) {
        var node = root.get(name);
        if (node == null || !node.isString()) throw new InvalidModelOutput(name + " 必须是字符串");
        String value = node.asString().strip();
        if (value.isBlank()) throw new InvalidModelOutput(name + " 不能为空白");
        if (value.length() > MAX_LENGTH) throw new InvalidModelOutput(name + " 超过长度限制");
        return value;
    }

    public record ModelInterpretation(String surplusReasons, String pointsToWatch) { }
}
