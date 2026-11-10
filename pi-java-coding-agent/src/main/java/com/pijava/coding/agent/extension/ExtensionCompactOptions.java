package com.pijava.coding.agent.extension;

import java.util.function.Consumer;

import com.pijava.agent.compaction.CompactionResult;

/**
 * docs/50 §2.1：pi {@code CompactOptions} 的同步方言。
 *
 * <p>pi 的 {@code compact()} 返回 Promise；Java 的压缩为同步调用，回调在返回前
 * 触发。示例只依赖「压缩完成后通知」，可观测结局一致（§3.2 留痕）。</p>
 *
 * @param customInstructions 自定义压缩指令（{@code null} ≙ 未给）
 * @param onComplete         成功回调（可 {@code null}）
 * @param onError            失败回调（可 {@code null}；缺省时异常原样上抛）
 */
public record ExtensionCompactOptions(
        String customInstructions,
        Consumer<CompactionResult> onComplete,
        Consumer<RuntimeException> onError) {

    /** 无指令、无回调。 */
    public static ExtensionCompactOptions none() {
        return new ExtensionCompactOptions(null, null, null);
    }
}
