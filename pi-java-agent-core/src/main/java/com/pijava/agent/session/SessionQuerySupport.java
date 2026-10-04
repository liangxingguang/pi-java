package com.pijava.agent.session;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;

/**
 * {@link SessionState} 的查询侧小工具（包 12 抽离 —— 那个类已顶到 500 行上限）。
 *
 * <p>全是纯函数：判据换算（{@code customType}／{@code opKind} 字面量）、
 * 排序与游标校验。**没有一处读 {@code SessionState} 的实例态**，所以搬得干净。</p>
 */
final class SessionQuerySupport {

    private SessionQuerySupport() {}

    /** 条目的 {@code customType}（只有两种 custom 变体有），其余为 {@code null}。 */
    static String customTypeOf(Entry entry) {
        return switch (entry) {
            case Entry.Custom c -> c.customType();
            case Entry.CustomMessage cm -> cm.customType();
            default -> null;
        };
    }

    /** {@code operation_started} 的意图字面量。 */
    static String kindValue(LaneRecord.OperationStarted.Intent intent) {
        return switch (intent) {
            case LaneRecord.OperationStarted.Run r -> "run";
            case LaneRecord.OperationStarted.Compaction c -> "compaction";
            case LaneRecord.OperationStarted.Navigation n -> "navigation";
        };
    }

    /** 按 {@code order} 返回同一批元素的迭代序（{@code NEWEST_FIRST} 时复制后反转）。 */
    static <T> Iterable<T> ordered(List<T> items, EntryOrder order) {
        if (order == EntryOrder.OLDEST_FIRST) {
            return items;
        }
        List<T> reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    static void assertValidLimit(Integer limit) {
        if (limit != null && limit <= 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY, "limit must be a positive integer");
        }
    }

    static void assertValidCursor(EntryCursor cursor) {
        if (cursor != null && cursor.afterSeq() < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
    }

    static void assertValidCursor(Long afterSeq) {
        if (afterSeq != null && afterSeq < 0) {
            throw new SessionError(SessionErrorCode.INVALID_QUERY,
                "cursor sequence must be a non-negative integer");
        }
    }
}
