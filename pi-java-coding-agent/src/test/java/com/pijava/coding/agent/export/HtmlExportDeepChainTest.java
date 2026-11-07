package com.pijava.coding.agent.export;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W8（docs/25 §9；pi {@code test/export-html-deep-tree.test.ts}，issue #11225）：
 * 超长线性会话（20,000 条）的祖先遍历不得爆栈——导出与渲染都必须迭代。
 */
class HtmlExportDeepChainTest {

    @TempDir
    Path dir;

    @Test
    void twentyThousandEntryChainExportsWithoutStackOverflow() throws Exception {
        int depth = 20_000;
        var lines = new java.util.ArrayList<String>(depth + 1);
        lines.add("{\"type\":\"session\",\"version\":4,\"id\":\"deep-test\","
            + "\"timestamp\":\"1970-01-01T00:00:00Z\",\"cwd\":\"/tmp/deep\"}");
        for (int i = 0; i < depth; i++) {
            lines.add("{\"type\":\"message\",\"id\":\"e" + i + "\",\"parentId\":"
                + (i == 0 ? "null" : "\"e" + (i - 1) + "\"")
                + ",\"seq\":" + (i + 1) + ",\"timestamp\":\"1970-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"user\",\"content\":\"x\"}}");
        }
        var session = dir.resolve("deep.jsonl");
        Files.writeString(session, String.join("\n", lines) + "\n");

        var html = new HtmlExporter().export(session, dir.resolve("out.html"));
        var text = Files.readString(html);

        // 不爆栈即为通过；再钉首末条都在。
        assertThat(text).contains("e0");
        assertThat(text).contains("e19999");
        assertThat(text.split("msg user", -1)).hasSize(depth + 1);
    }
}
