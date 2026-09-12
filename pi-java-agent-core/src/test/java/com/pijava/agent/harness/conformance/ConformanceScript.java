package com.pijava.agent.harness.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * L5 差分剧本（{@code conformance/scripts/S*.json}）的 Java 侧模型。
 *
 * <p>剧本是 pi 侧与 pi-java 侧**共用的唯一输入**（{@code docs/23c §2.2}）：两侧各自把它
 * 翻译成自己的流事件序列与配置，再比对产出的帧序列。剧本里出现而 pi 侧运行时不存在的
 * 概念（例如分块规则）会被显式写出来，避免两侧各自发明启发式而漂移。</p>
 *
 * @param id           剧本编号（{@code S1}…{@code S8}）
 * @param name         剧本的中文名（仅用于报告）
 * @param systemPrompt 系统提示词
 * @param prompt       首条用户消息
 * @param toolExecution {@code parallel} 或 {@code sequential}
 * @param tools        声明的工具；{@code reject=true} 表示被 {@code beforeToolCall} 拦截
 * @param responses    按请求次序排列的助手响应
 * @param steer        在指定轮之后注入的 steer 消息
 * @param followUp     在本轮循环耗尽后注入的 follow-up 消息
 */
record ConformanceScript(
        String id,
        String name,
        String systemPrompt,
        String prompt,
        String toolExecution,
        List<Tool> tools,
        List<Response> responses,
        List<Injection> steer,
        List<Injection> followUp) {

    /**
     * 剧本声明的工具。
     *
     * @param reject    由 {@code beforeToolCall} 拦截，从未执行（pi 的 denied 路径）
     * @param isError   只决定结果文本是 {@code failed} 还是 {@code ok} —— pi 侧脚本的同名字段
     *                  就是这个含义，结果消息的 {@code isError} 标记始终为 {@code false}。
     *                  这里照搬以免两侧对同一份剧本给出不同解释
     * @param terminate 该调用是否终止本轮批次
     */
    record Tool(String name, boolean reject, boolean isError, boolean terminate) {}

    /**
     * 一段助手响应里的内容块。
     *
     * @param text      文本块的内容
     * @param thinking  思考块的内容
     * @param name      工具调用的名字
     * @param arguments 工具调用的参数
     * @param chunks    文本块的**显式**流式分块；缺省表示整段一次成型、不发 delta
     */
    record Content(String type, String text, String thinking, String name,
                   Map<String, Object> arguments, List<String> chunks) {}

    /** 一次助手响应：内容块 + 停因。 */
    record Response(List<Content> content, String stopReason) {}

    /** 在完成第 {@code afterTurn} 轮（0 基）之后注入的一条消息。 */
    record Injection(int afterTurn, String text) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 读取并解析一个剧本文件。 */
    static ConformanceScript load(Path file) {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read script " + file, e);
        }
        var tools = new ArrayList<Tool>();
        for (var node : root.path("tools")) {
            tools.add(new Tool(node.path("name").asText(),
                node.path("reject").asBoolean(false),
                node.path("isError").asBoolean(false),
                node.path("terminate").asBoolean(false)));
        }
        var responses = new ArrayList<Response>();
        for (var node : root.path("responses")) {
            responses.add(new Response(contentOf(node.path("content")),
                node.path("stopReason").asText("stop")));
        }
        return new ConformanceScript(
            root.path("id").asText(),
            root.path("name").asText(""),
            root.path("systemPrompt").asText("You are helpful."),
            root.path("prompt").asText("Hello"),
            root.path("toolExecution").asText("parallel"),
            List.copyOf(tools),
            List.copyOf(responses),
            injections(root.path("steer")),
            injections(root.path("followUp")));
    }

    private static List<Content> contentOf(JsonNode blocks) {
        var content = new ArrayList<Content>();
        for (var node : blocks) {
            content.add(new Content(
                node.path("type").asText(),
                textOrNull(node, "text"),
                textOrNull(node, "thinking"),
                textOrNull(node, "name"),
                argumentsOf(node.path("arguments")),
                chunksOf(node.path("chunks"))));
        }
        return List.copyOf(content);
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<String, Object> argumentsOf(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private static List<String> chunksOf(JsonNode node) {
        if (!node.isArray()) {
            return null;
        }
        var chunks = new ArrayList<String>();
        for (var chunk : node) {
            chunks.add(chunk.asText());
        }
        return List.copyOf(chunks);
    }

    private static List<Injection> injections(JsonNode node) {
        var list = new ArrayList<Injection>();
        for (var entry : node) {
            list.add(new Injection(entry.path("afterTurn").asInt(),
                entry.path("text").asText("")));
        }
        return List.copyOf(list);
    }
}
