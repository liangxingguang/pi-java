package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * Ports pi's {@code debug-provider} example.
 *
 * <p>When enabled with {@code /debug-provider on}, captures raw provider stream
 * events plus the assistant message identity for the current turn and appends one
 * custom {@value #ENTRY_TYPE} entry at turn end. The renderer shows a compact
 * summary, or the raw JSON of captured events when expanded. pi's {@code keyHint}
 * is not available in the java renderer context (narrow difference D220).</p>
 */
public final class DebugProviderExtension implements PiExtension {

    /** Extension name shown by the host registry. */
    public static final String NAME = "debug-provider";

    /** Custom entry type used for captured provider debug data. */
    public static final String ENTRY_TYPE = "provider-debug";

    static final String STATUS_KEY = "debug-provider";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(ExtensionContext ctx) {
        Capture capture = new Capture();

        ctx.registerEntryRenderer(ENTRY_TYPE, (customType, data, expanded) ->
                renderEntry(data, expanded));
        ctx.slashCommands().register(debugCommand(ctx, capture));

        ctx.on(ExtensionEventSpec.TURN_START, (event, c) -> {
            capture.begin(event.turnIndex());
            return null;
        });
        ctx.on(ExtensionEventSpec.PROVIDER_STREAM_EVENT, (event, c) -> {
            capture.streamEvent(event.provider(), event.api(), event.model(), event.data());
            return null;
        });
        ctx.on(ExtensionEventSpec.MESSAGE_END, (event, c) -> {
            if (event.message() instanceof Message.AssistantMessage assistant) {
                capture.message(assistant);
            }
            return null;
        });
        ctx.on(ExtensionEventSpec.TURN_END, (event, c) -> {
            if (capture.enabled) {
                Map<String, Object> data = capture.toEntry();
                if (!data.isEmpty()) {
                    ctx.appendEntry(ENTRY_TYPE, data);
                }
                capture.reset();
            }
            return null;
        });
    }

    private static SlashCommand debugCommand(ExtensionContext ctx, Capture capture) {
        return new SlashCommand() {
            @Override
            public String name() {
                return DebugProviderExtension.NAME;
            }

            @Override
            public String description() {
                return "append provider debug entries at turn end";
            }

            @Override
            public String argumentHint() {
                return "[on|off]";
            }

            @Override
            public CompletableFuture<String> execute(
                    String args, com.pijava.coding.agent.core.slash.SlashContext context) {
                String mode = args == null ? "" : args.trim();
                boolean enabled = mode.isEmpty() ? !capture.enabled
                        : "on".equalsIgnoreCase(mode);
                capture.enabled = enabled;
                capture.reset();
                String message = enabled
                        ? "Provider debug capture enabled (will append at turn end)."
                        : "Provider debug capture disabled (cache cleared).";
                ctx.ui().notify(message, NotifyType.INFO);
                ctx.ui().setStatus(STATUS_KEY, enabled ? "provider debug: ON" : null);
                return CompletableFuture.completedFuture(message);
            }
        };
    }

    private static List<String> renderEntry(Map<String, Object> data, boolean expanded) {
        String provider = String.valueOf(data.getOrDefault("provider", "unknown"));
        String model = String.valueOf(data.getOrDefault("model", "unknown"));
        Object events = data.get("events");
        int count = events instanceof List<?> list ? list.size() : 0;
        List<String> lines = new ArrayList<>();
        lines.add("[provider-debug] " + provider + "/" + model + " · " + count + " event(s)");
        if (expanded) {
            try {
                lines.add(JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(data.get("events")));
            } catch (Exception ex) {
                lines.add(String.valueOf(data.get("events")));
            }
        }
        return lines;
    }

    /** Mutable per-turn capture buffer, reset at turn start/end. */
    private static final class Capture {

        private boolean enabled;
        private int turnIndex;
        private String provider;
        private String api;
        private String model;
        private Map<String, Object> message;
        private List<Object> events;

        void begin(int index) {
            reset();
            turnIndex = index;
        }

        void streamEvent(String eventProvider, String eventApi, String eventModel, Object data) {
            if (!enabled) {
                return;
            }
            if (events == null) {
                events = new ArrayList<>();
            }
            provider = eventProvider;
            api = eventApi;
            model = eventModel;
            events.add(snapshot(data));
        }

        void message(Message.AssistantMessage assistant) {
            if (!enabled) {
                return;
            }
            provider = assistant.provider() == null ? provider : assistant.provider();
            api = assistant.api() == null ? api : assistant.api();
            model = assistant.model() == null ? model : assistant.model();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("role", "assistant");
            summary.put("provider", nonNull(provider));
            summary.put("api", nonNull(api));
            summary.put("model", nonNull(model));
            summary.put("text", preview(assistant));
            message = summary;
        }

        Map<String, Object> toEntry() {
            if (events == null && message == null) {
                return Map.of();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", nonNull(provider));
            data.put("api", nonNull(api));
            data.put("model", nonNull(model));
            data.put("turnIndex", turnIndex);
            data.put("events", events == null ? List.of() : List.copyOf(events));
            if (message != null) {
                data.put("message", message);
            }
            return data;
        }

        void reset() {
            events = null;
            message = null;
            provider = null;
            api = null;
            model = null;
        }

        private static String nonNull(String value) {
            return value == null ? "" : value;
        }

        private static String preview(Message.AssistantMessage assistant) {
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ContentBlock.TextContent t) {
                    text.append(t.text());
                }
            }
            return text.length() > 200 ? text.substring(0, 200) : text.toString();
        }

        private static Object snapshot(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(String.valueOf(entry.getKey()), snapshot(entry.getValue()));
                }
                return copy;
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(snapshot(item));
                }
                return copy;
            }
            return value;
        }
    }
}
