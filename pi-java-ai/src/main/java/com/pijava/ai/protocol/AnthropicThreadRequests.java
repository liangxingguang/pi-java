package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.Message;

/**
 * 线程请求的生命周期（pi {@code anthropic-messages.ts:352-475}）—— 「准备 → 发送 → 记账」
 * 三段里除发送外的两段。从 {@link AnthropicMessagesApi} 抽出，守「文件 ≤ 500 行」。
 *
 * <p><b>哈希口径</b>：pi 直接哈希已构造的 {@code params}；稳定 SDK 的
 * {@code MessageCreateParams} 反序列化回来是 {@code {}}（实测），故本仓改用
 * {@link AnthropicRequestBuilder.BuiltParams#rowKeys()}（**挂断点之前**的逐行指纹）＋
 * {@link AnthropicRequestBuilder.BuiltParams#settingsKey()}（设置半边）。语义等价：前缀变了
 * 才需要新线程；漏掉某个设置只会换来一次服务端 {@code thread_fingerprint_mismatch}
 * 拒绝，而那条路径在回退矩阵里会开新线程。</p>
 *
 * <p>⚠️ 记账时 {@code prefixCount} 与哈希**同单位**（都是「行」）：本仓的
 * {@code messageCount} 早就是完整请求的行数（S7a），故无需换算。</p>
 */
final class AnthropicThreadRequests {

    private AnthropicThreadRequests() {
    }

    /**
     * 会话能不能走 threads —— pi {@code getAnthropicCompat:219} 的
     * {@code compat?.supportsMessageThreads ?? model.baseUrl.includes("api.anthropic.com")}。
     *
     * <p>本仓 {@link com.pijava.ai.catalog.ModelCompat} 没有该字段（加字段会连锁改全部构造点，
     * 属 {@code docs/12} 的 D-1 窄差），故「compat 显式值」这一支由 {@code ApiOptions.extra}
     * 的 {@code messageThreads} 承载：{@code extra} 直给（内代码／测试）或线格字符串
     * （{@code models.json}／CLI）。缺席时才回落到端点推导。</p>
     *
     * <p>⚠️ 端点推导与 pi 有一处刻意差异：本仓拿不到 {@code model.baseUrl}，只拿得到
     * 「请求期覆盖后的有效值」。故 {@code baseUrl} 有覆盖时按覆盖值判（必须命中
     * {@code api.anthropic.com}），无覆盖时才按 provider 名判（SDK 的缺省端点就是
     * {@code api.anthropic.com}）。这比 pi 更保守：中转站默认不开（pi 注释里点名的风险
     * 正是「中转站会丢掉未知字段 ⇒ {@code continue} 静默变成没有历史的请求」）。</p>
     *
     * @param override {@code extra["messageThreads"]}；{@code null} ≙ 未配置
     * @param provider 请求模型所属 provider（{@code model.provider}）
     * @param baseUrl  有效 baseUrl（{@code null} ≙ 无覆盖）
     */
    static boolean supported(Boolean override, String provider, String baseUrl) {
        if (override != null) {
            return override;
        }
        boolean hasOverride = baseUrl != null && !baseUrl.isBlank();
        return hasOverride ? baseUrl.contains("api.anthropic.com") : "anthropic".equals(provider);
    }

    /**
     * 本轮走哪个三态（pi {@code prepareThreadRequest :352-383}）。
     *
     * <p>判序逐字对齐 pi：会话被判 {@code "off"} ⇒ 不发（{@code null}）；有可续状态、行数变多、
     * 且前缀哈希仍相同 ⇒ **先删状态再发** {@code continue}（单次可续，同时挡住并发重复续）；
     * 其余一律 {@code create}。</p>
     *
     * @param sessionId 会话号（非空；调用方已过 threads 开关与 {@code cacheRetention} 门）
     * @param provider  provider 名（哈希原料）
     * @param baseUrl   有效 baseUrl（哈希原料）
     * @param full      **完整请求**（{@code thread == null} 那一次）的构造结果
     * @return 三态；{@code null} ≙ 不发 {@code thread}
     */
    static AnthropicThreadWire.ThreadRequest prepare(String sessionId, String provider,
                                                     String baseUrl,
                                                     AnthropicRequestBuilder.BuiltParams full) {
        if (sessionId == null) {
            return AnthropicThreadWire.ThreadRequest.create();
        }
        if (AnthropicThreadState.isOff(sessionId)) {
            return null;
        }
        var state = AnthropicThreadState.get(sessionId).orElse(null);
        if (state != null
                && full.messageCount() > state.prefixCount()
                && state.prefixCount() <= full.rowKeys().size()
                && prefixHash(provider, baseUrl, full, state.prefixCount())
                    .equals(state.prefixHash())) {
            // pi :373-375 —— 读并删：每条回复只能被继续一次。
            AnthropicThreadState.consume(sessionId);
            return AnthropicThreadWire.ThreadRequest.continueFrom(
                state.prefixCount(), state.replyId());
        }
        return AnthropicThreadWire.ThreadRequest.create();
    }

