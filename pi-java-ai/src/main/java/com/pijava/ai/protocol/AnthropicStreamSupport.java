package com.pijava.ai.protocol;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Anthropic 车道构造期/收尾期的无状态小工具：{@link ApiOptions} extra 读取 ＋ 流尾三段判。
 * 从 {@link AnthropicMessagesApi} 抽出（守「文件 ≤ 500 行」）。
 */
final class AnthropicStreamSupport {

    private AnthropicStreamSupport() {
    }

    /** 从 extra 读 sessionId（B103）；blank ⇒ null。与 completions 车道同形。 */
    static String sessionIdOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("sessionId");
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    /**
     * 从 {@code ApiOptions.extra} 读 {@code cacheRetention}。
     *
     * <p>两种来源都认：直接塞 {@link CacheRetention} 值（java 内代码走这条），或塞线格字符串
     * （{@code models.json}／CLI 直给走这条）。非法字符串经 {@link CacheRetention#parse} 落成
     * <b>缺席</b>（不是 {@code SHORT}），好让 {@code PI_CACHE_RETENTION} 仍能生效。</p>
     */
    static Optional<CacheRetention> retentionOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("cacheRetention");
        if (raw instanceof CacheRetention retention) {
            return Optional.of(retention);
        }
        return CacheRetention.parse(raw == null ? null : raw.toString());
    }

    /** B99 裁决③：从 ApiOptions.extra["headers"] 取名集合（无 ⇒ 空集）。 */
    static Set<String> extraHeaderNames(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("headers");
        if (!(raw instanceof Map<?, ?> map)) {
            return Set.of();
        }
        var names = new HashSet<String>();
        map.keySet().forEach(key -> {
            if (key != null) {
                names.add(key.toString());
            }
        });
        return Set.copyOf(names);
    }

    /**
     * 一条流的 stop reason 状态 —— pi 累加器里的 {@code output.stopReason} 与
     * {@code output.errorMessage} 两个字段（{@code anthropic-messages.ts:526} 起）。
     *
     * <p>初值 {@link StopReason#PENDING} 是 pi 的哨兵值：收尾据此区分「流看完了但**一个**
     * stop reason 都没观测到」与「正常结束」。</p>
     *
     * <p>{@code errored} 是 pi-java 侧新增的（pi 靠 throw 逃逸循环，不需要这个位）：
     * 见 {@link #submitTail} 的说明。</p>
     */
    static final class StopState {
        StopReason reason = StopReason.PENDING;
        String errorMessage;
        boolean errored;
    }

    /**
     * 收尾三段判（pi {@code anthropic-messages.ts:779-804} 的四段去掉首段 abort）。
     *
     * <pre>
     * signal aborted        → throw "Request was aborted"                        // 车道层不可达
     * stopReason == pending → throw "Anthropic stream ended without a stop reason"
     * stopReason == aborted/error → throw (errorMessage || "An unknown error occurred")
     * 其余                  → push {type:"done", reason: stopReason}; stream.end()
     * </pre>
     *
     * <p>⚠️ 与 pi 的差别不在判序、在 <b>throw 的去处</b>：pi 抛进自己的 {@code catch}，
     * 那里发 {@code {type:"error"}} 并结束流 ⇒ 一条流<b>只有一个</b>终局事件。pi-java 的
     * 等价物是 {@code emitError}，故前三个分支必须在这里返回 —— 循环内已发 error 时
     * （{@code stop.errored}）直接返回，不补第二个终局事件。</p>
     */
    static void submitTail(StreamPartialBuilder builder,
                           SubmissionPublisher<StreamEvent> publisher,
                           StopState stop,
                           AnthropicResponseExtras responseExtras) {
        if (stop.errored) {
            return; // 循环内已发过 error：pi 在那一层 throw，收尾不跑
        }
        if (stop.reason == StopReason.PENDING) {
            publisher.submit(builder.emitError(StopReason.ERROR,
                    new IllegalStateException("Anthropic stream ended without a stop reason")));
            return;
        }
        if (stop.reason == StopReason.ERROR) {
            publisher.submit(builder.emitError(StopReason.ERROR, new IllegalStateException(
                    stop.errorMessage != null ? stop.errorMessage : "An unknown error occurred")));
            return;
        }
        // B148（pi anthropic-messages.ts:871-885）：成功收尾时把 input_transformations
        // 落成诊断（pending/error/aborted 的 throw 都在这之前，错误路不产出）。
        if (responseExtras.hasTransformations()) {
            builder.appendDiagnostic(new AssistantMessageDiagnostic(
                "anthropic_input_transformations",
                System.currentTimeMillis(),
                null,
                responseExtras.transformationsDetails()));
        }
        publisher.submit(builder.emitDone(stop.reason));
    }
}
