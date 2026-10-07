package com.pijava.coding.agent.subcommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.config.JsJson;
import com.pijava.mcp.config.LoadedMcpConfig;
import com.pijava.mcp.config.McpServerConfigs;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpOAuthCredentialStore;

/**
 * {@code pi-java mcp list} (pi {@code list}, {@code cli.ts:434-519}).
 *
 * <p>Connects to every enabled server to report its state, tools and resources, then exits 1 if
 * anything failed — a configuration error or an enabled server that did not connect.</p>
 */
final class McpListCommand {

    /** What one server is reported as ({@code cli.ts:91-107}). */
    record ServerReport(
            String name,
            String scope,
            String source,
            @Nullable String override,
            boolean enabled,
            String exposure,
            String transport,
            String state,
            List<String> tools,
            @Nullable Map<String, String> toolExposure,
            @Nullable Integer resources,
            @Nullable Integer resourceTemplates,
            @Nullable String error) {
    }

    private McpListCommand() {
    }

    /**
     * Run {@code mcp list}.
     *
     * @param loaded the loaded configuration
     * @param json whether to print the JSON form
     * @param untrustedNote what to say about an ignored project file, or {@code null}
     * @param options the command's environment
     * @param credentials where the OAuth state lives
     * @param log where normal output goes
     * @return the exit code
     */
    static int run(LoadedMcpConfig loaded, boolean json, @Nullable String untrustedNote,
                   McpCommand.Options options, McpOAuthCredentialStore credentials,
                   Consumer<String> log) {
        var reports = connectAll(loaded, options, credentials);
        var failed = !loaded.errors().isEmpty()
                || reports.stream().anyMatch(report ->
                        report.enabled() && !"connected".equals(report.state()));

        if (json) {
            log.accept(jsonOf(reports, loaded, untrustedNote));
            return failed ? 1 : 0;
        }
        if (reports.isEmpty() && loaded.errors().isEmpty()) {
            log.accept("No MCP servers configured. Add them to "
                    + options.agentDir().resolve("mcp.json") + " or "
                    + McpCliHelp.CONFIG_DIR_NAME + "/mcp.json.");
        }
        for (var report : reports) {
            printReport(report, log);
        }
        for (var error : loaded.errors()) {
            log.accept("config error: " + error);
        }
        if (untrustedNote != null) {
            log.accept(untrustedNote);
        }
        return failed ? 1 : 0;
    }

