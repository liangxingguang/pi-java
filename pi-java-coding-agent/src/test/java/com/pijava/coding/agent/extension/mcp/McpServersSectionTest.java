package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code renderServersSection}（{@code index.ts:186-217}）。
 */
class McpServersSectionTest {

    private static McpServerEntry entry(String name, String description,
                                        Map<String, McpExposure> toolExposure,
                                        McpExposure exposure, Boolean enabled) {
        return new McpServerEntry(name,
                new McpServerConfig.Http(null, exposure, description, toolExposure, enabled, null,
                        "http://example.test/mcp", null, null, null),
                Path.of("mcp.json"), McpScope.GLOBAL, null);
    }

    private static McpServerEntry codemode(String name, String description) {
        return entry(name, description, null, null, null);
    }

    private static McpServerListing listing(McpServerEntry entry, String instructions) {
        return new McpServerListing(entry, instructions);
    }

    // -------------------------------------------------------------- filtering

    @Test
    void noServersMeansNoSection() {
        assertThat(McpServersSection.render(List.of())).isNull();
    }

    @Test
    void aServerWithOnlyDirectToolsIsNotListed() {
        var direct = entry("files", null, null, McpExposure.DIRECT, null);
        assertThat(McpServersSection.render(List.of(listing(direct, null)))).isNull();
    }

    @Test
    void aDisabledServerIsNotListed() {
        var disabled = entry("files", null, null, null, false);
        assertThat(McpServersSection.render(List.of(listing(disabled, null)))).isNull();
    }

    @Test
    void aHiddenServerIsNotListed() {
        var hidden = entry("files", null, null, McpExposure.HIDDEN, null);
        assertThat(McpServersSection.render(List.of(listing(hidden, null)))).isNull();
    }

    @Test
    void aPerToolOverrideCanMakeAServerIndirect() {
        var mostlyDirect = entry("files", null, Map.of("echo", McpExposure.CODEMODE),
                McpExposure.DIRECT, null);
        assertThat(McpServersSection.render(List.of(listing(mostlyDirect, null))))
                .isNotNull();
    }

    // ------------------------------------------------------------------ lines

    @Test
    void serversAreListedInNameOrder() {
        var section = McpServersSection.render(List.of(
                listing(codemode("zebra", null), null),
                listing(codemode("alpha", null), null)));
        assertThat(section).isEqualTo("MCP servers whose tools are not declared to you."
                + " Call the tools of `codemode` servers from codemode scripts."
                + "\n- mcp__alpha (codemode)"
                + "\n- mcp__zebra (codemode)");
    }

    @Test
    void theIntroOnlyExplainsTheWaysThatAreUsed() {
        var deferredOnly = entry("d", null, null, McpExposure.DEFERRED, null);
        assertThat(McpServersSection.render(List.of(listing(deferredOnly, null))))
                .startsWith("MCP servers whose tools are not declared to you."
                        + " Load the tools of `tool_search` servers with `tool_search`.");

        var codemodeOnly = McpServersSection.render(List.of(listing(codemode("c", null), null)));
        assertThat(codemodeOnly).contains("Call the tools of `codemode` servers from codemode scripts.");
        assertThat(codemodeOnly).doesNotContain("tool_search` servers with");

        var both = McpServersSection.render(List.of(
                listing(codemode("c", null), null), listing(deferredOnly, null)));
        assertThat(both).contains("codemode scripts").contains("with `tool_search`.");
    }

    @Test
    void aDescriptionIsAppendedAfterAColon() {
        var section = McpServersSection.render(List.of(listing(codemode("files", "A docs server"), null)));
        assertThat(section).endsWith("- mcp__files (codemode): A docs server");
    }

    @Test
    void onlyTheFirstLineOfADescriptionIsUsed() {
        var section = McpServersSection.render(List.of(
                listing(codemode("files", "first\nsecond"), null)));
        assertThat(section).endsWith(": first");
        assertThat(section).doesNotContain("second");
    }

    @Test
    void theServerInstructionsStandInForAMissingDescription() {
        var section = McpServersSection.render(List.of(
                listing(codemode("files", null), "  From the server  ")));
        assertThat(section).endsWith(": From the server");
    }

    @Test
    void aBlankDescriptionFallsBackToTheInstructions() {
        var section = McpServersSection.render(List.of(
                listing(codemode("files", "   "), "From the server")));
        assertThat(section).endsWith(": From the server");
    }

    @Test
    void aLongDescriptionIsCutToThePerServerBudget() {
        var description = "x".repeat(600);
        var section = McpServersSection.render(List.of(listing(codemode("files", description), null)));
        var summary = section.substring(section.indexOf(": ") + 2);
        assertThat(summary).endsWith("…");
        assertThat(summary.length()).isLessThanOrEqualTo(McpServersSection.MAX_SERVER_DESCRIPTION_CHARS);
    }

    // ------------------------------------------------------------------ budget

    @Test
    void descriptionsShrinkBeforeAnyServerIsLeftOut() {
        // The budget shrinks every description first; servers are only dropped when their head
        // lines alone no longer fit, so forty servers still all appear.
        var servers = new ArrayList<McpServerListing>();
        for (var index = 0; index < 40; index++) {
            servers.add(listing(codemode("server" + index, "d".repeat(250)), null));
        }

        var section = McpServersSection.render(servers);

        assertThat(section.length()).isLessThanOrEqualTo(McpServersSection.MAX_SERVERS_SECTION_CHARS);
        assertThat(section).contains("- mcp__server0 (codemode):")
                .contains("- mcp__server39 (codemode):")
                .doesNotContain("more server");
    }

    @Test
    void serversThatDoNotFitAreCountedInstead() {
        // Heads alone cannot fit two hundred long names, so the tail is left out and counted.
        var many = new ArrayList<McpServerListing>();
        for (var index = 0; index < 200; index++) {
            many.add(listing(codemode("extremely-long-server-name-" + index, null), null));
        }

        var crowded = McpServersSection.render(many);

        assertThat(crowded.length()).isLessThanOrEqualTo(McpServersSection.MAX_SERVERS_SECTION_CHARS);
        assertThat(crowded).contains("more servers; find their tools with searchTools()");
    }

    // ---------------------------------------------------------------- truncate

    @Test
    void truncateLeavesWhatFitsAlone() {
        assertThat(McpServersSection.truncate("abc", 3)).isEqualTo("abc");
        assertThat(McpServersSection.truncate("abc", 10)).isEqualTo("abc");
    }

    @Test
    void truncateReservesTheLastCharacterForTheEllipsis() {
        assertThat(McpServersSection.truncate("abcdef", 4)).isEqualTo("abc…");
        // The character before the ellipsis is trimmed off first.
        assertThat(McpServersSection.truncate("abc def", 4)).isEqualTo("abc…");
    }

    @Test
    void aBudgetOfOneOrLessLeavesNothing() {
        assertThat(McpServersSection.truncate("abcdef", 1)).isEmpty();
        assertThat(McpServersSection.truncate("abcdef", 0)).isEmpty();
    }

    @Test
    void theSectionNameMatchesThePromptSectionRule() {
        // SystemPrompts validates section names against ^[a-z][a-z0-9_-]*$.
        assertThat(McpServersSection.SECTION).matches("^[a-z][a-z0-9_-]*$");
    }
}
