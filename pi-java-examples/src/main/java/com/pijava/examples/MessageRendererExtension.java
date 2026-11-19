package com.pijava.examples;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionMessageRenderer;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/message-renderer.ts:1-59} 移植（docs/27 W5）——
 * {@code registerMessageRenderer("status-update")} 控制自定义消息在转录里的外观。
 *
 * <p>逐字对位：{@code details.level} 决定前缀（{@code error}/{@code warn}/{@code info}）；
 * 展开臂追加 {@code "  at <time>"}；{@code /status [warn|error] <message>} 发一条
 * {@code display:true} 的自定义消息（{@code details = {level, timestamp}}）。</p>
 *
 * <p>窄差 D92 同族（{@link ExtensionMessageRenderer} 的接口注释）：java 渲染器
 * 产<b>文本行</b>，没有 {@code theme} 与 {@code Box} 面——{@code theme.fg} 的色槽
 * 与 {@code customMessageBg} 底色只剩文本；{@code outputPad} 由调用方排版，示例不消费。</p>
 */
public final class MessageRendererExtension implements PiExtension {

    /** pi L15：自定义消息类型（逐字）。 */
    static final String TYPE = "status-update";

    /** pi L48：级别前缀缺省内容（逐字）。 */
    static final String DEFAULT_CONTENT = "Status update";

    /** pi {@code new Date(ts).toLocaleTimeString()} 的无区域口径（时:分:秒）。 */
    static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private LongSupplier clock = System::currentTimeMillis;

    @Override
    public String name() {
        return "message-renderer";
    }

    /** 时钟注入（测试定值；pi 用 {@code Date.now()}）。 */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerMessageRenderer(TYPE, (customType, content, details, expanded, outputPad) ->
            renderMessage(content, details, expanded));
        ctx.slashCommands().register(ExampleCommands.command("status",
            "Send a status message (usage: /status [warn|error] message)",
            args -> send(ctx, args)));
    }

    /** pi L15-35：渲染器（文本级）。 */
    static List<String> renderMessage(CustomMessageContent content, Map<String, Object> details,
                                      boolean expanded) {
        var level = levelOf(details);
        var prefix = "[" + level.toUpperCase(Locale.ROOT) + "]";
        var text = prefix + " " + (content == null ? "" : content.plainText());
        var timestamp = timestampOf(details);
        if (expanded && timestamp != null && timestamp != 0L) {
            text += "\n  at " + TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
        }
        return List.of(text.split("\n", -1));
    }

    /** pi L17：{@code details?.level ?? "info"}。 */
    static String levelOf(Map<String, Object> details) {
        var level = details == null ? null : details.get("level");
        return level == null ? "info" : String.valueOf(level);
    }

    /** pi L26：{@code details?.timestamp}（数字）。 */
    static Long timestampOf(Map<String, Object> details) {
        var value = details == null ? null : details.get("timestamp");
        return value instanceof Number number ? number.longValue()
            : value != null ? Long.valueOf(String.valueOf(value)) : null;
    }

    /** pi L40-57：命令体。 */
    void send(ExtensionContext ctx, String args) {
        var trimmed = args == null ? "" : args.trim();
        var parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        var level = "info";
        var content = trimmed;
        if (parts.length > 0 && ("warn".equals(parts[0]) || "error".equals(parts[0]))) {
            level = parts[0];
            var rest = String.join(" ",
                Arrays.copyOfRange(parts, 1, parts.length));
            content = rest.isEmpty() ? DEFAULT_CONTENT : rest;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", level);
        details.put("timestamp", clock.getAsLong());
        ctx.sendMessage(TYPE, CustomMessageContent.of(content), true, details);
    }
}
