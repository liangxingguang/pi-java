package com.pijava.coding.agent.core;

import com.pijava.ai.auth.OAuthInteraction;

/** Interactive callbacks used by both API-key and OAuth login. */
public interface LoginInteraction extends OAuthInteraction {

    /** Ask for a secret without exposing its value to logs or notifications. */
    String secret(String message);
}
