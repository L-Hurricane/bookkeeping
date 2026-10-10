package com.limingyuan.bookkeeping.analysis.application;

import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contract v1. Exactly two non-blank strings. Field order is ignored. Schema validation does not prove the prose. */
public final class ModelOutputValidator {
    public static final int MAX_LENGTH = 2000;
    private static final Set<String> FIELDS = Set.of("surplusReasons", "pointsToWatch");
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
