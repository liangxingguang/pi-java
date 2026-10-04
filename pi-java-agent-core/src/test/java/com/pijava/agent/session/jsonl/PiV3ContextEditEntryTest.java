package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.SessionJson;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3（{@code docs/13}）：pi 的 {@code context_edit} 是锚点新增的一等条目，
 * 本仓此前读到即报 {@code unknown entry type context_edit} ⇒ 整个会话不可读。
 *
 * <p><b>pi 侧逐字证据</b>（{@code coding-agent/src/core/session-manager.ts:175-180}）：</p>
 * <pre>
 * :175  /** Append-only change to one earlier entry's contribution to model context. *&#47;
 * :176  export interface ContextEditEntry extends SessionEntryBase {
 * :177      type: "context_edit";
 * :178      targetId: string;
 * :179      /** Null omits the target from model context. A value replaces only its content. *&#47;
 * :180      replacement: { content: ContextEditableContent } | null;
 *        }
 * </pre>
 */
class PiV3ContextEditEntryTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static final String PI_V3_HEADER =
        "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\",\"timestamp\":\"2026-10-04T09:00:00.000Z\","
            + "\"cwd\":\"D:\\\\work\"}";

    private static final String PI_USER_ENTRY =
        "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
            + "\"timestamp\":\"2026-10-04T09:01:00.000Z\","
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";

    private static final String PI_ASSISTANT_ENTRY =
        "{\"type\":\"message\",\"id\":\"m2\",\"parentId\":\"m1\","
            + "\"timestamp\":\"2026-10-04T09:02:00.000Z\","
            + "\"message\":{\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"original\"}]}}";

    /**
     * pi 写的 omission 行（{@code replacement:null}）—— 形状取自
     * {@code session-manager.ts:175-180}；生产者形状见 {@code agent-session.ts:1210-1225}
     * 的溢出恢复省略。
     */
    private static final String PI_OMISSION_EDIT =
        "{\"type\":\"context_edit\",\"id\":\"e1\",\"parentId\":\"m2\","
            + "\"timestamp\":\"2026-10-04T09:03:00.000Z\","
            + "\"targetId\":\"m2\",\"replacement\":null}";

    /**
     * pi 写的 replacement 行，content 取**裸串**（导入形：未经
     * {@code appendContextEdit} 的写入点归一）—— 投影时须按
     * {@code projectContextEntry}（{@code session-manager.ts:533-536}）归一成 text 块。
     */
    private static final String PI_IMPORTED_REPLACEMENT_EDIT =
        "{\"type\":\"context_edit\",\"id\":\"e2\",\"parentId\":\"m2\","
            + "\"timestamp\":\"2026-10-04T09:04:00.000Z\","
            + "\"targetId\":\"m2\",\"replacement\":{\"content\":\"imported replacement\"}}";

    /** 一个含 pi omission 行的会话文件必须能装载，且字段按 pi 的键原样过桥。 */
    @Test
    void loadsAPiAuthoredOmissionRow() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-context-edit");
        Path file = dir.resolve("2026-10-04T09-00-00-000Z_s-1.jsonl");
        Files.writeString(file,
            PI_V3_HEADER + "\n" + PI_USER_ENTRY + "\n" + PI_ASSISTANT_ENTRY + "\n"
                + PI_OMISSION_EDIT + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        var log = storage.getLog(LogOptions.none());

        assertThat(log.stream().map(PiV3ContextEditEntryTest::entryId))
            .as("context_edit 行不该让整个会话装载失败（此前：unknown entry type → 文件读不出）")
            .contains("m1", "m2", "e1");

        // 负向纪律：钉字段名，不只钉「有一行」。
        var edit = (Entry.ContextEdit) log.stream()
            .filter(item -> "e1".equals(entryId(item)))
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(edit.type()).isEqualTo("context_edit");
        assertThat(edit.targetId()).as("pi 的 targetId 必须原样过桥").isEqualTo("m2");
        assertThat(edit.replacement())
            .as("显式 null ⇒ 剔除语义，不能读成别的东西").isNull();
        assertThat(edit.parentId()).as("pi 的行必须链回当前叶").isEqualTo("m2");
    }

    /** replacement 行（裸串导入形）必须可读，content 不得丢。 */
    @Test
    void loadsAPiAuthoredReplacementRow() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-context-edit-replace");
        Path file = dir.resolve("2026-10-04T09-00-00-000Z_s-1.jsonl");
        Files.writeString(file,
            PI_V3_HEADER + "\n" + PI_USER_ENTRY + "\n" + PI_ASSISTANT_ENTRY + "\n"
                + PI_IMPORTED_REPLACEMENT_EDIT + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        var edit = (Entry.ContextEdit) storage.getLog(LogOptions.none()).stream()
            .filter(item -> "e2".equals(entryId(item)))
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();

        assertThat(edit.targetId()).isEqualTo("m2");
        assertThat(edit.replacement()).isNotNull();
        assertThat(edit.replacement().content())
            .isInstanceOf(CustomMessageContent.Text.class);
        assertThat(((CustomMessageContent.Text) edit.replacement().content()).text())
            .as("裸串 content 必须原样过桥（归一留给投影层）")
            .isEqualTo("imported replacement");
    }

    /**
     * **写侧**：溢出恢复省略必须落成 pi 的原生 {@code context_edit} 行，
     * {@code replacement} 键在且为 null（pi 类型该键必填 —— NON_NULL 会吞 null，
     * 由 PiV3Wire 强制补键）。
     */
    @Test
    void writesOmissionAsANativePiRow() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-context-edit-write");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.ContextEdit(
            "e1", 0, null, null, "m2", null)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var row = lines(file).stream()
            .filter(n -> "e1".equals(n.path("id").asText(null)))
            .findFirst().orElseThrow();

        assertThat(row.path("type").asText()).isEqualTo("context_edit");
        assertThat(row.path("targetId").asText()).isEqualTo("m2");
        assertThat(row.has("replacement"))
            .as("replacement 键必填，显式 null 也落键（M3 钉这一条）").isTrue();
        assertThat(row.path("replacement").isNull()).isTrue();
        assertThat(row.has("customType"))
            .as("不落 custom 审计行").isFalse();
        assertThat(row.has("seq")).as("pi 的 SessionEntryBase 没有 seq").isFalse();
        assertThat(row.path("timestamp").isTextual()).as("时间戳必须是 ISO 串").isTrue();
        assertThat(row.path("lane").asText())
            .as("lane 是本仓扩展键（pi JSON.parse 容忍未知键）").isEqualTo("main");

        // 读回来逐字段相等。
        var reloaded = JsonlSessionStorage.load(FS, file);
        var edit = (Entry.ContextEdit) reloaded.getLog(LogOptions.none()).stream()
            .filter(item -> "e1".equals(entryId(item)))
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(edit.targetId()).isEqualTo("m2");
        assertThat(edit.replacement()).isNull();
    }

    /** 读一个会话文件的所有行（跳过空行）。 */
    private static java.util.List<com.fasterxml.jackson.databind.JsonNode> lines(Path file)
            throws Exception {
        var mapper = SessionJson.mapper();
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
