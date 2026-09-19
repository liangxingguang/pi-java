package com.pijava.ai.message;

import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑨（docs/36，B41）：**终局助手消息的 `usage` 必填且永不为空**。
 *
 * <p>pi 的 {@code AssistantMessage.usage: Usage} 是**必填**（{@code ai/src/types.ts:439}），
 * 而且 pi **没有任何一条路径**会产出没 usage 的助手消息 —— 11 个 provider 适配器、
 * {@code lazy.ts} 的装配失败、{@code faux}、中止/错误路
 * （{@code agent.ts:511-527} 的 {@code EMPTY_USAGE}、{@code recovery.ts:28-40} 的
 * {@code ZERO_USAGE}）**全都显式给零值**；**落盘的助手条目也带 usage**
 * （{@code session-manager.ts:1029-1056} 整条 stringify，真实夹具
 * {@code test/fixtures/large-session.jsonl:3} 佐证）。</p>
 *
 * <p>⚠️ **但工具结果的 {@code usage} 在 pi 是可选的**（{@code types.ts:459}）——
 * 工具没报用量时线上**确实没有**该键（{@code createErrorToolResult}
 * 根本不带 usage，{@code agent-loop.ts:767-772} → {@code :793}）。
 * ⇒ 兜零**只能落在助手消息上**，把手伸到工具结果就是**引入偏差**。</p>
 */
class AssistantMessageUsageTest {

    private static com.pijava.ai.message.AssistantMessage partialWithoutUsage() {
        return com.pijava.ai.message.AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("hi")));
    }

    @Test
    void terminalAssistantMessageAlwaysCarriesZeroUsageWhenTheStreamReportedNone() {
        var terminal = Message.AssistantMessage.fromPartial(partialWithoutUsage());

        assertThat(terminal.usage())
            .as("pi 的 usage 必填 ⇒ 兜零，**不是** null")
            .isNotNull();
        assertThat(terminal.usage().input()).isZero();
        assertThat(terminal.usage().output()).isZero();
        assertThat(terminal.usage().totalTokens()).isZero();
        assertThat(terminal.usage().cost()).as("零 cost 对象（与 pi 的初值同形）").isNotNull();
        assertThat(terminal.usage().cost().total()).isZero();
    }

    @Test
    void terminalAssistantMessageKeepsTheFullBreakdownWhenTheStreamReportedIt() {
        var full = new Usage(10, 5, 7, 3, null, null, 25,
            new Usage.Cost(0.1, 0.2, 0, 0, 0.3));
        var partial = partialWithoutUsage()
            .withUsage(new StreamEvent.UsageInfo(10, 5, null, full));

        var terminal = Message.AssistantMessage.fromPartial(partial);

        assertThat(terminal.usage()).isSameAs(full);
    }

    @Test
    void synthesizedUsageCarriesTotalsNotJustCounts() {
        // 合成路不只看 input/output：totalTokens 必须等于两者之和，否则用量面板
        // 上「总」那一格永远是 0。
        var partial = partialWithoutUsage()
            .withUsage(new StreamEvent.UsageInfo(120, 30, null));

        var terminal = Message.AssistantMessage.fromPartial(partial);

        assertThat(terminal.usage().totalTokens()).isEqualTo(150);
    }

    // ── 反向（R4）：工具结果的 usage **可以**为空，不许被兜零 ──────────

    @Test
    void toolResultUsageStaysNullWhenTheToolReportedNone() {
        var result = new Message.ToolResultMessage("call_1", "bash",
            List.of(new ContentBlock.TextContent("out")), null, null, List.of(), false);

        assertThat(result.usage())
            .as("pi 的 ToolResultMessage.usage 可选 ⇒ 保持 null（兜零是引入偏差）")
            .isNull();
    }
}
