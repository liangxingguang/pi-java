package com.pijava.coding.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 14（docs/25 T13，§7 #12）：{@code --export} 位置输出参＋pi 文案
 * （{@code Exported to: } 成功 / {@code Error: } 失败 exit 1）。
 */
class MainExportTest {

    @TempDir Path tmp;

    private static Entry.Message user(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private Path seedSession() throws Exception {
        var root = Files.createDirectory(tmp.resolve("sessions" + UUID.randomUUID()));
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(new ProvisionedEntry<>(user("hello export")), "main");
        session.storage().drain();
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
    }

    @Test
    void positionalOutputPathIsHonored() throws Exception {
        var session = seedSession();
        var out = tmp.resolve("custom-out.html");

        var stdout = new ByteArrayOutputStream();
        int code;
        var originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            code = Main.run(new String[] {"--export", session.toString(), out.toString()});
        } finally {
            System.setOut(originalOut);
        }

        assertThat(code).isZero();
        assertThat(out).exists();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
            .contains("Exported to: " + out);
    }

    @Test
    void defaultOutputNameAndSuccessMessage() throws Exception {
        var session = seedSession();
        var base = session.getFileName().toString().replace(".jsonl", "");
        var expected = Path.of(System.getProperty("user.dir"),
            "pi-java-session-" + base + ".html");

        var stdout = new ByteArrayOutputStream();
        int code;
        var originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            code = Main.run(new String[] {"--export", session.toString()});
        } finally {
            System.setOut(originalOut);
            Files.deleteIfExists(expected);   // 默认输出落 cwd——清掉不污染模块目录
        }

        assertThat(code).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
            .contains("Exported to: pi-java-session-" + base + ".html");
    }

    @Test
    void failureUsesPiErrorPrefixAndExit1() {
        var stderr = new ByteArrayOutputStream();
        int code;
        var originalErr = System.err;
        try {
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            code = Main.run(new String[] {"--export", tmp.resolve("missing.jsonl").toString()});
        } finally {
            System.setErr(originalErr);
        }

        assertThat(code).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).startsWith("Error: ");
    }
}
