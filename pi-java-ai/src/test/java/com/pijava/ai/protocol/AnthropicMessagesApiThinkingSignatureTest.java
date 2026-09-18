package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.SignatureDelta;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingDelta;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock.TextContent;
import com.pijava.ai.message.ContentBlock.ThinkingContent;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2（{@code docs/31 §8.31}）：thinking 块的 {@code signature} **缺失**不得打死
 * 整轮 run。
 *
 * <p>生产事故（2026-09-17 22:14 web UI）的逐字报文：
 * {@code com.anthropic.errors.AnthropicInvalidDataException: `signature` is not set}
 * —— 来自 {@code AnthropicMessagesApi:121-122} 调用 SDK 的**严格必填**访问器
 * {@code ThinkingBlock.signature()}。pi 在同一个位置是容忍的：
 * {@code anthropic-messages.ts:633} {@code event.content_block.signature ?? ""}。</p>
 *
 * <p><b>夹具纪律</b>：① 事件一律从 **JSON 反序列化**（{@code ObjectMappers.jsonMapper()}）
 * —— 这是线上的真实入口；SDK 的 {@code ThinkingBlock.builder()} 自己会校验必填并抛
 * 「{@code `signature` is required, but was not set}」（**另一条**报文），从 builder 进
 * 就测不到适配器。② 一条流共享同一个 {@link StreamPartialBuilder} 与块态数组
 * （生产的形状：{@code streamInternal} 每流各一份）。③ {@code mapEvent} 是私有的，
 * 按仓库既有手法反射进入（同 {@code AnthropicMessagesApiBuildParamsTest:29-36}）。</p>
 */
class AnthropicMessagesApiThinkingSignatureTest {

    private static final ApiOptions OPTIONS = new ApiOptions(
        "https://api.example.invalid", "test-key", Duration.ofSeconds(5), 0, Map.of());

    private static final Class<?> STOP_STATE_TYPE = loadStopState();

    private static final Method MAP_EVENT = findMapEvent();