    /**
     * 成功后续记（pi {@code recordThreadReply :445-475}）。
     *
     * <p>不记的两种情形与 pi 同：{@code responseId} 缺失（无法续）与 {@code responseModel}
     * 有值（回退模型作答 —— 服务端**不存**那些回复）。</p>
     *
     * @param sessionId 会话号；{@code null} 表示本轮没走 threads
     * @param reply     本轮终局助手消息（已挂 identity，与下一轮转录里的同一条同形）
     * @param full      **完整请求**的构造结果（本轮若是 continue，rowKeys 仍是完整行表）
     */
    static void record(String sessionId, String provider, String baseUrl, StreamRequest request,
                       AnthropicRequestBuilder.BuiltParams full, Message.AssistantMessage reply,
                       boolean oauth, boolean allowEmptySignature) {
        if (sessionId == null || reply == null) {
            return;
        }
        var replyId = reply.responseId();
        if (replyId == null || replyId.isEmpty() || reply.responseModel() != null) {
            return;
        }
        var keys = new ArrayList<>(full.rowKeys());
        keys.addAll(replyRowKeys(reply, request, oauth, allowEmptySignature));
        AnthropicThreadState.put(sessionId, new AnthropicThreadState.Entry(
            keys.size(),
            AnthropicThreadHash.ofRows(provider, baseUrl, full.settingsKey(), keys, 0),
            replyId));
    }

    /**
     * 本轮可以走 threads 的会话号（pi {@code :864-866} 的 {@code cacheSessionId} 门）；
     * {@code null} ≙ 本轮不发 {@code thread}（但可能仍照发 threads beta）。
     *
     * <p>⚠️ 与亲和头那道 Bearer 门**不是同一道**：它只属于亲和头（pi {@code :992-1031}），
     * threads 用的是同一个 {@code cacheSessionId}（{@code :625}），OAuth 车道同样要能续线程。</p>
     */
    static String sessionOf(Boolean override, String sessionId, boolean retentionNone,
                            StreamRequest request, String baseUrl) {
        if (retentionNone) {
            return null;
        }
        var provider = request.modelId() == null ? null : request.modelId().provider();
        return supported(override, provider, baseUrl) ? sessionId : null;
    }

    /**
     * 本轮终局助手消息 ＋ 身份（pi 的 {@code output}）—— 记账要用它算回复行指纹。
     *
     * <p>身份盖法与 {@link AbstractChatApi} 的事件出口同式（{@code apiName} ／ {@code request}
     * 的 provider 与 model）：{@code transformMessages} 的 assistant 分支按「同 api／同 provider／
     * 同 model」分岔，不盖章会算出与下一轮不同的指纹；再经 {@code fromPartial} 投成转录层的
     * {@link Message.AssistantMessage}（pi 的 {@code output} 本来就是那个形状）。</p>
     */
    static Message.AssistantMessage replyMessage(
            com.pijava.ai.message.AssistantMessage snapshot, StreamRequest request,
            String apiName) {
        var identified = snapshot.withIdentity(apiName,
            request.model() == null ? null : request.modelId().provider(),
            request.model() == null ? null : request.modelId().modelName(),
            java.time.Instant.now());
        return Message.AssistantMessage.fromPartial(identified);
    }

    /** 前缀哈希（设置半边 ＋ 前 {@code prefixCount} 行指纹）。 */
    private static String prefixHash(String provider, String baseUrl,
                                     AnthropicRequestBuilder.BuiltParams full, int prefixCount) {
        return AnthropicThreadHash.ofRows(provider, baseUrl, full.settingsKey(),
            full.rowKeys(), prefixCount);
    }

    /**
     * 回复的逐行指纹（pi {@code :462-472}：{@code transformMessages([output])} 后再
     * {@code convertMessages}，**只留 assistant 行**）。
     *
     * <p>⚠️ 必须先 {@code withIdentity} 再 transform（车道已做）：{@code transformMessages}
     * 的 assistant 分支按「同 api／同 provider／同 model」分岔，而快照本身不带身份 ——
     * 不盖章就会算出与下一轮不同的行指纹。</p>
     */
    static List<String> replyRowKeys(Message.AssistantMessage reply, StreamRequest request,
                                     boolean oauth, boolean allowEmptySignature) {
        var transformed = com.pijava.ai.api.TransformMessages.apply(List.of(reply),
            request.modelId(), "anthropic-messages", request.model(),
            AnthropicToolCallIds.create());
        var keys = new ArrayList<String>(1);
        for (var msg : transformed) {
            if (!(msg instanceof Message.AssistantMessage assistant)) {
                continue;
            }
            var blocks = AnthropicMessageConverter.toBlockParams(
                assistant, allowEmptySignature, false, oauth);
            if (!blocks.isEmpty()) {
                keys.add(AnthropicConversationWire.rowKey(
                    com.anthropic.models.messages.MessageParam.Role.ASSISTANT, blocks));
            }
        }
        return keys;
    }
}
