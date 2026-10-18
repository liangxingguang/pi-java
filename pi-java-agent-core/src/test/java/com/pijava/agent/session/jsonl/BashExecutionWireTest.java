package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #57（docs/10 §3）：pi 的 {@code BashExecutionMessage} 寄居在
 * {@code type:"message"} 条目里（{@code session-manager.ts:1204-1214}），
 * 靠 {@code message.role:"bashExecution"} 判别。
 *
 * <p><b>pi 侧逐字证据</b>（{@code coding-agent/src/core/messages.ts}）：</p>
 * <pre>
 * :29  export interface BashExecutionMessage {
 * :30      role: "bashExecution";
 * :31      command: string;
 * :32      output: string;
 * :33      exitCode: number | undefined;
 * :34      cancelled: boolean;
 * :35      truncated: boolean;
 * :36      fullOutputPath?: string;
 * :37      timestamp: number;
 * :38      /** If true, this message is excluded from LLM context (!! prefix) &#42;/
 * :39      excludeFromContext?: boolean;
 *      }
 * </pre>
 *
 * <p>此前 {@code MessageJsonCodec} 撞见这个 role 走 default 臂抛 DecodeError
 * ⇒ pi 会话文件带一条 {@code !} 记录就整个读不出（G2）。</p>
 */
class BashExecutionWireTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static final String PI_V3_HEADER =
        "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\",\"timestamp\":\"2026-10-03T12:50:57.069Z\","
            + "\"cwd\":\"D:\\\\work\"}";

    private static final String PI_MESSAGE_ENTRY =
        "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
            + "\"timestamp\":\"2026-10-03T12:51:29.391Z\","
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";

    /** 全键的 pi 行 —— 形状取自 messages.ts:29-42（message.timestamp 是 epoch 毫秒）。 */
    private static final String PI_BASH_ENTRY =
        "{\"type\":\"message\",\"id\":\"b1\",\"parentId\":\"m1\","
            + "\"timestamp\":\"2026-10-03T12:53:00.000Z\","
            + "\"message\":{\"role\":\"bashExecution\",\"command\":\"ls -la\","
            + "\"output\":\"total 0\\n\",\"exitCode\":0,\"cancelled\":false,\"truncated\":true,"
            + "\"fullOutputPath\":\"/tmp/out.txt\",\"timestamp\":1759505580000,"
            + "\"excludeFromContext\":true}}";

    /** 可选键全省略的 pi 行（exitCode/fullOutputPath/excludeFromContext 缺席）。 */
    private static final String PI_BASH_ENTRY_MINIMAL =
        "{\"type\":\"message\",\"id\":\"b2\",\"parentId\":\"m1\","
            + "\"timestamp\":\"2026-10-03T12:54:00.000Z\","
            + "\"message\":{\"role\":\"bashExecution\",\"command\":\"true\","
            + "\"output\":\"\",\"cancelled\":false,\"truncated\":false,"
            + "\"timestamp\":1759505640000}}";

    /** pi 写的 bashExecution 行必须能装载，且逐字段过桥。 */
    @Test
    void loadsAPiAuthoredBashExecutionLine() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-bash");
        Path file = dir.resolve("2026-10-03T12-50-57-069Z_s-1.jsonl");
        Files.writeString(file,
            PI_V3_HEADER + "\n" + PI_MESSAGE_ENTRY + "\n" + PI_BASH_ENTRY + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        var log = storage.getLog(LogOptions.none());

        var bash = (Entry.BashExecution) log.stream()
            .filter(item -> item instanceof LogItem.EntryItem e
                && e.entry() instanceof Entry.BashExecution)
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(bash.id()).isEqualTo("b1");
        assertThat(bash.parentId()).isEqualTo("m1");
        assertThat(bash.command()).isEqualTo("ls -la");
        assertThat(bash.output()).isEqualTo("total 0\n");
        assertThat(bash.exitCode()).isZero();
        assertThat(bash.cancelled()).isFalse();
        assertThat(bash.truncated()).isTrue();
        assertThat(bash.fullOutputPath()).isEqualTo("/tmp/out.txt");
        assertThat(bash.excludeFromContext()).isTrue();
        assertThat(bash.type())
            .as("线判别共享 type:\"message\"（pi 无独立条目类型）")
            .isEqualTo(Entry.TYPE_MESSAGE);
    }

    /** 可选键缺席 ⇒ {@code null} ≙ pi 的 undefined。 */
    @Test
    void optionalKeysAbsentDecodeAsNull() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-bash-min");
        Path file = dir.resolve("2026-10-03T12-50-57-069Z_s-1.jsonl");
        Files.writeString(file,
            PI_V3_HEADER + "\n" + PI_MESSAGE_ENTRY + "\n" + PI_BASH_ENTRY_MINIMAL + "\n");

        var storage = JsonlSessionStorage.load(FS, file);

        var bash = (Entry.BashExecution) storage.getLog(LogOptions.none()).stream()
            .filter(item -> item instanceof LogItem.EntryItem e
                && e.entry() instanceof Entry.BashExecution)
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(bash.exitCode()).isNull();
        assertThat(bash.fullOutputPath()).isNull();
        assertThat(bash.excludeFromContext()).isNull();
    }

    /**
     * <b>写侧</b>：落成 pi 的原生 {@code type:"message"} 行（role 判别、无 seq、
     * ISO 条目时间戳、message.timestamp 是 epoch 毫秒数、可选键缺席省略），
     * 且读回来逐字段相等。
     */
    @Test
    void writesBashExecutionAsANativePiMessageRow() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-bash-write");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.BashExecution(
            "b1", 0, null, null, "git status", "On branch main\n",
            1, false, true, "/tmp/full.log", Boolean.TRUE)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var row = lines(file).stream()
            .filter(n -> "b1".equals(n.path("id").asText(null)))
            .findFirst().orElseThrow();

        assertThat(row.path("type").asText()).isEqualTo("message");
        assertThat(row.has("seq")).as("pi 的 SessionEntryBase 没有 seq").isFalse();
        assertThat(row.path("timestamp").isTextual()).as("条目时间戳必须是 ISO 串").isTrue();
        var msg = row.path("message");
        assertThat(msg.path("role").asText()).isEqualTo("bashExecution");
        assertThat(msg.path("command").asText()).isEqualTo("git status");
        assertThat(msg.path("output").asText()).isEqualTo("On branch main\n");
        assertThat(msg.path("exitCode").asInt()).isEqualTo(1);
        assertThat(msg.path("cancelled").asBoolean()).isFalse();
        assertThat(msg.path("truncated").asBoolean()).isTrue();
        assertThat(msg.path("fullOutputPath").asText()).isEqualTo("/tmp/full.log");
        assertThat(msg.path("timestamp").isNumber())
            .as("message.timestamp 是 epoch 毫秒数（pi Date.now()，messages.ts:39）").isTrue();
        assertThat(msg.path("excludeFromContext").asBoolean()).isTrue();

        var reloaded = JsonlSessionStorage.load(FS, file);
        var bash = (Entry.BashExecution) reloaded.getLog(LogOptions.none()).stream()
            .filter(item -> item instanceof LogItem.EntryItem e
                && e.entry() instanceof Entry.BashExecution)
            .map(item -> ((LogItem.EntryItem) item).entry())
            .findFirst().orElseThrow();
        assertThat(bash.command()).isEqualTo("git status");
        assertThat(bash.output()).isEqualTo("On branch main\n");
        assertThat(bash.exitCode()).isEqualTo(1);
        assertThat(bash.cancelled()).isFalse();
        assertThat(bash.truncated()).isTrue();
        assertThat(bash.fullOutputPath()).isEqualTo("/tmp/full.log");
        assertThat(bash.excludeFromContext()).isTrue();
    }

    /** 写侧的可选键为 null ⇒ 键全省略（NON_NULL 惯例）。 */
    @Test
    void omitsAbsentOptionalKeysOnWrite() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-bash-write-min");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.BashExecution(
            "b1", 0, null, null, "true", "", null, false, false, null, null)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var msg = lines(file).stream()
            .filter(n -> "b1".equals(n.path("id").asText(null)))
            .findFirst().orElseThrow().path("message");

        assertThat(msg.has("exitCode")).isFalse();
        assertThat(msg.has("fullOutputPath")).isFalse();
        assertThat(msg.has("excludeFromContext")).isFalse();
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
}
