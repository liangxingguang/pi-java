package com.pijava.evals.conformance;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.ai.api.ChatApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalContext;
import com.pijava.evals.api.EvalResult;
import com.pijava.evals.api.EvalSuite;

/**
 * Catalog-level checks for the 17 built-in providers.
 */
public final class ProviderCatalogConformance implements EvalSuite {

    @Override
    public String name() {
        return "provider-catalog-conformance";
    }

    @Override
    public List<EvalCase> cases() {
        return List.of(
            evalCase("seventeen-providers", ctx -> {
                if (ProviderCatalog.all().size() != 17) {
                    throw new AssertionError("expected 17 providers, got "
                        + ProviderCatalog.all().size());
                }
            }),
            evalCase("unique-names", ctx -> {
                var names = new HashSet<String>();
                for (Provider provider : ProviderCatalog.all()) {
                    if (!names.add(provider.name())) {
                        throw new AssertionError("duplicate provider name " + provider.name());
                    }
                }
            }),
            evalCase("config-self-consistent", ctx -> {
                for (Provider provider : ProviderCatalog.all()) {
                    if (!(provider instanceof ConfigurableProvider configurable)) {
                        throw new AssertionError(provider.name() + " is not configurable");
                    }
                    var config = configurable.providerConfig();
                    if (config.name().isBlank() || config.defaultBaseUrl().isBlank()) {
                        throw new AssertionError(provider.name() + " missing name/baseUrl");
                    }
                    // 协议一致性仅对 chat provider 生效；图片专用 provider（如
                    // openrouter-images）的 supportedProtocols 为空是合法的。
                    if (provider.supportedApis().contains(ChatApi.class)
                            && !config.supportedProtocols().contains(config.defaultProtocol())) {
                        throw new AssertionError(provider.name() + " defaultProtocol not supported");
                    }
                    if (!"ollama".equals(provider.name())
                            && (config.apiKeyEnvVar() == null || config.apiKeyEnvVar().isBlank())) {
                        throw new AssertionError(provider.name() + " missing apiKeyEnvVar");
                    }
                }
            })
        );
    }

    private static EvalCase evalCase(String name, Consumer<EvalContext> body) {
        return new EvalCase() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public EvalResult run(EvalContext ctx) {
                var started = System.nanoTime();
                try {
                    body.accept(ctx);
                    return EvalResult.passed(name, Duration.ofNanos(System.nanoTime() - started));
                } catch (Exception | AssertionError e) {
                    var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    return EvalResult.failed(name, message, Duration.ofNanos(System.nanoTime() - started));
                }
            }
        };
    }
}
