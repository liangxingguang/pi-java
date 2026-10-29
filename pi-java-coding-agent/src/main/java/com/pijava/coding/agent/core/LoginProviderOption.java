package com.pijava.coding.agent.core;

import org.jspecify.annotations.Nullable;

/** One provider/authentication-method choice in the login catalog. */
public record LoginProviderOption(
    String id,
    String name,
    LoginAuthType authType,
    @Nullable String status,
    boolean subscription
) {}
