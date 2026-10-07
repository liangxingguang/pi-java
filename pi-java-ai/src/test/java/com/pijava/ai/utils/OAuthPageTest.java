package com.pijava.ai.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code packages/ai/src/utils/oauth-page.ts} 的两个页面。
 */
class OAuthPageTest {

    @Test
    void successPageCarriesItsHeadingsAndMessage() {
        var html = OAuthPage.success("Signed in to the MCP server.");
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("<title>Authentication successful</title>");
        assertThat(html).contains("<h1>Authentication successful</h1>");
        assertThat(html).contains("<p>Signed in to the MCP server.</p>");
    }

    @Test
    void errorPageShowsItsDetailsOnlyWhenItHasSome() {
        var withDetails = OAuthPage.error("Authentication failed", "invalid_client");
        assertThat(withDetails).contains("<title>Authentication failed</title>");
        assertThat(withDetails).contains("<div class=\"details\">invalid_client</div>");

        assertThat(OAuthPage.error("Authentication failed", null)).doesNotContain("class=\"details\"");
    }

    @Test
    void escapesEveryValueInTheOrderThatKeepsEntitiesIntact() {
        var html = OAuthPage.error("<b>&\"'</b>", "<i>&");
        assertThat(html).contains("<p>&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;</p>");
        assertThat(html).contains("<div class=\"details\">&lt;i&gt;&amp;</div>");
    }

    @Test
    void theStylesheetSurvivesFormatting() {
        // `String.format` would eat a single percent sign; the template doubles it.
        assertThat(OAuthPage.success("x")).contains("width: 100%;").contains("min-height: 100vh;");
    }

    @Test
    void theLogoIsInlined() {
        assertThat(OAuthPage.success("x")).contains("<svg xmlns=\"http://www.w3.org/2000/svg\"");
    }
}
