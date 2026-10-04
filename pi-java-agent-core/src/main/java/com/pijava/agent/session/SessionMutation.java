package com.pijava.agent.session;


import java.time.Instant;

import com.pijava.agent.record.LaneRecord;

/**
 * A single persistable session mutation (aligned with pi's
 * {@code SessionMutation} union). {@code seq} is strictly increasing.
 */
public sealed interface SessionMutation {

    /** The sequence number consumed by this mutation. */
    long seq();

    /** Append an entry (with an optional lane for chain validation). */
    record Entry(String lane, com.pijava.agent.entry.Entry entry) implements SessionMutation {
        @Override
        public long seq() {
            return entry.seq();
        }
    }

    /** Append a lane record. */
    record Record(String parentId, LaneRecord record) implements SessionMutation {
        @Override
        public long seq() {
            return record.seq();
        }
    }

    /**
     * Create or move a lane.
     *
     * <p>⚠️ {@code parentId} 与 {@code timestamp} 是**为 pi 的线形状补的**（{@code docs/12 §6 D2}）：
     * pi 的每一行都是 {@code SessionEntryBase}（{@code session-manager.ts:57-63}），
     * {@code parentId}/{@code timestamp} 必填；而本仓这三个合成行**原本一个身份字段都没有**。
     * 二者在**创建 mutation 时**就取好（那时才知道当前叶是谁），**不在编码期合成** ——
     * 编码必须是纯函数。行的 {@code id} 由 {@code seq} 派生。</p>
     *
     * <p>为什么必须链进树：pi 的 {@code _buildIndex} 把**每一行**都当叶
     * （{@code session-manager.ts:1101-1120}），{@code getBranch()} 再从叶沿 {@code parentId} 回溯。
     * 合成行若 {@code parentId} 为 null，pi 打开本仓的会话就**看不到任何消息**。</p>
     */
    record Lane(long seq, String parentId, Instant timestamp, String lane, String leafId)
        implements SessionMutation {}

    /** Set (or clear, when {@code name} is null) the session name. 见 {@link Lane} 的补身份说明。 */
    record FactName(long seq, String parentId, Instant timestamp, String name)
        implements SessionMutation {}

    /** Set (or clear, when {@code label} is null) an entry label. 见 {@link Lane} 的补身份说明。 */
    record FactLabel(long seq, String parentId, Instant timestamp, String targetId, String label)
        implements SessionMutation {}
}
