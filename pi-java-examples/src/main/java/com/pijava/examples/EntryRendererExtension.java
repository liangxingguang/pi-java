package com.pijava.examples;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/entry-renderer.ts:1-41} 移植（docs/27 W5）——
 * {@code registerEntryRenderer("status-card")} 把持久化的条目画进转录，
 * 但不进 LLM 上下文。
 *
 * <p>逐字对位：{@code /status-card [message]} 用 {@code appendEntry} 落一条
 * {@code {message, timestamp}}；渲染器画 {@code "[status] <message>"}，展开臂追加
 * 时间串。{@code entry.data ?? {message:"No data", timestamp: now}} 的缺省臂在
 * java 是 {@code data == null}。</p>
 *
 * <p>窄差 D92 同族（{@link com.pijava.coding.agent.extension.ExtensionEntryRenderer}
 * 的接口注释）：java 渲染器产文本行，{@code theme.fg} 与 {@code Box} 底色只剩文本。</p>
 */
public final class EntryRendererExtension implements PiExtension {

    /** pi L20：条目的 {@code customType}（逐字）。 */
    static final String TYPE = "status-card";

    /** pi L36：命令无参数时的卡片文案（逐字）。 */
    static final String DEFAULT_MESSAGE = "Status card";

    /** pi L21：数据缺失时的文案（逐字）。 */
    static final String NO_DATA = "No data";

    /** pi {@code new Date(ts).toLocaleString()} 的无区域口径。 */
    static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private LongSupplier clock = System::currentTimeMillis;

    @Override
    public String name() {
        return "entry-renderer";
    }

    /** 时钟注入（测试定值；pi 用 {@code Date.now()}）。 */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerEntryRenderer(TYPE, (customType, data, expanded) ->
            renderCard(data, expanded));
        ctx.slashCommands().register(ExampleCommands.command("status-card",
            "Render a durable status card that is not sent to the LLM",
            args -> appendCard(ctx, args)));
    }

    /** pi L20-30：渲染器（文本级）。 */
    static List<String> renderCard(Map<String, Object> data, boolean expanded) {
        var message = data == null ? NO_DATA : jsString(data.get("message"));
        var lines = new java.util.ArrayList<String>(2);
        lines.add(" [status] " + message);
        if (expanded) {
            var timestamp = data == null ? null
                : MessageRendererExtension.timestampOf(data);
            if (timestamp != null) {
                lines.add(" " + DATE_FORMAT.format(Instant.ofEpochMilli(timestamp)));
            }
        }
        return List.copyOf(lines);
    }

    /** pi 的模板串：{@code `${undefined}`} 逐字为 {@code "undefined"}。 */
    private static String jsString(Object value) {
        return value == null ? "undefined" : String.valueOf(value);
    }

    /** pi L32-40：命令体（{@code args.trim() || "Status card"}）。 */
    void appendCard(ExtensionContext ctx, String args) {
        var message = args == null ? "" : args.trim();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message.isEmpty() ? DEFAULT_MESSAGE : message);
        data.put("timestamp", clock.getAsLong());
        ctx.appendEntry(TYPE, data);
    }
}
