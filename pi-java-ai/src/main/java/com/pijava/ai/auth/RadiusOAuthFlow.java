package com.pijava.ai.auth;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Radius browser/PKCE and device-code login flows. */
public final class RadiusOAuthFlow {

    private static final String CALLBACK_PATH = "/oauth/callback";
    private final RadiusOAuthConfig config;
    private final RadiusOAuthHttp http;
    private final int callbackPort;
    private final boolean autoOpenBrowser;

    /** Production flow: browser callback fixed at 127.0.0.1:1456. */
    public RadiusOAuthFlow(RadiusOAuthConfig config) {
        this(config, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), 1456, true);
    }

    /** Test seam: custom gateway HTTP/callback port; no desktop browser is opened. */
    RadiusOAuthFlow(RadiusOAuthConfig config, HttpClient http, int callbackPort) {
        this(config, http, callbackPort, false);
    }

    private RadiusOAuthFlow(RadiusOAuthConfig config, HttpClient http,
                            int callbackPort, boolean autoOpenBrowser) {
        this.config = config;
        this.http = new RadiusOAuthHttp(config, http);
        this.callbackPort = callbackPort;
        this.autoOpenBrowser = autoOpenBrowser;
    }

    /** Select and execute browser or device-code login. */
    public OAuthCredential login(OAuthInteraction interaction) throws IOException {
        var method = interaction.select("Sign in to " + config.name() + ":", List.of(
            new OAuthInteraction.LoginOption("browser", "Sign in with browser (recommended)"),
            new OAuthInteraction.LoginOption("device-code",
                "Sign in with device code (when signing in from another device)")));
        return switch (method) {
            case "browser" -> loginWithBrowser(interaction);
            case "device-code" -> loginWithDeviceCode(interaction);
            default -> throw new IOException("Unknown " + config.name() + " sign-in method: " + method);
        };
    }

    /** Refresh an existing Radius OAuth credential. */
    public OAuthCredential refresh(OAuthCredential credential) throws IOException {
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "refresh_token");
        params.put("client_id", "pi-gateway");
        params.put("refresh_token", credential.refreshToken());
        return http.token(params);
    }

    private OAuthCredential loginWithDeviceCode(OAuthInteraction interaction) throws IOException {
        var device = http.requestDevice();
        interaction.notify("Open this URL in your browser and enter the code:\n  "
            + device.verificationUri() + "\nUser code: " + device.userCode());
        return DeviceCodePoller.poll(device.intervalSeconds(), device.expiresInSeconds(), () -> {
            try {
                return new DeviceCodePoller.PollResult.Complete<>(http.token(Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", "pi-gateway", "device_code", device.code())));
            } catch (RadiusOAuthHttp.OAuthResponseError e) {
                return switch (e.oauthError() == null ? "" : e.oauthError()) {
                    case "authorization_pending" -> new DeviceCodePoller.PollResult.Pending<>();
                    case "slow_down" -> new DeviceCodePoller.PollResult.SlowDown<>(0);
                    case "expired_token" -> new DeviceCodePoller.PollResult.Failed<>(
                        "Device authorization expired.");
                    case "access_denied" -> new DeviceCodePoller.PollResult.Failed<>(
                        "Device authorization was denied.");
                    default -> throw e;
                };
            }
        });
    }

    private OAuthCredential loginWithBrowser(OAuthInteraction interaction) throws IOException {
        var authorizationEndpoint = http.loadAuthorizationEndpoint();
        var pkce = Pkce.generate();
        var state = UUID.randomUUID().toString();
        var redirectUriHolder = new String[1];
        var server = LoopbackCallbackServer.<OAuthCredential>start(new LoopbackCallbackServer.Options<>(
            "Radius", "127.0.0.1", callbackPort, CALLBACK_PATH, null, state, null,
            (code, query) -> http.token(Map.of(
                "grant_type", "authorization_code",
                "client_id", "pi-gateway",
                "redirect_uri", redirectUriHolder[0],
                "code", code,
                "code_verifier", pkce.verifier()))));
        redirectUriHolder[0] = server.redirectUri();
        var redirectUri = redirectUriHolder[0];
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("response_type", "code");
            params.put("client_id", "pi-gateway");
            params.put("redirect_uri", redirectUri);
            params.put("scope", "gateway offline_access");
            params.put("code_challenge", pkce.challenge());
            params.put("code_challenge_method", "S256");
            params.put("handoff", "url");
            params.put("state", state);
            var endpoint = authorizationEndpoint.contains("?")
                ? authorizationEndpoint.substring(0, authorizationEndpoint.indexOf('?'))
                : authorizationEndpoint;
            var authorizeUrl = endpoint + "?" + OAuthFlow.formEncode(params);
            interaction.notify("Listening for OAuth callback on " + redirectUri);
            interaction.notify(authorizeUrl);
            if (autoOpenBrowser) {
                OAuthFlow.openBrowser(authorizeUrl);
            }
            try {
                var credential = server.outcome().join();
                if (credential == null) {
                    throw new IOException("OAuth callback did not complete.");
                }
                return credential;
            } catch (CompletionException e) {
                if (e.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException(e.getMessage(), e);
            }
        } finally {
            server.close();
        }
    }
}
