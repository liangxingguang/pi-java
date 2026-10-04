package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6（{@code docs/12 §6}）：pi 的 {@code usage} 是**一等条目**，本仓必须能读。
 *
 * <p><b>pi 侧逐字证据</b>（{@code coding-agent/src/core/session-manager.ts}）：</p>
 * <pre>
 * :80  export interface UsageEntry extends SessionEntryBase {
 * :81      type: "usage";
 * :82      /** Arbitrary usage category, such as "cache_warm". &#42;/
 * :83      kind: string;
 * :84      provider: string;
 * :85      model: string;
 * :86      usage: Usage;
 * :87      /** Optional human-readable qualifier for usage notices. &#42;/
 * :88      note?: string;
 *      }
 * </pre>
 *
 * <p>本仓目前把 usage 放在 {@code LaneRecord} 族（审计线），落线成 {@code custom}，
 * 且 {@code ENTRY_TYPES} 里没有 {@code "usage"} ⇒ 读 pi 写的这一行会抛
 * {@code has unknown entry type usage}，而 {@link JsonlSessionStorage#load} 对 schema
 * 错误**不宽容**（只有语法错才当成撕裂尾）⇒ <b>整个会话打不开</b>。</p>
 *
 * <p>本用例钉的是**行为**（{@code docs/12 §8.2}：本仓能装载 pi 写的会话文件），
 * 不预设内部形状 —— 但至少要能读完整地读回来。</p>
 */
class PiV3UsageEntryTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static final String PI_V3_HEADER =
        "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\",\"timestamp\":\"2026-10-03T12:50:57.069Z\","
            + "\"cwd\":\"D:\\\\work\"}";

    /**
     * 一行 pi 写的 {@code usage} 条目 —— 形状取自 {@code session-manager.ts:80-89} ＋
     * 唯一生产者 {@code cache-warmer.ts:342-348}（{@code appendUsage("cache_warm", …)}）。
     * {@code usage} 载荷形状取自 {@code packages/ai/src/types.ts:429-443}。
     */
    private static final String PI_USAGE_ENTRY =
        "{\"type\":\"usage\",\"id\":\"u1\",\"parentId\":\"m1\","
            + "\"timestamp\":\"2026-10-03T12:52:00.000Z\","
            + "\"kind\":\"cache_warm\",\"provider\":\"anthropic\",\"model\":\"claude-sonnet-5-5\","
            + "\"usage\":{\"input\":10,\"output\":1,\"cacheRead\":0,\"cacheWrite\":0,"
            + "\"totalTokens\":11,"
            + "\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}},"
            + "\"note\":\"extension override\"}";

    private static final String PI_MESSAGE_ENTRY =
        "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
            + "\"timestamp\":\"2026-10-03T12:51:29.391Z\","
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";

    /** 一个含 pi {@code usage} 行的会话文件必须能装载，且前面的消息不丢。 */
    @Test
    void loadsAPiAuthoredUsageEntry() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-usage");
        Path file = dir.resolve("2026-10-03T12-50-57-069Z_s-1.jsonl");
        Files.writeString(file,
            PI_V3_HEADER + "\n" + PI_MESSAGE_ENTRY + "\n" + PI_USAGE_ENTRY + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        var log = storage.getLog(LogOptions.none());

        assertThat(log.stream().map(PiV3UsageEntryTest::entryId))
            .as("usage 行不该让整个会话装载失败（此前：has invalid seq → 整个文件读不出）")
            .contains("m1", "u1");

        // 负向纪律：**钉字段名**，不只钉「有一行」——
        // pi 的键（kind/provider/model/note）与 usage 载荷都要原样过桥。
        var usage = (Entry.Usage) log.stream()
            .filter(item -> "u1".equals(entryId(item)))
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(usage.kind())
            .as("kind 是自由串（pi 的 UsageEntry.kind），不许被收窄成 UsageCause —— "
                + "收窄后读到 \"cache_warm\" 就会抛")
            .isEqualTo("cache_warm");
        assertThat(usage.provider()).isEqualTo("anthropic");
        assertThat(usage.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(usage.note()).isEqualTo("extension override");
        assertThat(usage.usage().input()).isEqualTo(10);
        assertThat(usage.usage().totalTokens()).isEqualTo(11);
        assertThat(usage.parentId()).as("pi 的行必须链回上一条").isEqualTo("m1");
    }

    /**
     * 反向钉子：**本仓的** usage 行（{@code kind:"assistant"}）也要能读回来 ——
     * pi 的 kind 是自由串，本仓的取值来自 {@code UsageCause}，两侧共用一个键。
     */
    @Test
    void loadsAUsageEntryWithALocalKind() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-usage-local");
        Path file = dir.resolve("2026-10-03T12-50-57-069Z_s-1.jsonl");
        Files.writeString(file, PI_V3_HEADER + "\n" + PI_MESSAGE_ENTRY + "\n"
            + PI_USAGE_ENTRY.replace("\"cache_warm\"", "\"assistant\"") + "\n");

        var storage = JsonlSessionStorage.load(FS, file);

        assertThat(storage.getLog(LogOptions.none()).stream()
                .map(PiV3UsageEntryTest::entryId))
            .contains("u1");
    }

    /**
     * **写侧**：本仓的用量必须落成 pi 的原生 {@code usage} 行，而不是
     * {@code {"type":"custom","customType":"pi-java.record.usage",…}} 审计行。
     */
    @Test
    void writesUsageAsANativePiRow() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-usage-write");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(new com.pijava.agent.entry.ProvisionedEntry<>(new Entry.Usage(
            "u1", 0, null, null, "assistant", "anthropic", "claude-sonnet-5-5",
            new com.pijava.ai.Usage(100, 20, 40, 10, 6.0, 8.0, 170,
                new com.pijava.ai.Usage.Cost(0.30, 0.15, 0.02, 0.04, 0.51)),
            null, "run-1", "e-1", null, 0, "stop")), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var row = lines(file).stream()
            .filter(n -> "u1".equals(n.path("id").asText(null)))
            .findFirst().orElseThrow();

        assertThat(row.path("type").asText()).isEqualTo("usage");
        assertThat(row.has("customType"))
            .as("不再落 custom 审计行 —— 那正是 D6 要改掉的东西").isFalse();
        assertThat(row.path("kind").asText()).isEqualTo("assistant");
        assertThat(row.path("provider").asText()).isEqualTo("anthropic");
        assertThat(row.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(row.path("usage").path("totalTokens").asDouble()).isEqualTo(170);
        assertThat(row.path("usage").path("cacheRead").asDouble()).isEqualTo(40);
        assertThat(row.path("usage").path("cost").path("total").asDouble()).isEqualTo(0.51);
        assertThat(row.has("seq")).as("pi 的 SessionEntryBase 没有 seq（:57-63）").isFalse();
        assertThat(row.path("timestamp").isTextual()).as("时间戳必须是 ISO 串").isTrue();

        // 本仓的审计字段搭扩展键 —— pi 的 parseSessionEntries 只做 JSON.parse，未知键照读不误。
        assertThat(row.path("runId").asText()).isEqualTo("run-1");
        assertThat(row.path("entryId").asText()).isEqualTo("e-1");
        assertThat(row.path("attempt").asInt()).isZero();
        assertThat(row.path("stopReason").asText()).isEqualTo("stop");

        // 读回来逐字段相等（键序无关：按键取值）。
        var reloaded = JsonlSessionStorage.load(FS, file);
        var usage = reloaded.getLog(LogOptions.none()).stream()
            .filter(item -> item instanceof LogItem.EntryItem e
                && e.entry() instanceof Entry.Usage)
            .map(item -> (Entry.Usage) ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(usage.kind()).isEqualTo("assistant");
        assertThat(usage.provider()).isEqualTo("anthropic");
        assertThat(usage.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(usage.usage().totalTokens()).isEqualTo(170);
        assertThat(usage.usage().cacheWrite1h()).isEqualTo(6.0);
        assertThat(usage.usage().reasoning()).isEqualTo(8.0);
        assertThat(usage.runId()).isEqualTo("run-1");
        assertThat(usage.entryId()).isEqualTo("e-1");
        assertThat(usage.stopReason()).isEqualTo("stop");
    }

    /** 读一个会话文件的所有行（跳过空行）。 */
    private static java.util.List<com.fasterxml.jackson.databind.JsonNode> lines(Path file)
            throws Exception {
        var mapper = com.pijava.agent.session.SessionJson.mapper();
        return Files.readAllLines(file).stream()
            .filter(l -> !l.isBlank())
            .map(l -> {
                try {
                    return mapper.readTree(l);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })
            .toList();
    }

    private static String entryId(LogItem item) {
        return item instanceof LogItem.EntryItem e ? e.entry().id() : null;
    }
}
