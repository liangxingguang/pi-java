package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 文件清单公共件（docs/09 §7.3）—— pi {@code compaction/utils.ts} 被
 * {@code compaction.ts}（压缩）与 {@code branch-summarization.ts}（分支摘要）
 * 共用；{@link CompactionFiles}（压缩路，带回灌守卫）与 coding-agent 的
 * {@code BranchSummarization}（分支摘要路，fromHook 守卫同形）都从这里取
 * 「抽取 → 拆分 → 文本尾块 → details 两键」四段。
 *
 * <p>抽取只认三个工具名（{@code read}/{@code write}/{@code edit}，pi 硬编码的
 * {@code switch}）；{@code bash}/{@code glob} 等一律不记。清单由 assistant 的
 * toolCall 块**确定性**抽出 —— 从不为此问模型。</p>
 */
public final class FileOperationLists {

    /** {@code details} 的键（pi {@code CompactionDetails}）。 */
    public static final String READ_FILES = "readFiles";

    /** 见 {@link #READ_FILES}。 */
    public static final String MODIFIED_FILES = "modifiedFiles";

    private FileOperationLists() {}

    /**
     * 可变累加器（pi {@code utils.ts:5-21} 的 {@code FileOperations}）——
     * pi 也是边走消息边往同一个对象里 {@code add}。
     */
    public static final class Accumulator {
        private final Set<String> read = new LinkedHashSet<>();
        private final Set<String> written = new LinkedHashSet<>();
        private final Set<String> edited = new LinkedHashSet<>();

        /**
         * 从一条消息的 assistant toolCall 块里记文件操作（pi
         * {@code utils.ts:24-51}）。只认 {@code arguments.path} 且必须是
         * **非空字符串**（pi 的 {@code if (!path) continue} 把空串也跳过）。
         */
        public void addFromMessage(Message message) {
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
                    case "read" -> read.add(path);
                    case "write" -> written.add(path);
                    case "edit" -> edited.add(path);
                    default -> { /* pi 同：其它工具名不入账 */ }
                }
            }
        }

        /**
         * 从一份 entry 的 {@code details} 回灌（pi 的
         * {@code Array.isArray(...)} + {@code typeof path === "string"} 两道守卫；
         * fromHook 守卫在调用方 —— compaction.ts:70 / branch-summarization.ts:204）。
         */
        public void addFromDetails(Map<String, Object> details) {
            if (details == null) {
                return;
            }
            addPaths(details.get(READ_FILES), read);
            addPaths(details.get(MODIFIED_FILES), edited);
        }

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
         * 拆分「只读」与「已修改」（pi {@code utils.ts:54-59}）：
         * {@code modified = edited ∪ written}，{@code readFiles = read ∖ modified}。
         * 排序用 {@link TreeSet}/{@code sorted()} 的 {@code String.compareTo} ——
         * 与 JS 默认 {@code sort()} 的 UTF-16 码元序**逐字相同**。
         */
        public Lists compute() {
            Set<String> modified = new TreeSet<>(written);
            modified.addAll(edited);
            List<String> modifiedFiles = List.copyOf(modified);
            List<String> readFiles = read.stream()
                .filter(path -> !modified.contains(path))
                .sorted()
                .toList();
            return new Lists(readFiles, modifiedFiles);
        }
    }

    /** 一次摘要的文件清单：既是摘要尾部的文本来源，也是落库 {@code details} 的来源。 */
    public record Lists(List<String> readFiles, List<String> modifiedFiles) {

        /** 防御性拷贝（{@code details} 会长期持有它）。 */
        public Lists {
            readFiles = List.copyOf(readFiles);
            modifiedFiles = List.copyOf(modifiedFiles);
        }

        /**
         * 摘要文本尾部要追加的块（pi {@code utils.ts:62-72}）：每块自带
         * {@code \n\n} 前缀、块间**没有**额外分隔；两块都空 ⇒ 空串。
         */
        public String formatted() {
            List<String> sections = new ArrayList<>();
            if (!readFiles.isEmpty()) {
                sections.add("<read-files>\n" + String.join("\n", readFiles) + "\n</read-files>");
            }
            if (!modifiedFiles.isEmpty()) {
                sections.add("<modified-files>\n" + String.join("\n", modifiedFiles)
                    + "\n</modified-files>");
            }
            if (sections.isEmpty()) {
                return "";
            }
            return "\n\n" + String.join("\n\n", sections);
        }

        /**
         * 落库用的 {@code details}：**两键恒在**、数组可为空（pi 从不写 {@code null}
         * 或缺键）。{@link LinkedHashMap} 保 {@code readFiles}/{@code modifiedFiles}
         * 键序 —— 落盘 JSONL 才能与 pi 逐字节比对。
         */
        public Map<String, Object> details() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(READ_FILES, readFiles);
            map.put(MODIFIED_FILES, modifiedFiles);
            return Collections.unmodifiableMap(map);
        }
    }
}
