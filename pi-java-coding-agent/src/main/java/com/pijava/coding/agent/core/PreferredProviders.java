package com.pijava.coding.agent.core;

import java.util.List;

/**
 * Preferred partner providers and the order provider lists show them in
 * (pi {@code core/preferred-providers.ts}, anchor {@code a00a5048d}).
 *
 * <p>Kept free of UI code so every provider list ({@code /login}, {@code /logout},
 * {@code auth login}) shares one ordering.</p>
 */
public final class PreferredProviders {

    /** Ids of the preferred partner providers, most preferred first. */
    public static final List<String> PREFERRED_PROVIDER_IDS = List.of("radius", "openrouter");

    private PreferredProviders() {}

    /** Whether the provider is a preferred partner. */
    public static boolean isPreferredProvider(String providerId) {
        return PREFERRED_PROVIDER_IDS.contains(providerId);
    }

    /**
     * Orders providers: preferred partners first, in their order, then all others
     * alphabetically by display name (pi {@code compareProviders}).
     *
     * <p>Equal ranks fall back to the display name; a stable list sort then keeps the
     * insertion order, so one provider's account entry stays before its API-key entry.</p>
     */
    public static int compare(String idA, String nameA, String idB, String nameB) {
        var rankA = preferredRank(idA);
        var rankB = preferredRank(idB);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        var byName = nameA.compareToIgnoreCase(nameB);
        return byName != 0 ? byName : nameA.compareTo(nameB);
    }

    private static int preferredRank(String providerId) {
        var index = PREFERRED_PROVIDER_IDS.indexOf(providerId);
        return index == -1 ? PREFERRED_PROVIDER_IDS.size() : index;
    }
}
