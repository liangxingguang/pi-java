package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Locale;

import com.pijava.ai.provider.Provider;

/**
 * Provider id/name matching shared by the credential commands
 * (pi {@code core/provider-matching.ts}, anchor {@code a00a5048d}).
 */
public final class ProviderMatching {

    private ProviderMatching() {}

    /**
     * Match provider IDs and display names exactly, ignoring case and surrounding
     * whitespace in the reference.
     *
     * @param providers   the registered providers
     * @param providerRef the user-supplied id or display name
     * @return every provider whose id or name matches; empty when the reference is blank
     */
    public static List<Provider> findMatching(List<Provider> providers, String providerRef) {
        if (providerRef == null) {
            return List.of();
        }
        var normalized = providerRef.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return providers.stream()
            .filter(provider -> provider.name().toLowerCase(Locale.ROOT).equals(normalized)
                || provider.displayName().toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
    }
}
