package com.pijava.agent.interop;

import java.io.File;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.agent.session.jsonl.DefaultJsonlFileSystem;
import com.pijava.agent.session.jsonl.JsonlSessionStorage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 pi 端到端互读（{@code docs/13 §12.5}：B60/B61 销号的硬判据）。
 *
 * <p>夹具 {@code src/test/resources/interop/real-pi-session.jsonl} 是**真实 pi CLI
 * 在锚点 {@code 200387122} 写下的会话原文**（print 模式一轮：read 工具 → 最终答复，
 * provider 为本地 OpenAI 协议桩），逐字节未改。此前所有测试用的都是人手仿写的行。</p>
 */
class RealPiInteropTest {

    private static final DefaultJsonlFileSystem FS = new DefaultJsonlFileSystem();

    private static File realSessionFile() {
        String resource = RealPiInteropTest.class.getClassLoader()
            .getResource("interop/real-pi-session.jsonl").getFile();
        return new File(resource);
    }

    /** 真实 pi 会话必须能装载：1 头 + 7 条目，一个都不能少。 */
    @Test
    void loadsARealPiSessionFile() {
        var storage = JsonlSessionStorage.load(FS, realSessionFile().toPath());
        var ids = storage.getLog(LogOptions.none()).stream()
            .filter(LogItem.EntryItem.class::isInstance)
            .map(item -> ((LogItem.EntryItem) item).entry().id())
            .toList();

        assertThat(ids)
            .as("真 pi 行：model_change/thinking/user/assistant(toolUse)/toolResult/assistant(stop)")
            .containsExactly(
                "13cd0728", "7a0d359d", "1119f34f", "a03b96e7",
                "452695a3", "d7820148", "33453ab6");
    }

    /** 装载后的投影须按 pi 语义产出消息序列（系统提示 → 用户 → 助手 → 工具结果 → 助手）。 */
    @Test
    void projectsTheRealSession() {
        var storage = JsonlSessionStorage.load(FS, realSessionFile().toPath());
        var entries = storage.getLog(LogOptions.none()).stream()
            .filter(LogItem.EntryItem.class::isInstance)
            .map(item -> (Entry) ((LogItem.EntryItem) item).entry())
            .toList();

        var roles = com.pijava.agent.session.ContextEntries.toMessages(entries).stream()
            .map(com.pijava.ai.message.Message::role)
            .toList();

        assertThat(roles).containsExactly(
            "system", "user", "assistant", "tool", "assistant");
    }
}
