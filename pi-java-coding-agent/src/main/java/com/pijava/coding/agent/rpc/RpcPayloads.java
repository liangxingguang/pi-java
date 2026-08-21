package com.pijava.coding.agent.rpc;

import java.util.List;

import com.pijava.agent.entry.Entry;

/**
 * 末批命令（P6-5d）的响应载荷 record —— 对齐 pi {@code rpc-types.ts} 各命令
 * {@code data} 形状。包私有：仅 rpc 包内部使用（RpcDispatcher 构建，测试断言）。
 */
final class RpcPayloads {

    private RpcPayloads() {}

    /** pi {@code BashResult}：stdout+stderr 合并输出与退出信息。 */
    record RpcBashResult(
        String output,
        Integer exitCode,   // null = 被取消/未拿到退出码
        boolean cancelled,
        boolean truncated,
        String fullOutputPath
    ) {}

    /** pi {@code SessionTreeNode}：entry + 子节点树 + 可选标签。 */
    record RpcSessionTreeNode(
        Entry entry,
        List<RpcSessionTreeNode> children,
        String label
    ) {}

    /** pi {@code get_fork_messages} 的消息项。 */
    record RpcForkMessage(
        String entryId,
        String text
    ) {}

    /** pi {@code get_fork_messages} 响应 data。 */
    record RpcForkMessagesData(
        List<RpcForkMessage> messages
    ) {}

    /** pi {@code get_entries} 响应 data（entries 直接复用 {@link Entry} 序列化）。 */
    record RpcEntriesData(
        List<Entry> entries,
        String leafId
    ) {}

    /** pi {@code get_tree} 响应 data。 */
    record RpcTreeData(
        List<RpcSessionTreeNode> tree,
        String leafId
    ) {}
}
