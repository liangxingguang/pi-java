package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpServerConfigs;

/**
 * The {@code mcp_servers} system prompt section: every enabled server whose tools are not declared
 * to the model, with how its tools are reached and a one-line summary
 * (pi {@code renderServersSection}, {@code index.ts:186-217}).
 *
 * <p>The model learns of those servers from here, since neither codemode nor tool_search lists
 * them. The section is absent when there are none.</p>
 */
public final class McpServersSection {

    /** Name of the section that lists the servers whose tools are not declared ({@code index.ts:152}). */
    public static final String SECTION = "mcp_servers";

    /** Characters of a server description, as Codex allows for deferred namespaces. */
    public static final int MAX_SERVER_DESCRIPTION_CHARS = 250;

    /**
     * Characters of the whole section. Descriptions shrink to fit; when the server lines alone do
     * not fit, the last servers are left out and counted in a closing line.
     */
    public static final int MAX_SERVERS_SECTION_CHARS = 4096;

    private McpServersSection() {
    }

    /**
     * Render the section, or {@code null} when no server belongs in it
     * ({@code index.ts:191-217}).
     *
     * @param servers the configured servers, in any order
     * @return the section text, or {@code null}
     */
    public static @Nullable String render(List<McpServerListing> servers) {
        var listed = new ArrayList<McpServerListing>();
        for (var server : servers) {
            if (McpServerPresentation.isEnabled(server.entry())
                    && McpServerPresentation.hasIndirectTools(server.entry())) {
                listed.add(server);
            }
        }
        // pi sorts with localeCompare; the port uses the natural order, which agrees for ASCII
        // names (registered as a deviation).
        listed.sort(Comparator.comparing(listing -> listing.entry().name()));
        if (listed.isEmpty()) {
            return null;
        }
        var reaches = new ArrayList<String>();
        for (var server : listed) {
            reaches.add(McpServerPresentation.configuredExposures(server.entry())
                    .contains(McpExposure.CODEMODE) ? "codemode" : "tool_search");
        }
        var intro = intro(new LinkedHashSet<>(reaches));
        var heads = new ArrayList<String>();
        for (var index = 0; index < listed.size(); index++) {
            heads.add("- " + McpServerConfigs.namespace(listed.get(index).entry().name())
                    + " (" + reaches.get(index) + ")");
        }
        // Characters of the intro, the first `kept` server lines without descriptions, and the
        // omission line.
        var kept = listed.size();
        while (kept > 0 && size(intro, heads, listed.size() - kept) > MAX_SERVERS_SECTION_CHARS) {
            kept--;
        }
        // Each description also takes a ": " separator.
        var perServer = kept == 0 ? 0
                : Math.min(MAX_SERVER_DESCRIPTION_CHARS,
                        (MAX_SERVERS_SECTION_CHARS - size(intro, heads, listed.size() - kept)) / kept - 2);
        var lines = new ArrayList<String>();
        for (var index = 0; index < kept; index++) {
            var summary = perServer > 0
                    ? truncate(summaryOf(listed.get(index)), perServer) : "";
            lines.add(summary.isEmpty() ? heads.get(index) : heads.get(index) + ": " + summary);
        }
        var out = new ArrayList<String>();
        out.add(intro);
        out.addAll(lines);
        out.addAll(omitted(listed.size() - kept));
        return String.join("\n", out);
    }

    /** The section's first line: it explains only the ways of reaching tools the servers use. */
    private static String intro(Set<String> reaches) {
        var intro = "MCP servers whose tools are not declared to you.";
        if (reaches.contains("codemode")) {
            intro += " Call the tools of `codemode` servers from codemode scripts.";
        }
        if (reaches.contains("tool_search")) {
            intro += " Load the tools of `tool_search` servers with `tool_search`.";
        }
        return intro;
    }

    /** Characters of the intro, the first {@code kept} lines, and the omission line. */
    private static int size(String intro, List<String> heads, int omittedCount) {
        var parts = new ArrayList<String>();
        parts.add(intro);
        parts.addAll(heads.subList(0, heads.size() - omittedCount));
        parts.addAll(omitted(omittedCount));
        return String.join("\n", parts).length();
    }

    private static List<String> omitted(int count) {
        return count > 0
                ? List.of("- … " + count + " more server" + (count == 1 ? "" : "s")
                        + "; find their tools with searchTools()")
                : List.of();
    }

    /** First line of the configured description, or of the server instructions once connected. */
    private static String summaryOf(McpServerListing listing) {
        var description = listing.entry().config().description();
        var text = description != null && !description.strip().isEmpty()
                ? description.strip()
                : listing.instructions() == null ? "" : listing.instructions();
        var first = McpServerPresentation.firstLine(text);
        return first.strip();
    }

    /**
     * Cut a description to {@code max} characters ({@code index.ts:169-172}).
     *
     * <p>pi measures and slices UTF-16 units, so a surrogate pair can be cut in half; the port
     * does the same on purpose, since the budget is pi's.</p>
     */
    static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        if (max <= 1) {
            return "";
        }
        return text.substring(0, max - 1).stripTrailing() + "…";
    }
}
