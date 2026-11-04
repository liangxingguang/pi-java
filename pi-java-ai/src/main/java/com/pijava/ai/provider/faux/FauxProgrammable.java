package com.pijava.ai.provider.faux;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.Provider;

/**
 * docs/20 §3.2：pi {@code FauxProviderHandle}（{@code providers/faux.ts:155-166}）。
 *
 * <p>由 {@link FauxProvider#programmable(FauxProviderOptions)} 创建；同一个实例既是
 * {@link Provider}，也暴露模型、状态与响应队列。{@code provider} 惰性构建，避免
 * 与 {@link FauxProvider} 的循环依赖。</p>
 */
public final class FauxProgrammable {

    private final FauxCore core;
    private volatile FauxProvider provider;

    /** 按 pi 选项创建可编程核心（不触碰网络）。 */
    public FauxProgrammable(FauxProviderOptions options) {
        this.core = new FauxCore(options);
    }

    /** 可直接注册进 provider 注册表的 {@link Provider}。 */
    public Provider provider() {
        var current = provider;
        if (current == null) {
            synchronized (this) {
                current = provider;
                if (current == null) {
                    current = new FauxProvider(this);
                    provider = current;
                }
            }
        }
        return current;
    }

    /** provider 名（pi {@code core.provider}）。 */
    public String providerName() {
        return core.provider();
    }

    /** 当前模型表（首个是 {@link #getModel()} 的默认模型）。 */
    public List<ModelInfo> models() {
        return core.models();
    }

    /** 默认模型（pi {@code models[0]}）。 */
    public ModelInfo getModel() {
        return core.getModel();
    }

    /** 按模型 id 查找；未知 id 返回 {@link Optional#empty()}。 */
    public Optional<ModelInfo> getModel(String modelId) {
        return core.getModel(modelId);
    }

    /** 整体替换响应队列。 */
    public void setResponses(List<FauxResponseStep> responses) {
        core.setResponses(responses);
    }

    /** 追加响应。 */
    public void appendResponses(List<FauxResponseStep> responses) {
        core.appendResponses(responses);
    }

    /** 队列中尚未消费的响应数。 */
    public int pendingResponseCount() {
        return core.pendingResponseCount();
    }

    /** 调用计数与取消记录。 */
    public FauxProviderState state() {
        return core.state();
    }

    /** 核心 api 字面量（默认随机 {@code faux:<…>}）。 */
    public String api() {
        return core.api();
    }

    /** 创建一个绑定本核心的 {@link ChatApi}（{@link FauxProvider#createApi} 的内部入口）。 */
    public ChatApi newApi() {
        return new FauxChatApi(this);
    }

    FauxCore core() {
        return core;
    }
}
