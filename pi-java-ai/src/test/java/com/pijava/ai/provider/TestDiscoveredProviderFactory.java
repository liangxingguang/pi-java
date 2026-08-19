package com.pijava.ai.provider;

import java.util.List;

import com.pijava.ai.stream.StreamEvent;

/**
 * Test-only factory discovered via {@link java.util.ServiceLoader}.
 */
public final class TestDiscoveredProviderFactory implements ProviderFactory {

    /** Create the test provider registered as {@code test-discovered}. */
    @Override
    public Provider create() {
        return new FauxProvider("test-discovered", List.<StreamEvent>of(), 0);
    }

    /** @return {@code test-discovered} */
    @Override
    public String providerName() {
        return "test-discovered";
    }
}
