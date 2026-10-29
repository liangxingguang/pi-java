package com.pijava.coding.agent.core;

import java.io.IOException;

/** Blocking provider-login boundary; hosts run it away from their UI thread. */
@FunctionalInterface
public interface LoginExecutor {

    /** Save credentials, refresh that provider, and apply post-login model selection. */
    ProviderLoginService.LoginSuccess login(
        LoginProviderOption option, LoginInteraction interaction) throws IOException;
}
