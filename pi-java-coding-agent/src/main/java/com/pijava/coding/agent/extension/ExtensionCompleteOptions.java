package com.pijava.coding.agent.extension;

import java.util.Optional;
import java.util.OptionalInt;

import com.pijava.agent.harness.CompactionAbortToken;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * docs/50 §2.1：pi {@code ModelRegistry.complete} 的选项。
 *
 * @param maxTokens      输出上限（空 ≙ 模型默认）
 * @param reasoning      思考级别（空 ≙ 不开思考）
 * @param cacheRetention 缓存保留档（空 ≙ provider 缺省链）
 * @param sessionId      会话亲和 id（空 ≙ 未给）
 * @param signal         中止令牌（{@code null} ≙ pi 的 undefined）
 */
public record ExtensionCompleteOptions(
        OptionalInt maxTokens,
        Optional<ThinkingLevel> reasoning,
        Optional<CacheRetention> cacheRetention,
        Optional<String> sessionId,
        @org.jspecify.annotations.Nullable CompactionAbortToken signal) {

    /** 全缺席。 */
    public static ExtensionCompleteOptions defaults() {
        return new ExtensionCompleteOptions(OptionalInt.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), null);
    }

    /** pi {@code options.signal?.aborted}。 */
    public boolean aborted() {
        return signal != null && signal.isAborted();
    }
}