    /** Every server connects at the same time, like pi's {@code Promise.all} ({@code cli.ts:442}). */
    private static List<ServerReport> connectAll(LoadedMcpConfig loaded,
                                                 McpCommand.Options options,
                                                 McpOAuthCredentialStore credentials) {
        var futures = new ArrayList<CompletableFuture<ServerReport>>();
        for (var entry : loaded.servers()) {
            var future = new CompletableFuture<ServerReport>();
            Thread.startVirtualThread(() -> {
                try {
                    future.complete(connect(entry, options, credentials));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
            futures.add(future);
        }
        var reports = new ArrayList<ServerReport>();
        for (var future : futures) {
            reports.add(future.join());
        }
        return List.copyOf(reports);
    }

    /** Connect once and describe what came back ({@code cli.ts:443-476}). */
    private static ServerReport connect(McpServerEntry entry, McpCommand.Options options,
                                        McpOAuthCredentialStore credentials) {
        var config = entry.config();
        var enabled = !Boolean.FALSE.equals(config.enabled());
        var exposure = config.exposure() == null ? "codemode" : config.exposure().wire();
        var connection = McpCommandSupport.connection(entry, options, credentials);
        if (!enabled) {
            return new ServerReport(entry.name(), scopeOf(entry), entry.source().toString(),
                    entry.override() == null ? null : entry.override().toString(),
                    false, exposure, McpCommandSupport.describeTransport(entry), "disabled", List.of(),
                    null, null, null, null);
        }
        try {
            connection.getClient().join();
        } catch (RuntimeException | Error ignored) {
            // The connection records the state and the error.
        }
        var tools = connection.tools().stream().map(tool -> tool.name()).toList();
        Map<String, String> overrides = null;
        var differing = new LinkedHashMap<String, String>();
        for (var tool : connection.tools()) {
            var toolExposure = McpServerConfigs.getMcpToolExposure(config, tool.name()).wire();
            if (!toolExposure.equals(exposure)) {
                differing.put(tool.name(), toolExposure);
            }
        }
        if (!differing.isEmpty()) {
            overrides = Map.copyOf(differing);
        }
        var state = connection.state().wire();
        Integer resources = null;
        Integer templates = null;
        if (connection.hasResources()) {
            resources = connection.resources().size();
            templates = connection.resourceTemplates().size();
        }
        var error = !"connected".equals(state) ? connection.error() : null;
        try {
            connection.close().join();
        } catch (RuntimeException | Error ignored) {
            // Closing is best effort.
        }
        return new ServerReport(entry.name(), scopeOf(entry), entry.source().toString(),
                entry.override() == null ? null : entry.override().toString(),
                true, exposure, McpCommandSupport.describeTransport(entry), state, tools, overrides,
                resources, templates, error);
    }

    private static String scopeOf(McpServerEntry entry) {
        return entry.scope() == null ? "global" : entry.scope().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code JSON.stringify({servers, errors, note?}, null, 2)} ({@code cli.ts:480-489}). */
    private static String jsonOf(List<ServerReport> reports, LoadedMcpConfig loaded,
                                 @Nullable String untrustedNote) {
        var node = McpJson.mapper().createObjectNode();
        var servers = node.putArray("servers");
        for (var report : reports) {
            servers.add(reportNode(report));
        }
        var errors = node.putArray("errors");
        for (var error : loaded.errors()) {
            errors.add(error);
        }
        if (untrustedNote != null) {
            node.put("note", untrustedNote);
        }
        return JsJson.stringify(node, "  ");
    }

    private static ObjectNode reportNode(ServerReport report) {
        var node = McpJson.mapper().createObjectNode();
        node.put("name", report.name());
        node.put("scope", report.scope());
        node.put("source", report.source());
        if (report.override() != null) {
            node.put("override", report.override());
        }
        node.put("enabled", report.enabled());
        node.put("exposure", report.exposure());
        node.put("transport", report.transport());
        node.put("state", report.state());
        var tools = node.putArray("tools");
        for (var tool : report.tools()) {
            tools.add(tool);
        }
        if (report.toolExposure() != null) {
            var exposures = node.putObject("toolExposure");
            report.toolExposure().forEach(exposures::put);
        }
        if (report.resources() != null) {
            node.put("resources", report.resources());
        }
        if (report.resourceTemplates() != null) {
            node.put("resourceTemplates", report.resourceTemplates());
        }
        if (report.error() != null) {
            node.put("error", report.error());
        }
        return node;
    }

    /** The human-readable block of one server ({@code cli.ts:493-515}). */
    private static void printReport(ServerReport report, Consumer<String> log) {
        log.accept(report.name() + ": " + describeState(report) + " ("
                + report.exposure() + ", " + report.scope() + ")");
        log.accept("  " + report.transport());
        if (report.override() != null) {
            log.accept("  project override: " + report.override());
        }
        if ("needs-auth".equals(report.state())) {
            log.accept("  sign in with: " + McpCliHelp.APP_NAME + " mcp login " + report.name());
        }
        if (!report.tools().isEmpty()) {
            var tools = new ArrayList<String>();
            for (var tool : report.tools()) {
                var exposure = report.toolExposure() == null ? null : report.toolExposure().get(tool);
                tools.add(exposure == null ? tool : tool + " [" + exposure + "]");
            }
            log.accept("  tools: " + String.join(", ", tools));
        }
        if (report.resources() != null) {
            log.accept("  resources: " + report.resources() + ", URI templates: "
                    + (report.resourceTemplates() == null ? 0 : report.resourceTemplates()));
        }
        if (report.error() != null) {
            log.accept("  " + report.error().replace("\n", "\n  "));
        }
    }

    /** {@code cli.ts:494-499}: three of the states read differently. */
    private static String describeState(ServerReport report) {
        if ("connected".equals(report.state())) {
            var count = report.tools().size();
            return "connected, " + count + " tool" + (count == 1 ? "" : "s");
        }
        if ("needs-auth".equals(report.state())) {
            return "needs sign-in";
        }
        return report.state();
    }

}
