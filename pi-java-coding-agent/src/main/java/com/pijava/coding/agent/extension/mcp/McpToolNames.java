package com.pijava.coding.agent.extension.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * {@code mcp__<server>__<tool>}, sanitized and shortened with a hash suffix when too long
 * (pi {@code createMcpToolName}, {@code tools.ts:88-97}).
 *
 * <p>Like Codex, everything but {@code [A-Za-z0-9_]} becomes {@code _}, so the name is also the
 * identifier codemode scripts call it by. Sanitizing can map two tools to one name ({@code a-b}
 * and {@code a_b}), which is what {@code isTaken} is for.</p>
 *
 * <p>⚠️ Not the same function as {@code McpServerConfigs.namespace}, which only turns {@code -}
 * into {@code _}; this one replaces every other character too.</p>
 */
public final class McpToolNames {

    /** Provider tool names are limited to 64 characters of {@code [A-Za-z0-9_-]} ({@code tools.ts:50}). */
    public static final int MAX_TOOL_NAME_LENGTH = 64;

    /** Length of the hash suffix, without its separator. */
    private static final int HASH_LENGTH = 8;

    private McpToolNames() {
    }

    /**
     * The name of an MCP tool, assuming nothing else uses it.
     *
     * @param server the server name
     * @param tool the tool name as the server offers it
     * @return the sanitized name
     */
    public static String create(String server, String tool) {
        return create(server, tool, name -> false);
    }

    /**
     * The name of an MCP tool, shortened with a hash suffix when it is too long or taken.
     *
     * @param server the server name
     * @param tool the tool name as the server offers it
     * @param isTaken reports names used by a different MCP tool
     * @return the sanitized name
     */
    public static String create(String server, String tool, Predicate<String> isTaken) {
        var name = ("mcp__" + server + "__" + tool).replaceAll("[^A-Za-z0-9_]", "_");
        if (name.length() <= MAX_TOOL_NAME_LENGTH && !isTaken.test(name)) {
            return name;
        }
        var hash = hash(server + "\0" + tool);
        // JS slice() clamps; String.substring throws — a short name that is merely taken would
        // otherwise fail here.
        var keep = Math.min(name.length(), MAX_TOOL_NAME_LENGTH - HASH_LENGTH - 1);
        return name.substring(0, keep) + "_" + hash;
    }

    /** Eight hex characters of the SHA-256 of {@code <server>NUL<tool>}. */
    private static String hash(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }
}
