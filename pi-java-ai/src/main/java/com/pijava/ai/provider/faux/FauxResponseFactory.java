package com.pijava.ai.provider.faux;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;

/**
 * docs/20 §3.2：pi {@code FauxResponseFactory}（{@code providers/faux.ts:109-114}）。
 *
 * <p>pi 的工厂可以是 {@code async}；Java 只提供同步函数，测试可自行阻塞等待
 * （语义等价于 {@code await}，登记 D214）。</p>
 */
@FunctionalInterface
public interface FauxResponseFactory {

    /**
     * 按请求上下文现场生成一条助手消息。
     *
     * @param context 归一后的转录
     * @param options 本次请求的选项（{@code null} 不会发生，沿用流入口的值）
     * @param state   provider 状态计数
     * @param model   请求的模型
     * @return 预置响应（其身份与 usage 会被核心改写）
     */
    AssistantMessage create(TranscriptContext context, ApiOptions options,
                            FauxProviderState state, ModelInfo model);
}
