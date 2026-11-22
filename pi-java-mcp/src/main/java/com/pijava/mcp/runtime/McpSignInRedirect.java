package com.pijava.mcp.runtime;

import java.net.URI;

import org.jspecify.annotations.Nullable;

/**
 * The browser step of a sign-in: plain data, so an application can persist it and complete the
 * sign-in in another request (pi {@code McpSignInRedirect}, {@code sign-in.ts:40-51}).
 *
 * @param authorizationUrl the URL to open in the user's browser
 * @param redirectUrl the redirect URI of the authorization request; the response must arrive on it
 * @param state the {@code state} the authorization response must carry
 * @param scope the scope requested, if any; saved with the tokens when the token response names none
 */
public record McpSignInRedirect(URI authorizationUrl, String redirectUrl, String state,
                                @Nullable String scope) implements McpSignInStart {
}
