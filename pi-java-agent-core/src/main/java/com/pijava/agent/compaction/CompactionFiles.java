package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 压缩产物的文件清单（pi {@code harness/compaction/utils.ts:24-72} +
 * {@code compaction.ts:46-76}，B2，见 {@code docs/31 §8.30}）。
 *
 * <p>两样产物同源：摘要**文本**尾部的 {@code <read-files>}/{@code <modified-files>}
 * 两块，与落库 {@code Entry.Compaction.details} 的两个键。pi 从不为此问模型 ——
 * 清单是 assistant 的 toolCall 块里**确定性**抽出来的，所以这里没有 LLM 参与。</p>
 *
 * <p>抽取只认三个工具名（{@code read}/{@code write}/{@code edit}，pi 硬编码的
 * {@code switch}）；{@code bash}/{@code glob} 等一律不记。清单还会**跨压缩累积**：
 * 上一份 compaction entry 的 {@code details} 先回灌，再走本次丢弃的消息。</p>
 */
final class CompactionFiles {

    /** {@code Entry.Compaction.details} 的键（pi {@code CompactionDetails}）。 */
    static final String READ_FILES = "readFiles";

    /** 见 {@link #READ_FILES}。 */
    static final String MODIFIED_FILES = "modifiedFiles";

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
    static Lists collect(List<Message> messages, List<Entry> transcript) {
        var ops = new FileOperations();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Entry.Compaction previous) {
                carryOver(previous, ops);
                break;
            }
        }
        for (Message message : messages) {
            extractFromMessage(message, ops);
        }
        return compute(ops);
    }

    /**
     * 把上一份 compaction 的 {@code details} 回灌进累加器。
     *
     * <p>pi 的两份实现守卫不同：legacy 是 {@code !fromHook && details}
     * （{@code coding-agent/.../compaction.ts:52}），harness 只做形状守卫
     * （{@code compaction.ts:54-68}）。pi-java 的 {@code Entry.Compaction} **没有
     * {@code fromHook} 字段**，故采用 harness 那份：对象非 null 非数组 + 逐元素判字符串。
     * 差异后果（已知并接受）：钩子写下的 compaction entry 也会被回灌。</p>
     */
    private static void carryOver(Entry.Compaction previous, FileOperations ops) {
        Map<String, Object> details = previous.details();
        if (details == null) {
            return;
        }
        // pi 的 `typeof details === "object" && !Array.isArray(details)` 在 Java 侧
        // 是平凡成立的（details 的静态类型就是 Map），故形状守卫只剩下面两条逐键/逐元素判断。
        addPaths(details.get(READ_FILES), ops.read);
        addPaths(details.get(MODIFIED_FILES), ops.edited);
    }

    /** pi 的 {@code Array.isArray(...)} + {@code typeof path === "string"} 两道守卫。 */
    private static void addPaths(Object value, Set<String> target) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object element : list) {
            if (element instanceof String path) {
                target.add(path);
            }
        }
    }

    /**
     * 从一条消息的 assistant toolCall 块里记文件操作（pi
     * {@code utils.ts:24-51}）。
     *
     * <p>只认 {@code arguments.path} 且必须是**非空字符串**（pi 的
     * {@code if (!path) continue} 把空串也跳过）。</p>
     */
    static void extractFromMessage(Message message, FileOperations ops) {
        if (!"assistant".equals(message.role())) {
            return;
        }
        for (ContentBlock block : message.content()) {
            if (!(block instanceof ContentBlock.ToolUseContent call)) {
                continue;
            }
            if (!(call.arguments().get("path") instanceof String path) || path.isEmpty()) {
                continue;
            }
            switch (call.name()) {
                case "read" -> ops.read.add(path);
                case "write" -> ops.written.add(path);
                case "edit" -> ops.edited.add(path);
                default -> { /* pi 同：其它工具名不入账 */ }
            }
        }
    }

    /**
     * 拆分「只读」与「已修改」（pi {@code utils.ts:54-59}）：
     * {@code modified = edited ∪ written}，{@code readFiles = read ∖ modified}。
     * 排序用 {@link TreeSet}/{@code sorted()} 的 {@code String.compareTo} ——
     * 与 JS 默认 {@code sort()} 的 UTF-16 码元序**逐字相同**。
     */
    static Lists compute(FileOperations ops) {
        Set<String> modified = new TreeSet<>(ops.written);
        modified.addAll(ops.edited);
        List<String> modifiedFiles = List.copyOf(modified);
        List<String> readFiles = ops.read.stream()
            .filter(path -> !modified.contains(path))
            .sorted()
            .toList();
        return new Lists(readFiles, modifiedFiles);
    }

    /**
     * 摘要文本尾部要追加的块（pi {@code utils.ts:62-72}）：每块自带 {@code \n\n}
     * 前缀、块间**没有**额外分隔；两块都空时返回空串（摘要一个字符都不变）。
     */
    static String format(List<String> readFiles, List<String> modifiedFiles) {
        List<String> sections = new ArrayList<>();
        if (!readFiles.isEmpty()) {
            sections.add("<read-files>\n" + String.join("\n", readFiles) + "\n</read-files>");
        }
        if (!modifiedFiles.isEmpty()) {
            sections.add("<modified-files>\n" + String.join("\n", modifiedFiles) + "\n</modified-files>");
        }
        if (sections.isEmpty()) {
            return "";
        }
        return "\n\n" + String.join("\n\n", sections);
    }

    /** 一次压缩的文件清单：既是摘要尾部的文本来源，也是落库 {@code details} 的来源。 */
    record Lists(List<String> readFiles, List<String> modifiedFiles) {

        /** 防御性拷贝（{@code Entry.Compaction.details} 会长期持有它）。 */
        Lists {
            readFiles = List.copyOf(readFiles);
            modifiedFiles = List.copyOf(modifiedFiles);
        }

        /** 摘要文本尾部要追加的块；两块都空 ⇒ 空串。 */
        String formatted() {
            return format(readFiles, modifiedFiles);
        }

        /**
         * 落库用的 {@code details}：**两键恒在**、数组可为空（pi 从不写 {@code null}
         * 或缺键）。用 {@link LinkedHashMap} 保住 {@code readFiles}/{@code modifiedFiles}
         * 的键序 —— {@code Map.of}/{@code Map.copyOf} 的迭代序不保证，会让落盘的 JSONL
         * 与 pi 不可逐字节比对。
         */
        Map<String, Object> details() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(READ_FILES, readFiles);
            map.put(MODIFIED_FILES, modifiedFiles);
            return Collections.unmodifiableMap(map);
        }
    }
}
