package com.pijava.coding.agent.core;

import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/** Shared provider-login fixtures. */
final class ProviderLoginFixtures {

    private ProviderLoginFixtures() {}

    static LoginProviderOption oauth(String id, String name) {
        return new LoginProviderOption(id, name, LoginAuthType.OAUTH, null, true);
    }

    static LoginProviderOption apiKey(String id, String name) {
        return new LoginProviderOption(id, name, LoginAuthType.API_KEY, null, false);
    }

    static ModelInfo radiusModel(String id) {
        return new ModelInfo(ModelId.of("radius", id), id, Set.of(),
            1_000, 100, false, PricingInfo.UNKNOWN);
    }
}
