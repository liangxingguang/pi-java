package com.pijava.agent.compaction;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;

/**
 * 压缩产物的文件清单（pi {@code compaction.ts:46-76}，B2，见 {@code 原 docs/31 §8.30}）。
 *
 * <p>与 {@link FileOperationLists}（公共件，docs/24 §7.3）的分工：这里只剩压缩路
 * 特有的<b>跨压缩累积</b> —— 上一份 compaction entry 的 {@code details} 先回灌
 * （带 {@code fromHook} 守卫，compaction.ts:70），再走本次丢弃的消息；抽取/拆分/
 * 文本尾块/details 四段全在公共件。</p>
 */
final class CompactionFiles {

    private CompactionFiles() {}

    /**
     * 回灌上一份 compaction 的清单，再抽本次被丢弃的消息，算出两个列表。
     *
     * <p>回扫规则照 pi（{@code compaction.ts:642-648}）：**从尾往前**找最后一份
     * {@code Entry.Compaction}。pi-java 的 marker 由
     * {@code CompactionExecutor:423} 放在转录下标 0，每次一击命中 —— 但回扫写法保留，
     * 它不该依赖「marker 恰在头部」这个实现细节。</p>
     *
     * @param messages   本次被丢弃、要进摘要的消息
     * @param transcript 完整转录（用于找回上一份 compaction entry）
     */
    static FileOperationLists.Lists collect(List<Message> messages, List<Entry> transcript) {
        var ops = new FileOperationLists.Accumulator();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Entry.Compaction previous) {
                carryOver(previous, ops);
                break;
            }
        }
        for (Message message : messages) {
            ops.addFromMessage(message);
        }
        return ops.compute();
    }

    /**
     * 把上一份 compaction 的 {@code details} 回灌进累加器。
     *
     * <p>守卫对位 pi {@code compaction.ts:70}：{@code !fromHook && details} —— 扩展
     * 生成的 entry 的 {@code details} 结构不可信，**不回灌**（{@code null} ≙ pi 的
     * undefined ⇒ pi-generated，回灌）。</p>
     */
    private static void carryOver(Entry.Compaction previous, FileOperationLists.Accumulator ops) {
        if (Boolean.TRUE.equals(previous.fromHook())) {
            return;
        }
        Map<String, Object> details = previous.details();
        if (details == null) {
            return;
        }
        ops.addFromDetails(details);
    }
}
