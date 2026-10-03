package com.pijava.ai.provider;

import com.pijava.ai.catalog.BuiltinCatalog;

/**
 * DeepSeek provider — reuses the OpenAI adapter with a different base URL
 * and the static {@link BuiltinCatalog#deepseekModels()} catalog.
 *
 * <p>运行时目录更新由统一的远程目录机制覆盖（coding-agent 装配
 * RemoteCatalogProvider，docs/70），不再有 provider 私有的 models URL。</p>
 */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            "DEEPSEEK_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinCatalog.deepseekModels());
    }
}
