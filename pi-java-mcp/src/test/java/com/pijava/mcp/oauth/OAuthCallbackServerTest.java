package com.pijava.mcp.oauth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Callback server pages and state handling (pi oauth.test.ts clause 8). */
class OAuthCallbackServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void rendersPlainTextByDefault() throws Exception {
        var server = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            var pending = server.waitForCallback("s1");
            var response = get(server.redirectUrl() + "?code=abc&state=s1");
            assertThat(response.headers().firstValue("content-type"))
                    .contains("text/plain; charset=utf-8");
            assertThat(response.body())
                    .isEqualTo("Authorization complete. You may close this window.");
            assertThat(pending.join().code()).isEqualTo("abc");
            assertThat(pending.join().state()).isEqualTo("s1");
            assertThat(pending.join().iss()).isNull();
        } finally {
            server.close();
        }
    }

    @Test
    void carriesTheIssParameter() throws Exception {
        var server = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            var pending = server.waitForCallback("s1");
            get(server.redirectUrl() + "?code=abc&state=s1&iss=https%3A%2F%2Fas.example");
            assertThat(pending.join().iss()).isEqualTo("https://as.example");
        } finally {
            server.close();
        }
    }

    // #10302
    @Test
    void rejectsAResponseOnAnotherPathThanTheExpectedOne() throws Exception {
        var extra = "/callback/server-id";
        var server = OAuthCallbackServer.listen(new OAuthCallbackServerOptions(
                null, null, 0, null, List.of(extra), null, null));
        try {
            var origin = URI.create(server.redirectUrl()).getScheme() + "://"
                    + URI.create(server.redirectUrl()).getAuthority();
            var mixedUp = server.waitForCallback("s1", extra);
            var wrong = get(origin + "/callback?code=abc&state=s1");
            assertThat(wrong.statusCode()).isEqualTo(400);
            assertThatThrownBy(mixedUp::join)
                    .hasMessageContaining("arrived on another redirect URI");

            var pending = server.waitForCallback("s2", extra);
            var right = get(origin + extra + "?code=abc&state=s2");
            assertThat(right.statusCode()).isEqualTo(200);
            assertThat(pending.join().code()).isEqualTo("abc");
        } finally {
            server.close();
        }
    }

    @Test
    void rendersPagesThroughRenderPage() throws Exception {
        var pages = new ArrayList<OAuthCallbackPage>();
        var server = OAuthCallbackServer.listen(new OAuthCallbackServerOptions(
                null, null, 0, null, null, null, page -> {
                    pages.add(page);
                    return page instanceof OAuthCallbackPage.Ok
                            ? "<p>ok</p>"
                            : "<p>" + ((OAuthCallbackPage.Failed) page).message() + "</p>";
                }));
        try {
            var denied = server.waitForCallback("s1");
            var failure = get(server.redirectUrl()
                    + "?error=access_denied&error_description=Denied&state=s1");
            assertThat(failure.headers().firstValue("content-type"))
                    .contains("text/html; charset=utf-8");
            assertThat(failure.headers().firstValue("cache-control")).contains("no-store");
            assertThatThrownBy(denied::join).hasMessageContaining("Denied");
            assertThat(pages.getLast()).isEqualTo(
                    new OAuthCallbackPage.Failed(
                            "Authorization failed. You may close this window.", "Denied"));

            var pending = server.waitForCallback("s2");
            var success = get(server.redirectUrl() + "?code=abc&state=s2");
            assertThat(success.body()).isEqualTo("<p>ok</p>");
            assertThat(pending.join().code()).isEqualTo("abc");
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsUnknownStateUnknownPathAndMissingCode() throws Exception {
        var server = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            assertThat(get(URI.create(server.redirectUrl()).getScheme() + "://"
                    + URI.create(server.redirectUrl()).getAuthority()
                    + "/nowhere?code=abc&state=s1").statusCode()).isEqualTo(404);
            assertThat(get(server.redirectUrl() + "?code=abc&state=unknown").statusCode())
                    .isEqualTo(400);
            assertThat(get(server.redirectUrl() + "?state=s1").statusCode()).isEqualTo(400);
            assertThat(get(server.redirectUrl() + "?code=abc").statusCode()).isEqualTo(400);
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsADuplicatePendingState() throws Exception {
        var server = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        try {
            server.waitForCallback("s1");
            assertThatThrownBy(() -> server.waitForCallback("s1"))
                    .hasMessage("OAuth state is already pending");
        } finally {
            server.close();
        }
    }

    @Test
    void closingFailsPendingRequests() throws Exception {
        var server = OAuthCallbackServer.listen(OAuthCallbackServerOptions.defaults());
        var pending = server.waitForCallback("s1");
        server.close();
        assertThatThrownBy(pending::join).hasMessageContaining("OAuth callback server closed");
    }

    @Test
    void timesOutAPendingRequest() throws Exception {
        var server = OAuthCallbackServer.listen(new OAuthCallbackServerOptions(
                null, null, 0, null, null, 50L, null));
        try {
            var pending = server.waitForCallback("s1");
            assertThatThrownBy(pending::join).hasMessageContaining("OAuth callback timed out");
        } finally {
            server.close();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
