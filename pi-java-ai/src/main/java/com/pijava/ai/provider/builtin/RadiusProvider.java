package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.provider.RadiusGatewayClient;
import com.pijava.ai.protocol.PiMessagesApi;

/** Radius pi-messages gateway with a persisted, dynamically replaced account catalog. */
public final class RadiusProvider extends ConfigurableProvider {

    public static final String DEFAULT_GATEWAY = "https://radius.pi.dev";

    private final ProviderConfig config = ProviderConfig.single(
        "radius", "Radius", DEFAULT_GATEWAY, "RADIUS_API_KEY", Protocol.PI_MESSAGES, ModelCatalog.empty());
    private final RadiusGatewayClient gateway;
    private final Supplier<Optional<String>> credential;
    private volatile List<ModelInfo> dynamicModels;

    /** Production provider using the default gateway and shared credential chain. */
    public RadiusProvider() {
        this(new RadiusGatewayClient(DEFAULT_GATEWAY,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()),
            () -> Credentials.resolveApiKey("radius"));
    }

    /** Dependency-injection seam shared by production and test rigs. */
    RadiusProvider(RadiusGatewayClient gateway, Supplier<Optional<String>> credential) {
        this.gateway = gateway;
        this.credential = credential;
    }

    @Override
    protected ProviderConfig config() {
        return config;
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return new PiMessagesApi(options, "RADIUS_API_KEY");
    }

    @Override
    public List<ModelInfo> getModels() {
        var current = dynamicModels;
        return current == null ? config.builtinModels().listModels() : current;
    }

    @Override
    public void refreshModels(RefreshModelsContext context) {
        if (context.stored().isPresent()) {
            var restored = context.stored().get().models().stream()
                .filter(model -> model.id().provider().equals(name())).toList();
            boolean accepted = context.publish().publish(
                ModelsPublication.updateOnly(() -> dynamicModels = restored));
            if (!accepted) {
                return;
            }
        }
        if (!context.allowNetwork()) {
            return;
        }
        try {
            var loaded = gateway.load(credential.get().orElse(null));
            var refreshed = loaded.models();
            var entry = new ModelsStoreEntry(refreshed, null, Instant.now(), null);
            context.publish().publish(ModelsPublication.persistAndUpdate(
                entry, () -> dynamicModels = refreshed));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
