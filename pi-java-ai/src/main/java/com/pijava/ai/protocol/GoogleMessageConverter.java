package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;

import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * {@code google-generative-ai} 车道的「消息 → 线格」转换（pi {@code google-shared.ts}）。
 *
 * <p>从 {@link GoogleGenerativeAiApi} 搬出（包 B84 步2，纯搬移零行为改动）——
 * 车道文件已达 407 行，本包要往转换里加约 120 行，不拆就破 500 行上限。搬移手法与
 * 包 H2 步6 拆 completions／responses 两条车道一致。</p>
 *
 * <p><b>当前形状＝搬移前的形状</b>（角色只有 user／model 两态，工具结果与助手消息
 * 共用一条路径）。包 B84 步4 会把这里重构成 pi 的<b>按消息角色分三支</b>形状
 * （见 {@code docs/45}）；本步刻意不改行为，好让那一步的红灯是「工具结果落错角色」
 * 而不是「搬移搬坏了」。</p>
 */
final class GoogleMessageConverter {

    private GoogleMessageConverter() {
    }

    /** pi {@code google-shared.ts:175} 的正则 {@code /^gemini(?:-live)?-(\d+)/}。 */
    private static final java.util.regex.Pattern GEMINI_VERSION =
            java.util.regex.Pattern.compile("^gemini(?:-live)?-(\\d+)");

    /**
     * 该模型的工具调用／工具结果要不要带显式 {@code id}（pi {@code google-shared.ts:165-172}）。
     *
     * <p>三个来源：Cloud Code Assist 上的 {@code claude-*}／{@code gpt-oss-*}（无版本可言，
     * 恒要），以及 gemini 主版本 ≥ 3。⚠️ <b>非 gemini 且非那两个前缀 ⇒ 假</b> ——
     * 与 {@link #supportsMultimodalFunctionResponse} 的「非 gemini 恒真」**相反**，
     * 两者不是同一个谓词的两个名字（夹具 {@code theTwoPredicatesDisagreeOnNonGemini} 钉着）。</p>
     */
    static boolean requiresToolCallId(String modelId) {
        var major = geminiMajorVersion(modelId);
        return modelId.startsWith("claude-") || modelId.startsWith("gpt-oss-")
                || (major != null && major >= 3);
    }

    /**
     * gemini 主版本号，读不到给 {@code null}（pi {@code :174-178}）。
     *
     * <p>⚠️ 正则前**先小写化** —— 目录 id 的大小写是用户输入，{@code models.json} 里
     * 写 {@code GEMINI-3-PRO} 也得认（pi 同样先 {@code toLowerCase()}）。</p>
     */
    private static Integer geminiMajorVersion(String modelId) {
        var m = GEMINI_VERSION.matcher(modelId.toLowerCase(java.util.Locale.ROOT));
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * 工具结果里的图片能不能<b>内嵌</b>进 {@code functionResponse.parts}
     * （pi {@code google-shared.ts:180-186}）。
     *
     * <p>gemini 主版本 ≥ 3 ⇒ 真；gemini &lt; 3 ⇒ 假（另起一条 user 图片回合）；
     * ⚠️ <b>非 gemini 恒真</b> —— 「不是 Gemini ⇒ 不受 Gemini 版本限制」。
     * 把它「顺手统一」成 {@code major != null && major >= 3} 会让 claude／gpt-oss
     * 多出一条 user 图片回合。</p>
     */
    static boolean supportsMultimodalFunctionResponse(String modelId) {
        var major = geminiMajorVersion(modelId);
        return major == null || major >= 3;
    }

    /** 消息列表 → {@code Content[]}。 */
    static List<Content> toContents(List<Message> messages) {
        var contents = new ArrayList<Content>();
        for (var msg : messages) {
            var role = msg instanceof Message.UserMessage ? "user" : "model";
            var parts = new ArrayList<Part>();
            for (var block : msg.content()) {
                parts.addAll(blockParts(block));
            }
            if (!parts.isEmpty()) {
                contents.add(Content.builder()
                        .role(role)
                        .parts(parts)
                        .build());
            }
        }
        return contents;
    }

    /** 内容块 → {@code Part[]}（一个块可能展开成零个或多个 Part）。 */
    static List<Part> blockParts(ContentBlock block) {
        return switch (block) {
            // pi google-shared.ts:207/:212（user 串/文本项）、:242（assistant 文本块）
                    // —— 均为「该块的文本」，java 两角色共用这一处 ⇒ 一处即够。
            case ContentBlock.TextContent tc ->
                    List.of(Part.fromText(SanitizeUnicode.surrogates(tc.text())));
            case ContentBlock.ThinkingContent tc ->
                    List.of(); // Gemini has its own thinking protocol; do not echo it as text
            case ContentBlock.DiffContent diff ->
                    List.of(); // display-only artifact; not part of the LLM request
            case ContentBlock.ToolUseContent tc -> {
                var fc = FunctionCall.builder()
                        .name(tc.name())
                        .args(tc.arguments());
                if (tc.id() != null && !tc.id().isEmpty()) {
                    fc.id(tc.id());
                }
                yield List.of(Part.builder()
                        .functionCall(fc.build())
                        .build());
            }
            case ContentBlock.ToolResultContent tc -> {
                    // Extract text from content blocks for the function response
                    String text = tc.content().stream()
                        .filter(ContentBlock.TextContent.class::isInstance)
                        .map(b -> ((ContentBlock.TextContent) b).text())
                        .collect(java.util.stream.Collectors.joining("\n"));
                    // pi google-shared.ts:301 —— 净化的是拼好之后的 responseValue。
                    yield List.of(Part.fromFunctionResponse(tc.toolUseId(),
                            Map.of("content", SanitizeUnicode.surrogates(text))));
                }
            case ContentBlock.ImageContent ic ->
                    List.of(Part.fromBytes(
                            java.util.Base64.getDecoder().decode(ic.data()),
                            ic.mediaType()));
            case ContentBlock.UrlImageContent url ->
                    // Gemini 走 fileData（URL 图片，P6-19）。
                    List.of(Part.builder()
                            .fileData(com.google.genai.types.FileData.builder()
                                    .fileUri(url.url())
                                    .build())
                            .build());
        };
    }

    /** 工具定义 → {@code FunctionDeclaration[]}。 */
    static List<FunctionDeclaration> functions(List<ToolDefinition> tools) {
        return tools.stream().<FunctionDeclaration>map(tool -> {
            var builder = FunctionDeclaration.builder()
                    .name(tool.name());
            if (tool.description() != null && !tool.description().isEmpty()) {
                builder.description(tool.description());
            }
            if (tool.inputSchema() != null) {
                builder.parametersJsonSchema(tool.inputSchema());
            }
            return builder.build();
        }).toList();
    }
}
