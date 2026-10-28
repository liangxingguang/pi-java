package com.pijava.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.NestedToolCallRecord;
import com.pijava.ai.message.NestedToolCalls;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162/T12）：pi {@code compaction-nested-calls.test.ts}（27 行）镜像——
 * toolResult 的 {@code nestedCalls} 里 read/edit 的 {@code path} 进压缩文件清单；
 * 参数被省略（{@code argumentsBytes} 形态）的调用不产生文件项。
 */
class NestedCallsFileOpsTest {

    private static NestedToolCallRecord call(String id, String name, String argsJson,
                                             Long bytes) throws Exception {
        return new NestedToolCallRecord(id, name,
            argsJson == null ? null : SessionJson.mapper().readTree(argsJson), bytes,
            NestedToolCallRecord.Status.OK, 1L, null);
    }

    @Test
    void nestedReadAndEditPathsLandInTheFileLists() throws Exception {
        var accumulator = new FileOperationLists.Accumulator();
        var result = new Message.ToolResultMessage("c1", "run_tools",
            List.of(new ContentBlock.TextContent("done")), false)
            .withNestedCalls(new NestedToolCalls(List.of(
                call("c1/1", "read", "{\"path\":\"a.ts\"}", null),
                // 参数省略（超限）——pi 的 write 不产生文件项（compaction-nested-calls 同断言）
                call("c1/2", "write", null, 9000L),
                call("c1/3", "edit", "{\"path\":\"c.ts\"}", null)), true));

        accumulator.addFromMessage(result);
        var lists = accumulator.compute();

        assertThat(lists.readFiles()).containsExactly("a.ts");
        assertThat(lists.modifiedFiles()).containsExactly("c.ts");
    }

    @Test
    void resultsWithoutNestedCallsContributeNothing() {
        var accumulator = new FileOperationLists.Accumulator();
        accumulator.addFromMessage(new Message.ToolResultMessage("c1", "bash",
            List.of(new ContentBlock.TextContent("x")), false));

        var lists = accumulator.compute();
        assertThat(lists.readFiles()).isEmpty();
        assertThat(lists.modifiedFiles()).isEmpty();
    }
}
