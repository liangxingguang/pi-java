package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.FunctionResponsePart;
import com.google.genai.types.Part;

import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * {@code google-generative-ai} 车道的「消息 → 线格」转换（pi {@code google-shared.ts:191-343}）。
 *
 * <p>从 {@link GoogleGenerativeAiApi} 搬出（包 B84 步2，纯搬移零行为改动），随后按 pi 的
 * <b>形状</b>重写成三分支（步4）—— 工具结果在 pi 里是<b>消息级</b>的东西
 * （{@code role: "toolResult"}），不是内容块；此前 java 用「不是 user 就是 model」的
 * 两态角色判据把它当成 model 轮的纯文本发出去，Gemini 的多轮工具调用因此是坏的。</p>
 *
 * <h3>三分支（pi {@code :202}）</h3>
 * <ul>
 *   <li>{@code user} ⇒ 一条 {@code role:"user"}，块展开为空则**整条跳过**（pi {@code :222}）</li>
 *   <li>{@code assistant} ⇒ 一条 {@code role:"model"}，块展开为空则整条跳过（pi {@code :279}）</li>
 *   <li>{@code toolResult} ⇒ {@code functionResponse} ＋ {@code role:"user"}
 *       （含**合并**与**独立图片回合**两条附加规则，pi {@code :320-338}）</li>
 * </ul>
 */
final class GoogleMessageConverter {

    private GoogleMessageConverter() {
    }

    /** pi {@code google-shared.ts:175} 的正则 {@code /^gemini(?:-live)?-(\d+)/}。 */
    private static final Pattern GEMINI_VERSION = Pattern.compile("^gemini(?:-live)?-(\\d+)");

    /**
     * pi {@code :301} 的「有图无文」占位文案 —— 字面量，**不净化**。
     */
    private static final String SEE_ATTACHED_IMAGE = "(see attached image)";

    /** pi {@code :337} 独立图片回合的开头文案 —— 同样是**不净化**的字面量。 */
    private static final String TOOL_RESULT_IMAGE_LABEL = "Tool result image:";

    /**
     * 消息列表 → {@code Content[]}（pi {@code :191-343}）。
     *
     * @param modelId 目标模型 id —— 两个谓词（{@link #requiresToolCallId}／
     *                {@link #supportsMultimodalFunctionResponse}）都读它
     * @param model   目标模型元数据，供车道侧能力门读（{@code null} ⇒ 按「未知」处理，
     *                见 {@link #addToolResult} 的判据说明）
     */
    static List<Content> toContents(List<Message> messages, ModelId<?> modelId, ModelInfo model) {
        var contents = new ArrayList<Content>();
        for (var msg : messages) {
            switch (msg) {
                case Message.UserMessage user -> {
                    var parts = new ArrayList<Part>();
                    for (var block : user.content()) {
                        parts.addAll(blockParts(block));
                    }
                    if (parts.isEmpty()) {
                        continue;                       // pi :222
                    }
                    contents.add(Content.builder().role("user").parts(parts).build());
                }
                case Message.AssistantMessage assistant -> {
                    var parts = assistantParts(assistant, modelId);
                    if (parts.isEmpty()) {
                        continue;                       // pi :279
                    }
                    contents.add(Content.builder().role("model").parts(parts).build());
                }
                case Message.ToolResultMessage tool -> addToolResult(contents, tool, modelId, model);
            }
        }
        return contents;
    }