    private static Class<?> loadStopState() {
        try {
            return Class.forName("com.pijava.ai.protocol.AnthropicMessagesApi$StopState");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "StopState 改名/消失了，夹具需同步（docs/31 §8.35.14）", e);
        }
    }

    private static Method findMapEvent() {
        try {
            var method = AnthropicMessagesApi.class.getDeclaredMethod("mapEvent",
                RawMessageStreamEvent.class, StreamPartialBuilder.class,
                boolean[].class, boolean[].class, String[].class, String[].class, STOP_STATE_TYPE);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mapEvent 的签名变了，夹具需同步（docs/31 §8.31）", e);
        }
    }

    /** 一条流：适配器实例 + 一个 builder + 块态数组（照 {@code streamInternal} 的形状）。 */
    private static final class Stream {

        private final AnthropicMessagesApi api = new AnthropicMessagesApi(OPTIONS);
        private final StreamPartialBuilder builder = new StreamPartialBuilder();
        private final boolean[] isToolBlock = {false};
        private final boolean[] isThinkingBlock = {false};
        private final String[] pendingToolName = {""};
        private final String[] pendingToolId = {""};
        // B20 把 `toolCallSeen` 换成了 stop reason 状态（pi 只看 message_delta.stop_reason）；
        // 本夹具不喂 message_delta，故只需一个空实例。
        private final Object stopState;

        Stream() throws ReflectiveOperationException {
            var ctor = STOP_STATE_TYPE.getDeclaredConstructor();
            ctor.setAccessible(true);
            stopState = ctor.newInstance();
        }

        StreamEvent feed(RawMessageStreamEvent event) throws Exception {
            return (StreamEvent) MAP_EVENT.invoke(api, event, builder,
                isToolBlock, isThinkingBlock, pendingToolName, pendingToolId, stopState);
        }

        StreamEvent feedJson(String json, Class<?> type) throws Exception {
            var parsed = ObjectMappers.jsonMapper().readValue(json, type);
            if (parsed instanceof ThinkingBlock block) {
                return feed(RawMessageStreamEvent.ofContentBlockStart(
                    RawContentBlockStartEvent.builder().index(0).contentBlock(block).build()));
            }
            if (parsed instanceof RedactedThinkingBlock block) {
                return feed(RawMessageStreamEvent.ofContentBlockStart(
                    RawContentBlockStartEvent.builder().index(0).contentBlock(block).build()));
            }
            if (parsed instanceof ThinkingDelta delta) {
                return feed(RawMessageStreamEvent.ofContentBlockDelta(
                    RawContentBlockDeltaEvent.builder().index(0).delta(delta).build()));
            }
            if (parsed instanceof SignatureDelta delta) {
                return feed(RawMessageStreamEvent.ofContentBlockDelta(
                    RawContentBlockDeltaEvent.builder().index(0).delta(delta).build()));
            }
            throw new IllegalArgumentException("夹具不认识的事件类型：" + type);
        }

        StreamEvent stop() throws Exception {
            return feed(RawMessageStreamEvent.ofContentBlockStop(
                RawContentBlockStopEvent.builder().index(0).build()));
        }
    }

    private static ThinkingContent firstThinking(AssistantMessage partial) {
        return (ThinkingContent) partial.content().stream()
            .filter(ThinkingContent.class::isInstance)
            .findFirst()
            .orElseThrow(() -> new AssertionError("partial 里没有 thinking 块：" + partial.content()));
    }

    // ── RE-P2：缺 signature 的 content_block_start ────────────────────────

    /**
     * 修复前：{@code mapEvent} 返回 {@code StreamError(AnthropicInvalidDataException:
     * `signature` is not set)} —— 模块间还吃得下，但它就是生产上打死整轮 run 的那一条。
     * 修复后：块正常开始、流照走到底。
     */
    @Test
    void thinkingBlockWithoutSignatureStillStreams() throws Exception {
        var stream = new Stream();

        var start = stream.feedJson(
            "{\"type\":\"thinking\",\"thinking\":\"\"}", ThinkingBlock.class);
        assertThat(start)
            .as("缺 signature 的 thinking 块必须还能开始（线上由 signature_delta 补，relay 可能整个不给）")
            .isInstanceOf(StreamEvent.ThinkingStart.class);

        stream.feedJson("{\"type\":\"thinking_delta\",\"thinking\":\"hidden\"}", ThinkingDelta.class);
        var end = stream.stop();

        assertThat(end).isInstanceOf(StreamEvent.ThinkingEnd.class);
        var block = firstThinking(((StreamEvent.ThinkingEnd) end).partial());
        assertThat(block.text())
            .as("思考文本必须留下 —— 空签名只影响下一轮的重放，不影响本轮内容")
            .isEqualTo("hidden");
        assertThat(block.signature()).isEmpty();
    }

    /** 防回归：签名在时仍要进块（修复前后都该绿）。 */
    @Test
    void thinkingBlockWithSignatureKeepsIt() throws Exception {
        var stream = new Stream();

        var start = stream.feedJson(
            "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-123\"}", ThinkingBlock.class);
        assertThat(start).isInstanceOf(StreamEvent.ThinkingStart.class);

        stream.feedJson("{\"type\":\"thinking_delta\",\"thinking\":\"hidden\"}", ThinkingDelta.class);
        var end = stream.stop();

        assertThat(firstThinking(((StreamEvent.ThinkingEnd) end).partial()).signature())
            .isEqualTo("sig-123");
    }

    // ── RE-P2b：缺 signature 的 signature_delta（同族第二个访问器）────────

    /**
     * {@code :145} 是同族的第二个严格访问器。线上的 {@code signature_delta}
     * 若缺 signature 字段，修复前同样抛（此处反序列化走 JSON 路径 —— SDK builder
     * 会拒绝这种形状）。
     */
    @Test
    void signatureDeltaWithoutSignatureDoesNotKillTheStream() throws Exception {
        var stream = new Stream();
        stream.feedJson("{\"type\":\"thinking\",\"thinking\":\"\"}", ThinkingBlock.class);
        stream.feedJson("{\"type\":\"thinking_delta\",\"thinking\":\"hidden\"}", ThinkingDelta.class);

        var out = stream.feedJson("{\"type\":\"signature_delta\"}", SignatureDelta.class);

        assertThat(out)
            .as("缺 signature 的 signature_delta 只应是无事发生，不应变成 StreamError（pi 的 JS 会把 "
                + "undefined 拼成字面量 \"undefined\"，那是 pi 的事故，不复刻）")
            .isInstanceOf(StreamEvent.ThinkingDelta.class);
        assertThat(((StreamEvent.ThinkingDelta) out).delta()).isEmpty();
        assertThat(firstThinking(((StreamEvent.ThinkingDelta) out).partial()).signature()).isEmpty();
    }

    /** 防回归：签名在时仍要落进 partial（供下一轮重放）。 */
    @Test
    void signatureDeltaWithSignatureLandsInPartial() throws Exception {
        var stream = new Stream();
        stream.feedJson("{\"type\":\"thinking\",\"thinking\":\"\"}", ThinkingBlock.class);

        var out = stream.feedJson(
            "{\"type\":\"signature_delta\",\"signature\":\"sig-456\"}", SignatureDelta.class);

        assertThat(out).isInstanceOf(StreamEvent.ThinkingDelta.class);
        assertThat(firstThinking(((StreamEvent.ThinkingDelta) out).partial()).signature())
            .isEqualTo("sig-456");
    }

    // ══════════════════════════════════════════════════════════════════
    // 包①（docs/31 §8.33）：初始文本/签名 + redacted_thinking
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>B6</b>：{@code content_block_start} 里预置的 {@code thinking} 必须随首个
     * {@code ThinkingStart.partial} 投影，并活着穿过后续 delta。
     *
     * <p>pi 的次序是「先建好带初值的块（{@code anthropic-messages.ts:630-635}）→ 入 content
     * （{@code :636}）→ 才 push {@code thinking_start}（{@code :637}）」；修复前 pi-java
     * **整个没读** {@code event.content_block.thinking}。</p>
     *
     * <p>第二条断言（{@code prepost}）才是真正钉住修法的：{@code emitThinkingDelta} 用
     * 缓冲**覆盖**块，所以初始文本必须被 seed 进缓冲，否则会被第一个 delta 冲掉。</p>
     */
    @Test
    void initialThinkingTextLandsInFirstPartialAndSurvivesDeltas() throws Exception {
        var stream = new Stream();

        var start = stream.feedJson(
            "{\"type\":\"thinking\",\"thinking\":\"pre\",\"signature\":\"sig\"}", ThinkingBlock.class);

        assertThat(start).isInstanceOf(StreamEvent.ThinkingStart.class);
        assertThat(firstThinking(((StreamEvent.ThinkingStart) start).partial()).text())
            .as("B6：pi 在 :632 是 `thinking ?? \"\"`，预置文本必须收下")
            .isEqualTo("pre");

        stream.feedJson("{\"type\":\"thinking_delta\",\"thinking\":\"post\"}", ThinkingDelta.class);
        var end = stream.stop();

        assertThat(firstThinking(((StreamEvent.ThinkingEnd) end).partial()).text())
            .as("B6：缓冲必须被 seed —— 否则初始文本会被第一个 delta 覆盖冲掉")
            .isEqualTo("prepost");
    }

    /**
     * <b>B9</b>：初始 {@code signature} 必须进**首个** {@code ThinkingStart.partial}。
     *
     * <p>修复前 {@code emitThinkingStart()} 先 {@code snapshot()} 返回（{@code StreamPartialBuilder:127}），
     * {@code AnthropicMessagesApi:131} 才拿 {@code emitThinkingSignature(initial)} 去改块
     * —— 而且那个返回值**被直接丢弃、从未 submit**。于是初始签名只能从**下一个**事件的
     * partial 起才可见，与 pi 的「先入块、后 push」差一拍。</p>
     */
    @Test
    void initialThinkingSignatureLandsInFirstPartial() throws Exception {
        var stream = new Stream();

        var start = stream.feedJson(
            "{\"type\":\"thinking\",\"thinking\":\"pre\",\"signature\":\"sig\"}", ThinkingBlock.class);

        assertThat(firstThinking(((StreamEvent.ThinkingStart) start).partial()).signature())
            .as("B9：pi 在 :633 收 signature，块在 push 事件前已入 content ⇒ 首个 partial 就该有")
            .isEqualTo("sig");
    }

    /**
     * <b>B7</b>：{@code redacted_thinking} 必须走 thinking 通道，且留下**不透明载荷**。
     *
     * <p>前半段是 SDK 反序列化**路由**的活凭据（不是 javap 推断）：pi-java 拿到的是
     * {@code ContentBlock}，只有 SDK 的 {@code ContentBlock.Deserializer} 把
     * {@code "redacted_thinking"} 路由到 {@code redactedThinking} 变体，
     * 生产的 {@code isRedactedThinking()} 才可能为真。SDK 是**手写**反序列化器
     * （{@code ContentBlock.kt:549-553}），失败时兜底 {@code ContentBlock(_json = json)}
     * —— 四个变体**全为 null**，会静默掉进 text 分支，所以这条必须显式断言。</p>
     *
     * <p>后半段钉住修复后的形状：pi {@code :638-647} 映射为 thinking 块，
     * 文本固定 {@code "[Reasoning redacted]"}、{@code thinkingSignature = data}、
     * {@code redacted: true}；且因为它收不到任何 delta，走错分支会在消息里留下
     * 一个空 {@code TextContent("")}。</p>
     */
    @Test
    void redactedThinkingBlockStartsAsThinkingWithOpaqueSignature() throws Exception {
        String json = "{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}";

        var routed = ObjectMappers.jsonMapper()
            .readValue(json, com.anthropic.models.messages.ContentBlock.class);
        assertThat(routed.isRedactedThinking())
            .as("SDK 必须把 redacted_thinking 路由到该变体，否则 B7 的修法作废（docs/31 §8.33.7-2）")
            .isTrue();
        assertThat(routed.redactedThinking().orElseThrow()._data().asString())
            .contains("opaque");

        var stream = new Stream();
        var start = stream.feedJson(json, RedactedThinkingBlock.class);

        assertThat(start)
            .as("B7：redacted 块必须走 thinking 通道，不是 text")
            .isInstanceOf(StreamEvent.ThinkingStart.class);
        var block = firstThinking(((StreamEvent.ThinkingStart) start).partial());
        assertThat(block.text()).isEqualTo("[Reasoning redacted]");
        assertThat(block.signature()).isEqualTo("opaque");
        assertThat(block.redacted()).isTrue();

        var end = stream.stop();
        assertThat(((StreamEvent.ThinkingEnd) end).partial().content())
            .as("B7：走对分支就不该留下空 TextContent（那个空块会被原样发给 Anthropic）")
            .noneMatch(TextContent.class::isInstance);
    }
}
