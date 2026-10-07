package com.pijava.mcp.runtime;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;

/**
 * MCP App user interfaces, which only hosts that render them can use
 * (pi {@code resources.ts:50-53}).
 *
 * <p>They are filtered out of the lists a connection exposes, so a server's UI resources do not
 * show up as documents.</p>
 */
public final class McpAppResources {

    /** Case-insensitive, unanchored, like pi's {@code /;\s*profile\s*=\s*"?mcp-app"?/i}. */
    private static final Pattern PROFILE =
            Pattern.compile(";\\s*profile\\s*=\\s*\"?mcp-app\"?", Pattern.CASE_INSENSITIVE);

    private McpAppResources() {
    }

    /**
     * Whether a listed resource or template is an MCP App.
     *
     * @param uri the resource URI, when it has one
     * @param uriTemplate the template, when it is one
     * @param mimeType the MIME type
     * @return {@code true} for a {@code ui://} URI or an {@code mcp-app} profile
     */
    public static boolean isMcpAppResource(@Nullable String uri, @Nullable String uriTemplate,
                                           @Nullable String mimeType) {
        var target = uri != null ? uri : uriTemplate != null ? uriTemplate : "";
        return target.startsWith("ui://")
                || PROFILE.matcher(mimeType == null ? "" : mimeType).find();
    }

    /**
     * Whether a listed resource is an MCP App.
     *
     * @param resource the resource
     * @return {@code true} for an MCP App
     */
    public static boolean isMcpAppResource(Resource resource) {
        return isMcpAppResource(resource.uri(), null, resource.mimeType());
    }

    /**
     * Whether a listed template is an MCP App.
     *
     * @param template the template
     * @return {@code true} for an MCP App
     */
    public static boolean isMcpAppResource(ResourceTemplate template) {
        return isMcpAppResource(null, template.uriTemplate(), template.mimeType());
    }
}
