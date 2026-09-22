package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolResultBlockParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 H2（{@code docs/44}）步 2：Anthropic 车道的图片落点 ——
 * pi {@code anthropic-messages.ts:128-174}（{@code convertContentBlocks}）与 {@code :1243-1269}（user 分支）。
 *
 * <p>本车道有三处**独有的不对称**（{@code docs/44 D3}），逐条钉住：</p>
 * <table>
 *   <caption>三处不对称</caption>
 *   <tr><th>#</th><th>user 分支</th><th>toolResult 分支（{@code convertContentBlocks}）</th></tr>
 *   <tr><td>空文本块</td><td>**过滤**（{@code :1262-1268}）</td><td>**不过滤**</td></tr>
 *   <tr><td>无文本时补块</td><td>不补</td><td>补 {@code "(see attached image)"}（{@code :166-172}）</td></tr>
 *   <tr><td>多文本块</td><td>逐块保留</td><td>**join("\n") 成一个**（{@code :142-144}）</td></tr>
 * </table>
 *
 * <p>观测面＝反射 {@code buildParams} 后的 {@link MessageCreateParams}（先例：
 * {@code AnthropicSurrogateSanitizeTest}）＋ 一条走 {@link RecordingHttpServer} 的**上线**断言
 * （结构对了不等于序列化对了 —— 见 {@code docs/43 §9-6} 的教训）。</p>
 *
 * <p><b>实测：`Tests run: 14, Failures: 7, Errors: 3`</b>（本步实现前）—— 10 条行为红灯、4 条回归门
 * （{@code urlImagesAreStillDropped} ／ {@code assistantImagesAreIgnored} 今天就是绿的：
 * 它们钉的是「**不该**发的那两类」，闸缺席时恰好也满足）。
 * ⚠️ 3 条 Error 是同一个症状：图片是**唯一的**内容块时，落线后块集为空 ⇒ 整条消息被
 * {@code continue} 掉 ⇒ 消息列表为空 ⇒ SDK 在 {@code build()} 上抛
 * {@code IllegalState: `messages` is required}。即：**今天发一条只带图片的用户消息会让整个请求失败**。</p>
 */
class AnthropicImageContentTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";

    private static final String PNG_B64 = "aGVsbG8=";
    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    private static final ModelInfo VISION = new ModelInfo(TARGET, "Claude Sonnet 5",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            200_000, 8_192, false, PricingInfo.UNKNOWN);

    private static ContentBlock image() {
        return new ContentBlock.ImageContent("image/png", PNG_B64);
    }

    // ── 出参构造（反射，先例：AnthropicSurrogateSanitizeTest） ──────────

    private static MessageCreateParams buildParams(List<Message> messages) throws Exception {
        var options = new ApiOptions("https://api.example.test", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        var request = new StreamRequest(VISION, null, messages, List.of(), -1, -1, Map.of());
        Method method = AnthropicMessagesApi.class.getDeclaredMethod("buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    /** 一条消息的线格，压成 {@code text:…} ／ {@code image:mime:data} ／ {@code other}。 */
    private static List<String> render(MessageCreateParams params) {
        var out = new ArrayList<String>();
        for (var m : params.messages()) {
            for (var b : m.content().asBlockParams()) {
                if (b.isText()) {
                    out.add("text:" + b.asText().text());
                } else if (b.isImage()) {
                    out.add(renderImage(b.asImage()));
                } else if (b.isToolResult()) {
                    var content = b.asToolResult().content().orElse(null);
                    if (content == null) {
                        continue;
                    }
                    if (content.isString()) {
                        out.add("tool_text:" + content.asString());
                    } else {
                        content.asBlocks().forEach(blk -> {
                            if (blk.isText()) {
                                out.add("tool_text:" + blk.asText().text());
                            } else if (blk.isImage()) {
                                out.add("tool_" + renderImage(blk.asImage()));
                            }
                        });
                    }
                } else {
                    out.add("other");
                }
            }
        }
        return out;
    }

    private static String renderImage(com.anthropic.models.messages.ImageBlockParam img) {
        var src = img.source();
        if (!src.isBase64()) {
            return "image:url";
        }
        var b64 = src.asBase64();
        return "image:" + b64.mediaType().asString() + ":" + b64.data();
    }

    private static List<String> wire(List<Message> messages) throws Exception {
        return render(buildParams(messages));
    }

    private static List<Message> user(ContentBlock... blocks) {
        return List.of(new Message.UserMessage(List.of(blocks)));
    }

    private static List<Message> toolResult(ContentBlock... blocks) {
        return List.of(new Message.ToolResultMessage("toolu_1", "read",
                List.of(blocks), false));
    }

    // ------------------------------------------------------------------ RED

    /** <b>红</b> pi {@code :1250-1260}：user 的图片块落成 {@code {type:"image",source:{type:"base64",…}}}。 */
    @Test
    void userImagesBecomeBase64ImageBlocks() throws Exception {
        assertThat(wire(user(new ContentBlock.TextContent("look at this"), image())))
                .containsExactly("text:look at this", "image:image/png:" + PNG_B64);
    }

    /**
     * <b>红</b> pi {@code :1266}：只有图片、没有文本时**不补**占位符（那是 toolResult 分支
     * {@code :166-172} 的行为）—— 这是三处不对称的第二处。
     */
    @Test
    void imageOnlyUserMessageGetsNoPlaceholder() throws Exception {
        assertThat(wire(user(image()))).containsExactly("image:image/png:" + PNG_B64);
    }

    /** <b>红</b> pi {@code :1262-1268}：user 分支**过滤** trim 为空的文本块（第一处不对称）。 */
    @Test
    void userBlankTextBlocksAreFiltered() throws Exception {
        assertThat(wire(user(new ContentBlock.TextContent("   "), image())))
                .containsExactly("image:image/png:" + PNG_B64);
    }

    /** <b>红</b> pi {@code :1216}＋{@code :156-163}：toolResult 的图片落成图片块（有文本时保留文本块）。 */
    @Test
    void toolResultImagesBecomeImageBlocks() throws Exception {
        assertThat(wire(toolResult(new ContentBlock.TextContent("Read image file [image/png]"), image())))
                .containsExactly("tool_text:Read image file [image/png]",
                    "tool_image:image/png:" + PNG_B64);
    }

    /**
     * <b>红</b> pi {@code :166-172}：toolResult **只有图片**时补一个 {@code "(see attached image)"}
     * （第三处不对称 —— user 分支不补）。
     */
    @Test
    void toolResultWithoutTextGetsSeeAttachedImage() throws Exception {
        assertThat(wire(toolResult(image())))
                .containsExactly("tool_text:(see attached image)",
                    "tool_image:image/png:" + PNG_B64);
    }

    /**
     * <b>红</b> pi {@code :142-144}：**无图片**时各文本块 {@code join("\n")} 成**一个**块
     * —— 这是相对 pi-java 旧行为（一块一文本块）的行为变更，见 {@code docs/44 D3} 的「顺带」。
     */
    @Test
    void toolResultTextBlocksAreJoinedIntoOne() throws Exception {
        assertThat(wire(toolResult(new ContentBlock.TextContent("a"), new ContentBlock.TextContent("b"))))
                .containsExactly("tool_text:a\nb");
    }

    /**
     * <b>红</b> pi {@code :142} 的 join 分隔符是 {@code "\n"}，且**先 join 后净化** ——
     * 跨块边界的「尾孤高 ＋ 首孤低」被那个换行**隔开**，各自作为孤对被删掉，**不会**配对成活 emoji。
     *
     * <p>⚠️ 这条钉的是**分隔符**，不是「净化先后」：因为 {@code "\n"} 永不是代理，
     * join 只会**拆散**相邻关系、不会**制造**它 ⇒ 在 toolResult 这条路径上「逐块净化后拼」
     * 与「拼完再净化」**结果恒等**（`docs/43 §9-2` 记的那个跨块边界例只在**无分隔符**的
     * 拼接上才分叉，例如各车道的 assistant 文本块）。真正的牙在这里：
     * 把 {@code joining("\n")} 改成 {@code joining("")} ⇒ 本用例必须红。</p>
     */
    @Test
    void crossBlockSurrogatesDoNotPairUpAcrossTheJoin() throws Exception {
        assertThat(wire(toolResult(
                new ContentBlock.TextContent("A" + HIGH),
                new ContentBlock.TextContent(LOW + "B"))))
                .containsExactly("tool_text:A\nB");
    }

    /**
     * <b>红</b> pi {@code :166} 的 {@code hasText} 判的是**块类型**而不是非空 ——
     * 一个空文本块照样算「有文本」⇒ **不补**占位符（第一处不对称在 toolResult 侧的镜像）。
     */
    @Test
    void toolResultBlankTextBlockCountsAsText() throws Exception {
        assertThat(wire(toolResult(new ContentBlock.TextContent(""), image())))
                .containsExactly("tool_text:", "tool_image:image/png:" + PNG_B64);
    }

    /**
     * <b>红</b> pi {@code :1307-1369}：assistant 分支**没有** image 分支（`if text / else if
     * thinking / else if toolCall`，无 else）⇒ assistant 里的图片块被**忽略**。
     * java 的 {@code AssistantMessage.content} 是无限定的 {@code List<ContentBlock>}（类型上允许带图），
     * 所以这条是可构造的 —— 照抄 pi 就是忽略。
     */
    @Test
    void assistantImagesAreIgnored() throws Exception {
        var assistant = new Message.AssistantMessage(List.of(
                new ContentBlock.TextContent("hi"), image()), "stop", null,
                "anthropic-messages", TARGET.provider(), TARGET.modelName(),
                null, null, null, null);

        assertThat(wire(List.of(assistant))).containsExactly("text:hi");
    }

    /**
     * <b>红</b> {@code docs/44 D4 选项 A}：{@link ContentBlock.UrlImageContent} 在 Anthropic 车道
     * **仍丢弃**（pi 的 TS 类型里没有 URL 图片来源 ⇒ 照抄；SDK 虽有 {@code UrlImageSource}，
     * 但用它＝发明行为）。
     */
    @Test
    void urlImagesAreStillDropped() throws Exception {
        assertThat(wire(user(new ContentBlock.TextContent("look"),
                new ContentBlock.UrlImageContent("https://example.test/a.png"))))
                .containsExactly("text:look");
    }

    /**
     * <b>红</b> 风险探针（{@code docs/44 J14}）：{@code PathUtils.detectImageMimeType} 会返回
     * {@code image/bmp}，而 pi 的 Anthropic {@code media_type} 联合类型**不含 bmp**
     * （pi 那边是 TS 的 {@code as} 断言、运行时不校验）。java 的 SDK 是有类型的 ⇒
     * 断言 {@code MediaType.of("image/bmp")} **不抛**且原样上线（照缝）。
     */
    @Test
    void bmpMediaTypeSurvivesTheWire() throws Exception {
        assertThat(wire(user(new ContentBlock.ImageContent("image/bmp", PNG_B64))))
                .containsExactly("image:image/bmp:" + PNG_B64);
    }

    /**
     * <b>红</b> 结构对了不等于**序列化**对了：整条请求体过一遍真 HTTP（{@link RecordingHttpServer}），
     * 断言 image 块的三件套都在 JSON 里（含 {@code "type":"base64"} 这个判别位 ——
     * 它由 SDK 补，缺了会被 provider 拒）。
     */
    @Test
    void imageReachesTheSerializedRequestBody() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var options = new ApiOptions(server.baseUrl(), "sk-test",
                Duration.ofSeconds(10), 1, Map.of());
            var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
            var request = new StreamRequest(VISION, null, user(image()), List.of(), -1, -1, Map.of());
            var finished = new java.util.concurrent.CountDownLatch(1);
            // 桩服务器一律回 400 ⇒ 流走 error 收场；请求体此刻已经录到了。
            api.stream(request, options).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }
                @Override public void onNext(com.pijava.ai.stream.StreamEvent e) { }
                @Override public void onError(Throwable t) { finished.countDown(); }
                @Override public void onComplete() { finished.countDown(); }
            });
            assertThat(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("请求未在 10s 内结束").isTrue();
            assertThat(server.body())
                .contains("\"type\":\"image\"")
                .contains("\"type\":\"base64\"")
                .contains("\"media_type\":\"image/png\"")
                .contains("\"data\":\"" + PNG_B64 + "\"");
        }
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /** <b>回归门</b> 纯文本 toolResult 落成**一个**文本块（join 的退化情形，无图片）。 */
    @Test
    void textOnlyToolResultIsASingleTextBlock() throws Exception {
        assertThat(wire(toolResult(new ContentBlock.TextContent("ok"))))
                .containsExactly("tool_text:ok");
    }

    /** <b>回归门</b> 纯文本 user 消息逐块保留（user 分支**不** join）。 */
    @Test
    void textOnlyUserKeepsItsBlocks() throws Exception {
        assertThat(wire(user(new ContentBlock.TextContent("a"), new ContentBlock.TextContent("b"))))
                .containsExactly("text:a", "text:b");
    }
}
