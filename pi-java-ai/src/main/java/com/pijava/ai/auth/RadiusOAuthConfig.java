package com.pijava.ai.auth;

/** Radius OAuth display name and normalized gateway. */
public record RadiusOAuthConfig(String name, String gateway) {

    /** Normalize scheme and trailing slashes once at the boundary. */
    public RadiusOAuthConfig {
        if (!gateway.matches("(?i)^https?://.*")) {
            gateway = "https://" + gateway;
        }
        while (gateway.endsWith("/")) {
            gateway = gateway.substring(0, gateway.length() - 1);
        }
    }
}
