package com.pijava.ai.provider;

import com.pijava.ai.catalog.BuiltinCatalog;

/**
 * DeepSeek provider — reuses the OpenAI adapter with a different base URL.
 *
 * <p>可选动态目录：设置环境变量 {@code DEEPSEEK_MODELS_URL} 指向自托管
 * pi 格式 models.json 时，运行时通过 {@link RemoteCatalog} 拉取并刷新模型清单；
 * 未设置则用静态 {@link BuiltinCatalog#deepseekModels()}。</p>
 */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    /** 自托管 models.json URL 的环境变量；未设置时保持静态目录。 */
    private static final String MODELS_URL_ENV = "DEEPSEEK_MODELS_URL";

    @Override
    protected ProviderConfig config() {
        var modelsUrl = System.getenv(MODELS_URL_ENV);
        if (modelsUrl != null && !modelsUrl.isBlank()) {
            return ProviderConfig.singleWithModelsUrl(
                "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
                "DEEPSEEK_API_KEY", Protocol.OPENAI_COMPLETIONS,
                BuiltinCatalog.deepseekModels(), modelsUrl);
        }
        return ProviderConfig.single(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            "DEEPSEEK_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinCatalog.deepseekModels());
    }
}
