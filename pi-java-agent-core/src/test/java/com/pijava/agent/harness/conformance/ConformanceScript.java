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
 * @param nextTurn     {@code prepareNextTurn} 的返回值；{@code null} 表示该剧本不配钩子
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
        List<Injection> followUp,
        NextTurn nextTurn) {

    /**
     * {@code prepareNextTurn} 在指定轮之后返回的东西（pi 的 {@code AgentLoopTurnUpdate}）。
     *
     * <p>三个字段都映射到 pi 的对应字段：{@code messages} + {@code systemPrompt} 合成
     * 一个**整体替换**的 {@code AgentContext}，{@code model} 换模型。</p>
     *
     * <p>pi 的第三个字段 {@code thinkingLevel} **不在剧本里**：它不出现在任何帧上，
     * 差分验证不了 —— 写进来只会给人一种「已覆盖」的错觉。</p>
     *
     * @param afterTurn    在第 {@code afterTurn} 轮（0 基）完成之后生效，只触发一次
     * @param messages     替换后上下文里的用户消息文本（整体替换，不是追加）
     * @param systemPrompt 替换后的系统提示
     * @param model        要切换到的模型 id（provider 固定 {@code openai}，两侧的基线模型同此）
     */
    record NextTurn(int afterTurn, List<String> messages, String systemPrompt, String model) {}

    /**
     * 剧本声明的工具。
     *
     * @param reject    由 {@code beforeToolCall} 拦截，从未执行（pi 的 denied 路径）
     * @param isError   只决定结果文本是 {@code failed} 还是 {@code ok} —— pi 侧脚本的同名字段
     *                  就是这个含义，结果消息的 {@code isError} 标记始终为 {@code false}。
     *                  这里照搬以免两侧对同一份剧本给出不同解释
     * @param terminate 该调用是否终止本轮批次
     */
    /**
     * 剧本里的一个工具。
     *
     * @param executionMode {@code "sequential"} / {@code "parallel"}；缺省 = 未声明
     *                      （pi 侧同为 {@code undefined}，两者都不触发顺序路径）
     * @param details       结果对象的 {@code details} 载荷（原样进
     *                      {@code tool_execution_end.result.details}）；缺省 = {@code {}}
     *                      （pi 侧 {@code t.details ?? {}}，{@code run.test.ts:319}）
     * @param updates       执行期间经 update 回调流出的部分结果条数；缺省 0。
     *                      两侧都把它翻成 {@code tool_execution_update} 帧 ——
     *                      这是 L5 观察「工具流式更新是否发射」的唯一通道
     * @param delayMs       该工具的**声明延迟**（毫秒）；缺省 0。两侧都先睡够再流 updates、
     *                      再返回结果。见 {@code docs/31 §8.23.7}：并行批次的 end 是
     *                      <b>完成序</b>，而等延迟的两个调用谁先完成在两侧都不可约 ——
     *                      把延迟写进剧本，完成序才是**声明出来的**、可比对的证据，
     *                      而不是「恰好在我的运行时里同序」的偶然
     * @param updateEveryMs 相邻两条 update 之间睡这么久（毫秒）；缺省 0 = 背靠背。
     *                      首条 update 之前仍先睡 {@code delayMs}。见 {@code docs/31 §8.24}：
     *                      背靠背发 update 时**任何别的帧都插不进来**（两侧的桩都是同步
     *                      循环），于是「update 与并发批次的交错」结构上跑不到 ——
     *                      本字段把交错变成声明出来的事实，S14 用它
     */
    record Tool(String name, boolean reject, boolean isError, boolean terminate,
                String executionMode, Object details, int updates, int delayMs,
                int updateEveryMs) {}

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

    /**
     * 一次助手响应：内容块 + 停因。
     *
     * @param echoRequest 把「这次请求实际带了什么」（消息数 / 模型 / 系统提示）编进首个文本块的开头。
     *                    <p><b>为什么需要它</b>：归一化后的帧**只有 agent 事件** —— 请求消息本身
     *                    从不进帧（剧本的流是假的，不看参数）。于是 {@code prepareNextTurn}
     *                    整体替换上下文的后果在帧里完全不可观察，剧本会在钩子根本没被调用时
     *                    照样通过 —— 一条不可能为它存在的理由而失败的用例。回显把这次请求的
     *                    形状折进助手消息的文本，差分才真正覆盖到那条通道。</p>
     * @param usage       终局消息携带的整只 {@code Usage}（包 H1 步 7，裁决 C）；
     *                    缺省 ⇒ 两侧各自的恒零桩（A1 的旧状态）。字段名就是 pi 的
     *                    {@code Usage} 键名 —— 剧本是两侧共用的唯一输入，pi 侧无需翻译，
     *                    Java 侧由 {@code ScriptedStreams} 一一对进 {@link com.pijava.ai.Usage}。
     */
    record Response(List<Content> content, String stopReason, boolean echoRequest,
                    UsageScript usage) {}

    /**
     * 剧本声明的整只 usage（S15；{@code docs/42} 步 7 / 裁决 C 的产物）。
     *
     * <p>形状 = pi {@code types.ts} 的 {@code Usage}：四必填计数 + 两个可选键
     * （{@code cacheWrite1h}/{@code reasoning}）+ {@code totalTokens} + 五字段
     * {@code cost}。可选键的<b>缺席</b>才是本字段想钉的东西之一 —— 见
     * {@code FrameNormalizer.usageOf} 的「非空才带」规则，pi 侧靠
     * {@code JSON.stringify} 丢 undefined，两侧必须同样省略。</p>
     *
     * <p>⚠️ {@code cost} 的数值一律取<b>二进制精确</b>的小数（如 0.125、0.03125）：
     * JS 的 {@code JSON.stringify} 与 Java 的 {@code Double.toString} 只在
     * {@code <1e-3} 处进位记数法且格式不同（{@code 0.00002} ≠ {@code 2.0E-5}），
     * 十进制小数的最短往返表示两语言一致 —— 用二分小数把这个格式分叉彻底避开。</p>
     */
    record UsageScript(long input, long output, long cacheRead, long cacheWrite,
                       Long cacheWrite1h, Long reasoning, long totalTokens,
                       CostScript cost) {}

    /** 剧本声明的成本（pi {@code Usage.cost} 的五字段）。 */
    record CostScript(double input, double output, double cacheRead, double cacheWrite,
                      double total) {}

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
                node.path("terminate").asBoolean(false),
                node.path("executionMode").isMissingNode()
                    ? null : node.path("executionMode").asText(),
                detailsOf(node.path("details")),
                node.path("updates").asInt(0),
                node.path("delayMs").asInt(0),
                node.path("updateEveryMs").asInt(0)));
        }
        var responses = new ArrayList<Response>();
        for (var node : root.path("responses")) {
            responses.add(new Response(contentOf(node.path("content")),
                node.path("stopReason").asText("stop"),
                node.path("echoRequest").asBoolean(false),
                usageScriptOf(node)));
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
            injections(root.path("followUp")),
            nextTurnOf(root.path("nextTurn")));
    }

    /** {@code nextTurn} 缺省为 {@code null}（S1–S8 都不配钩子）。 */
    private static NextTurn nextTurnOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var messages = new ArrayList<String>();
        for (var message : node.path("messages")) {
            messages.add(message.asText(""));
        }
        return new NextTurn(
            node.path("afterTurn").asInt(0),
            List.copyOf(messages),
            textOrNull(node, "systemPrompt"),
            textOrNull(node, "model"));
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

    /** 工具的 {@code details}：任意 JSON 原样转 Java 结构；缺席/显式 null → null（两侧都按 {@code {}} 处理）。 */
    private static Object detailsOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    /**
     * 响应的 {@code usage}：缺省/非对象 ⇒ {@code null}（两侧回落到各自的恒零桩）。
     * 写了 usage 就必须写全 {@code cost} —— pi 的类型里 cost 是必填，残缺剧本在
     * 装载期就炸，而不是产出一条两边都"自圆其说"的畸形基线。
     */
    private static UsageScript usageScriptOf(JsonNode response) {
        var usage = response.path("usage");
        if (!usage.isObject()) {
            return null;
        }
        var cost = usage.path("cost");
        if (!cost.isObject()) {
            throw new IllegalStateException("script usage requires a five-field cost");
        }
        return new UsageScript(
            usage.path("input").asLong(),
            usage.path("output").asLong(),
            usage.path("cacheRead").asLong(),
            usage.path("cacheWrite").asLong(),
            optionalLong(usage, "cacheWrite1h"),
            optionalLong(usage, "reasoning"),
            usage.path("totalTokens").asLong(),
            new CostScript(
                cost.path("input").asDouble(),
                cost.path("output").asDouble(),
                cost.path("cacheRead").asDouble(),
                cost.path("cacheWrite").asDouble(),
                cost.path("total").asDouble()));
    }

    private static Long optionalLong(JsonNode parent, String field) {
        var value = parent.get(field);
        return value == null || value.isNull() ? null : value.asLong();
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