    /**
     * 助手消息的块 → Part（pi {@code :228-283}）。
     *
     * <p>文本与工具调用两条在这里**特判**（各有自己的规则），其余块仍走
     * {@link #blockParts}（thinking／diff／块级 toolResult 丢块、图片落线）。</p>
     */
    private static List<Part> assistantParts(Message.AssistantMessage msg, ModelId<?> modelId) {
        var parts = new ArrayList<Part>();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :240 —— 空白文本块跳过。⚠️「除非它带 textSignature」那半句在 java
                // **恒为假**（TextContent 没有签名字段，docs/45 D8）⇒ 实现就是「空白就跳」。
                // ⚠️ 与 pi 的细微差别：pi 的 trim() 会剥掉 U+00A0(NBSP)／U+FEFF 等，
                // Java 的 isBlank() 不认（Character.isWhitespace 排除它们）⇒ 一个**纯 NBSP**
                // 的块在 pi 被跳过、在 java 上线（已登记）。
                if (tc.text().isBlank()) {
                    continue;
                }
                parts.add(Part.fromText(SanitizeUnicode.surrogates(tc.text())));
            } else if (block instanceof ContentBlock.ToolUseContent tu) {
                var fc = FunctionCall.builder()
                        .name(tu.name())
                        .args(tu.arguments());
                // pi :271 —— id 受 requiresToolCallId 门控。⚠️ 本包**改掉了**此前「恒发 id」
                // 的行为（docs/45 D3）：只关门的一侧会造出 pi 里不存在的状态
                // （functionCall 有 id、functionResponse 没有）。null／空串仍不发
                // —— 对应 pi 的 `block.id === undefined` 时那个键被 JSON.stringify 略去。
                if (requiresToolCallId(modelId.modelName())
                        && tu.id() != null && !tu.id().isEmpty()) {
                    fc.id(tu.id());
                }
                parts.add(Part.builder().functionCall(fc.build()).build());
            } else {
                parts.addAll(blockParts(block));
            }
        }
        return parts;
    }

    /**
     * 工具结果 → {@code functionResponse}（pi {@code :284-339}）。
     *
     * <p>六件事，逐条对应 pi：</p>
     * <ol>
     *   <li>{@code name} 取<b>工具名</b>（{@code :313}）—— <b>不是</b> {@code toolUseId}；
     *       {@code id} 是另一个字段（第 5 条）</li>
     *   <li>{@code response} 的键按 {@code isError} 二选一（{@code :314}）</li>
     *   <li>{@code responseValue} 三选一（{@code :301}）</li>
     *   <li>有图<b>且</b>模型支持多模态函数响应 ⇒ 图片**内嵌**进 {@code functionResponse.parts}
     *       （{@code :315}）；不满足则整键不写</li>
     *   <li>{@code id} 只在 {@link #requiresToolCallId} 时写（{@code :316}）</li>
     *   <li>有图<b>但不</b>支持内嵌 ⇒ 合并之后再 push 一条独立的
     *       {@code "Tool result image:"} user 回合（{@code :332-338}）</li>
     * </ol>
     *
     * <p><b>能力门的判据</b>（第 4 条的 {@code hasImages}）：用
     * {@link ModelInfo#supportsImageInput()}，<b>不是</b>字面的
     * {@code capabilities().contains(IMAGE_INPUT)}。两者只在「目录未命中」时不同 ——
     * 那时 {@code capabilities} 为空集，后者会判「不支持」⇒ <b>静默丢图</b>，与包 H2 的
     * D2 裁决（未知 ⇒ 按支持 ⇒ 让 provider 响亮报错）相反。代价是这道门与共享闸
     * （{@code TransformMessages.downgradeUnsupportedImages}，**同一个** {@code ModelInfo}、
     * **同一个**判据）恒同真同假 ⇒ 它<b>不可达</b>，变异探针**必然零红</b>。
     * ⚠️ 那不是「夹具没牙」，是这一行**没有出参**（包 H2 已在 completions／responses
     * 两处实测过同一形态，{@code docs/44 §10-3}）—— 照抄保留是为了与 pi 同形。</p>
     */
    private static void addToolResult(List<Content> contents, Message.ToolResultMessage msg,
                                      ModelId<?> modelId, ModelInfo model) {
        var text = msg.content().stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .collect(Collectors.joining("\n"));                     // pi :287
        var images = model != null && model.supportsImageInput()          // pi :288
                ? msg.content().stream().filter(GoogleMessageConverter::isImageBlock).toList()
                : List.<ContentBlock>of();

        boolean hasText = !text.isEmpty();
        boolean hasImages = !images.isEmpty();
        boolean multimodal = supportsMultimodalFunctionResponse(modelId.modelName());  // pi :298

        // pi :301 —— 三选一；**净化的是拼好之后的整串**（包 A0 的 Google 落点之一）。
        String responseValue = hasText ? SanitizeUnicode.surrogates(text)
                : hasImages ? SEE_ATTACHED_IMAGE : "";

        var fnResponse = FunctionResponse.builder()
                .name(msg.toolName())                                   // pi :313（不是 toolUseId！）
                .response(msg.isError()                                 // pi :314
                        ? Map.of("error", responseValue)
                        : Map.of("output", responseValue));
        if (hasImages && multimodal) {                                  // pi :315
            // ⚠️ 内嵌用的是 FunctionResponsePart（SDK 里与 Part 是**两个类型**），
            // 而独立回合用的是 Part —— pi 的单一 Part 在 Java SDK 上分叉成两支，
            // 故这里不能像 pi 那样复用同一个 imageParts 列表（docs/45 §9 的登记）。
            fnResponse.parts(images.stream()
                    .map(GoogleMessageConverter::functionResponseImagePart).toList());
        }
        if (requiresToolCallId(modelId.modelName())) {                  // pi :316
            fnResponse.id(msg.toolUseId());
        }
        appendFunctionResponse(contents,
                Part.builder().functionResponse(fnResponse.build()).build());

        if (hasImages && !multimodal) {                                 // pi :332-338
            var parts = new ArrayList<Part>();
            parts.add(Part.fromText(TOOL_RESULT_IMAGE_LABEL));          // 字面量，不净化
            for (var image : images) {
                parts.add(inlineDataPart(image));
            }
            contents.add(Content.builder().role("user").parts(parts).build());
        }
    }

    /**
     * pi {@code :320-330} —— 最后一条是「带 {@code functionResponse} 的 user 回合」就<b>并入</b>，
     * 否则**新起**一条。
     *
     * <p>并入是为了 Cloud Code Assist：它要求所有函数响应落在同一个 user 回合里。⚠️ 这个
     * 判据会被上一条的**独立图片回合打断** —— 图片回合是 user 但 parts 里没有
     * {@code functionResponse} ⇒ 下一条工具结果**新起**一回合（pi 自己的夹具钉着这个形状，
     * 见 {@code google-shared-image-tool-result-routing.test.ts:80-89}）。</p>
     */
    private static void appendFunctionResponse(List<Content> contents, Part fnResponse) {
        if (!contents.isEmpty()) {
            var last = contents.get(contents.size() - 1);
            boolean mergeable = "user".equals(last.role().orElse(null))
                    && last.parts().orElse(List.of()).stream()
                            .anyMatch(p -> p.functionResponse().isPresent());
            if (mergeable) {
                var parts = new ArrayList<>(last.parts().orElse(List.of()));
                parts.add(fnResponse);
                contents.set(contents.size() - 1, last.toBuilder().parts(parts).build());
                return;
            }
        }
        contents.add(Content.builder().role("user").parts(List.of(fnResponse)).build());
    }

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
        var m = GEMINI_VERSION.matcher(modelId.toLowerCase(Locale.ROOT));
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

    /**
     * 工具结果里的图片块判据（pi 只认 {@code type === "image"}）。
     *
     * <p>⚠️ java 扩展的 {@link ContentBlock.UrlImageContent} **不算**（{@code docs/45 §8}）：
     * pi 无此类型，把它算进来会让 {@code hasImages} 在 pi 里没有对应物。</p>
     */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent;
    }

    /** pi {@code :303-308} —— 内嵌图片 {@code {inlineData:{mimeType,data}}}（FunctionResponsePart 支）。 */
    private static FunctionResponsePart functionResponseImagePart(ContentBlock block) {
        var image = (ContentBlock.ImageContent) block;
        return FunctionResponsePart.fromBytes(Base64.getDecoder().decode(image.data()),
                image.mediaType());
    }

    /** pi {@code :303-308} 的 Part 支（独立图片回合用）。 */
    private static Part inlineDataPart(ContentBlock block) {
        var image = (ContentBlock.ImageContent) block;
        return Part.fromBytes(Base64.getDecoder().decode(image.data()), image.mediaType());
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
            // pi **没有**块级的 toolResult —— 工具结果在 pi 里是**消息级**的
            // （role: "toolResult"），走 toContents 的第三条分支。
            // 本 case 只可能来自手改的会话文件（ContentBlock.ToolResultContent 是
            // 解码专用类型，生产零构造点）⇒ 与 ThinkingContent／DiffContent 同形丢块。
            case ContentBlock.ToolResultContent tc -> List.of();
            case ContentBlock.ImageContent ic ->
                    List.of(Part.fromBytes(
                            Base64.getDecoder().decode(ic.data()),
                            ic.mediaType()));
            case ContentBlock.UrlImageContent url ->
                    // Gemini 走 fileData（URL 图片，P6-19）。
                    List.of(Part.builder()
                            .fileData(FileData.builder()
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
