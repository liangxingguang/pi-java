package com.pijava.tui.component.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.SessionTreeBuilder;
import com.pijava.coding.agent.core.SessionTreeNode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TreeListModel} 的行为规格——pi {@code test/tree-selector.test.ts}
 * （739 行）13 用例的对位移植（docs/19 §6）：pi 驱动 {@code handleInput(键串)}，
 * 此处直驱模型键语义方法（键分派是 overlay 层职责，见
 * {@code TreeSelectorOverlayTest}）。夹具与 pi 逐字同形。
 */
class TreeListModelTest {

    private static final Instant T0 = Instant.parse("2026-03-28T14:00:00Z");
    /** pi 测试终端高 24 ⇒ maxVisibleLines = max(5, 24/2) = 12。 */
    private static final int MAX_VISIBLE = 12;

    // ── pi 夹具工厂（tree-selector.test.ts:24-98 对位）─────────

    private static Entry.Message userMessage(String id, String parentId, String content) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(content)), null), null);
    }

    private static Entry.Message assistantMessage(String id, String parentId, String text) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
                StopReason.STOP, null),
            null);
    }

    private static Entry.Message toolCallOnlyAssistant(String id, String parentId) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.AssistantMessage(List.of(new ContentBlock.ToolUseContent(
                "tc-" + id, "read", Map.of("path", "test.ts"))),
                StopReason.TOOL_USE, null),
            null);
    }

    private static Entry.ModelChange modelChange(String id, String parentId) {
        return new Entry.ModelChange(id, 0, parentId, T0, "anthropic", "claude-sonnet-4");
    }

    private static Entry.ThinkingLevelChange thinkingLevelChange(String id, String parentId) {
        return new Entry.ThinkingLevelChange(id, 0, parentId, T0, "high");
    }

    private static Entry.ContextEdit contextEdit(String id, String parentId, String targetId) {
        return new Entry.ContextEdit(id, 0, parentId, T0, targetId, null);
    }

    /** pi 测试 buildTree 对位（经 T1 的 SessionTreeBuilder——同两轮链接语义）。 */
    private static List<SessionTreeNode> buildTree(List<? extends Entry> entries) {
        return SessionTreeBuilder.build(entries, id -> null, id -> null);
    }

    private static TreeListModel model(List<? extends Entry> entries, String currentLeafId) {
        return new TreeListModel(buildTree(entries), currentLeafId, MAX_VISIBLE, null, null);
    }

    private static TreeListModel model(List<? extends Entry> entries, String currentLeafId,
                                       String initialSelectedId, FilterMode initialFilterMode) {
        return new TreeListModel(buildTree(entries), currentLeafId, MAX_VISIBLE,
            initialSelectedId, initialFilterMode);
    }

    private static String selectedId(TreeListModel m) {
        var node = m.selectedNode();
        return node == null ? null : node.entry().id();
    }

    /** pi 分支夹具（:457-474 逐字）：A/B 两分支，活动叶 asst-4a。 */
    private static List<Entry> branchingEntries() {
        return List.of(
            userMessage("user-1", null, "first message"),
            assistantMessage("asst-1", "user-1", "response 1"),
            userMessage("user-2", "asst-1", "second message"),
            assistantMessage("asst-2", "user-2", "response 2"),
            // Branch A (active)
            userMessage("user-3a", "asst-2", "branch A start"),
            assistantMessage("asst-3a", "user-3a", "branch A response"),
            userMessage("user-4a", "asst-3a", "branch A deep"),
            assistantMessage("asst-4a", "user-4a", "branch A leaf"),
            // Branch B
            userMessage("user-3b", "asst-2", "branch B start"),
            assistantMessage("asst-3b", "user-3b", "branch B response"),
            userMessage("user-4b", "asst-3b", "branch B deep"));
    }

    // ── pi :130 ─────────────────────────────────────────────

    @Test
    void focusesNearestVisibleAncestorWhenLeafIsFilteredModelChange() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            userMessage("user-2", "asst-1", "active branch"),
            modelChange("model-1", "user-2"),          // CURRENT LEAF（默认过滤）
            userMessage("user-3", "asst-1", "sibling branch")),
            "model-1");
        // 落在最近可见祖先 user-2，而非兄弟 user-3。
        assertThat(selectedId(m)).isEqualTo("user-2");
    }

    // ── pi :159 ─────────────────────────────────────────────

    @Test
    void contextEditHiddenByDefaultVisibleInAllMode() {
        var entries = List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            contextEdit("edit-1", "asst-1", "asst-1"));
        var defaultModel = model(entries, "edit-1");
        assertThat(selectedId(defaultModel)).isEqualTo("asst-1");
        assertThat(defaultModel.filteredNodes())
            .noneMatch(n -> n.node().entry().id().equals("edit-1"));

        var allModel = model(entries, "edit-1", null, FilterMode.ALL);
        assertThat(allModel.filteredNodes())
            .anyMatch(n -> n.node().entry().id().equals("edit-1"));
    }

    // ── pi :196 ─────────────────────────────────────────────

    @Test
    void focusesNearestVisibleAncestorWhenLeafIsThinkingLevelChange() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            userMessage("user-2", "asst-1", "active branch"),
            thinkingLevelChange("thinking-1", "user-2"),
            userMessage("user-3", "asst-1", "sibling branch")),
            "thinking-1");
        assertThat(selectedId(m)).isEqualTo("user-2");
    }

    // ── pi :227/:256 ────────────────────────────────────────

    @Test
    void userOnlyFilterMovesToParentAndDefaultKeepsIt() {
        var entries = List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            userMessage("user-2", "asst-1", "active branch"),
            assistantMessage("asst-2", "user-2", "response"),
            userMessage("user-3", "asst-1", "sibling branch"));
        var m = model(entries, "asst-2");
        assertThat(selectedId(m)).isEqualTo("asst-2");

        // ctrl+u（overlay 互切语义的直臂：default → user-only）。
        m.setFilter(FilterMode.USER_ONLY);
        assertThat(selectedId(m)).isEqualTo("user-2");

        // ctrl+d 回 default：保持 user-2（父遍历落点即新选中）。
        m.setFilter(FilterMode.DEFAULT);
        assertThat(selectedId(m)).isEqualTo("user-2");
    }

    // ── pi :314 ─────────────────────────────────────────────

    @Test
    void copyTextIsTheFullSelectedMessage() {
        var message = "long message ".repeat(30) + "\nsecond line";
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", message)),
            "asst-1");
        assertThat(m.selectedCopyText()).isEqualTo(message);
    }

    // ── pi :336 ─────────────────────────────────────────────

    @Test
    void labelTimestampToggleFlipsFlagAndStatusLabel() {
        var labelTs = Instant.parse("2026-03-28T14:32:00Z");
        var tree = SessionTreeBuilder.build(
            List.of(userMessage("user-1", null, "hello"),
                assistantMessage("asst-1", "user-1", "hi")),
            id -> "user-1".equals(id) ? "checkpoint" : null,
            id -> "user-1".equals(id) ? labelTs : null);
        var m = new TreeListModel(tree, "asst-1", MAX_VISIBLE, null, null);

        assertThat(m.showLabelTimestamps()).isFalse();
        assertThat(m.statusLabels()).doesNotContain("[+label time]");
        // labeled-only 过滤经解析 label 生效（裁决①：不依赖 label 条目行）。
        m.setFilter(FilterMode.LABELED_ONLY);
        assertThat(m.filteredNodes()).extracting(n -> n.node().entry().id())
            .containsExactly("user-1");
        m.setFilter(FilterMode.DEFAULT);

        m.toggleLabelTimestamps();
        assertThat(m.showLabelTimestamps()).isTrue();
        assertThat(m.statusLabels()).contains("[+label time]");
    }

    // ── pi :366/:400 ────────────────────────────────────────

    @Test
    void emptyLabeledFilterRoundTripPreservesSelection() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            userMessage("user-2", "asst-1", "bye"),
            assistantMessage("asst-2", "user-2", "goodbye")),
            "asst-2");
        assertThat(selectedId(m)).isEqualTo("asst-2");

        m.setFilter(FilterMode.LABELED_ONLY);   // 无标签 ⇒ 空
        assertThat(m.selectedNode()).isNull();

        m.setFilter(FilterMode.DEFAULT);
        assertThat(selectedId(m)).isEqualTo("asst-2");
    }

    @Test
    void multipleEmptyFilterSwitchesPreserveSelection() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi")),
            "asst-1");
        assertThat(selectedId(m)).isEqualTo("asst-1");

        m.setFilter(FilterMode.LABELED_ONLY);
        assertThat(m.selectedNode()).isNull();
        m.setFilter(FilterMode.DEFAULT);
        assertThat(selectedId(m)).isEqualTo("asst-1");
        m.setFilter(FilterMode.LABELED_ONLY);
        assertThat(m.selectedNode()).isNull();
        m.setFilter(FilterMode.DEFAULT);
        assertThat(selectedId(m)).isEqualTo("asst-1");
    }

    // ── pi :476/:512（ctrl/alt 同臂，模型层不分别名）─────────

    @Test
    void foldThenUnfoldThenSegmentJumpToLeaf() {
        var m = model(branchingEntries(), "asst-4a");

        m.foldOrUp();                       // asst-4a → user-3a（段首）
        assertThat(selectedId(m)).isEqualTo("user-3a");
        m.foldOrUp();                       // 折叠 user-3a
        assertThat(selectedId(m)).isEqualTo("user-3a");
        assertThat(m.isFolded("user-3a")).isTrue();

        m.moveDown();                       // user-3a → user-3b（子代隐）
        assertThat(selectedId(m)).isEqualTo("user-3b");
        m.moveUp();
        assertThat(selectedId(m)).isEqualTo("user-3a");

        m.unfoldOrDown();                   // 展开 user-3a
        assertThat(selectedId(m)).isEqualTo("user-3a");
        assertThat(m.isFolded("user-3a")).isFalse();
        m.moveDown();                       // 子代恢复
        assertThat(selectedId(m)).isEqualTo("asst-3a");

        m.foldOrUp();                       // asst-3a → user-3a
        assertThat(selectedId(m)).isEqualTo("user-3a");
        m.unfoldOrDown();                   // 段跳直下到叶
        assertThat(selectedId(m)).isEqualTo("asst-4a");
    }

    // ── pi :536 ─────────────────────────────────────────────

    @Test
    void foldingRootHidesSubtreeNestedFoldPreserved() {
        var m = model(branchingEntries(), "asst-4a");

        m.foldOrUp();                       // → user-3a
        m.foldOrUp();                       // 折 user-3a
        m.foldOrUp();                       // user-3a（已折）→ user-1
        assertThat(selectedId(m)).isEqualTo("user-1");
        m.foldOrUp();                       // 折 user-1
        assertThat(m.isFolded("user-1")).isTrue();

        m.moveDown();                       // 唯一可见行 ⇒ 环绕不动
        assertThat(selectedId(m)).isEqualTo("user-1");

        m.unfoldOrDown();                   // 展开 user-1
        m.unfoldOrDown();                   // 段跳：user-3a 仍折 ⇒ 停 user-3a
        assertThat(selectedId(m)).isEqualTo("user-3a");
        assertThat(m.isFolded("user-3a")).isTrue();
        m.moveDown();                       // user-3a → user-3b
        assertThat(selectedId(m)).isEqualTo("user-3b");
    }

    // ── pi :572 ─────────────────────────────────────────────

    @Test
    void foldAndNavigateOnNonActiveBranch() {
        var m = model(branchingEntries(), "asst-4a");

        boolean found = false;
        for (int i = 0; i < 20; i++) {
            m.moveDown();
            if ("user-3b".equals(selectedId(m))) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();

        m.unfoldOrDown();                   // user-3b → 段跳到叶 user-4b
        assertThat(selectedId(m)).isEqualTo("user-4b");
        m.foldOrUp();                       // → user-3b
        assertThat(selectedId(m)).isEqualTo("user-3b");
        m.foldOrUp();                       // 折 user-3b
        m.foldOrUp();                       // user-3b（已折）→ user-1
        assertThat(selectedId(m)).isEqualTo("user-1");
    }

    // ── pi :607 ─────────────────────────────────────────────

    @Test
    void foldAndNavigateWithMultipleRoots() {
        var m = model(List.of(
            userMessage("user-1", null, "first root"),
            assistantMessage("asst-1", "user-1", "response 1"),
            userMessage("user-2", null, "second root"),
            assistantMessage("asst-2", "user-2", "response 2")),
            "asst-1");
        assertThat(m.multipleRoots()).isTrue();
        assertThat(selectedId(m)).isEqualTo("asst-1");

        m.foldOrUp();                       // asst-1 → user-1
        assertThat(selectedId(m)).isEqualTo("user-1");
        m.foldOrUp();                       // 折 user-1
        m.moveDown();                       // user-1 → user-2
        assertThat(selectedId(m)).isEqualTo("user-2");
        m.unfoldOrDown();                   // 段跳到叶 asst-2
        assertThat(selectedId(m)).isEqualTo("asst-2");
        m.foldOrUp();                       // → user-2
        m.foldOrUp();                       // 折 user-2
        m.foldOrUp();                       // 已折的根 ⇒ 原地
        assertThat(selectedId(m)).isEqualTo("user-2");
    }

    // ── pi :648 ─────────────────────────────────────────────

    @Test
    void foldingRootHidesDescendantsEvenWithFilteredIntermediate() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            toolCallOnlyAssistant("tool-asst-1", "user-1"),   // 默认过滤
            userMessage("user-2", "tool-asst-1", "follow up"),
            assistantMessage("asst-2", "user-2", "response")),
            "asst-2");
        // 默认过滤下纯工具 assistant 隐（非当前叶）。
        assertThat(m.filteredNodes())
            .noneMatch(n -> n.node().entry().id().equals("tool-asst-1"));

        m.foldOrUp();                       // asst-2 → user-1
        assertThat(selectedId(m)).isEqualTo("user-1");
        m.foldOrUp();                       // 折 user-1
        m.moveDown();                       // 唯一可见行 ⇒ 环绕不动
        assertThat(selectedId(m)).isEqualTo("user-1");
    }

    // ── pi :676/:709 ────────────────────────────────────────

    @Test
    void searchResetsFoldState() {
        var m = model(branchingEntries(), "asst-4a");
        m.foldOrUp();
        m.foldOrUp();                       // 折 user-3a
        m.moveDown();
        assertThat(selectedId(m)).isEqualTo("user-3b");

        m.appendSearch("b");                // 搜索清折叠
        assertThat(m.isFolded("user-3a")).isFalse();
        assertThat(m.clearSearchIfAny()).isTrue();

        String currentId = "";
        for (int i = 0; i < 20; i++) {
            m.moveDown();
            currentId = selectedId(m);
            if ("user-3a".equals(currentId)) {
                break;
            }
        }
        assertThat(currentId).isEqualTo("user-3a");
        m.moveDown();                       // 折叠已清 ⇒ asst-3a（非 user-3b）
        assertThat(selectedId(m)).isEqualTo("asst-3a");
    }

    @Test
    void filterModeChangeResetsFoldState() {
        var m = model(branchingEntries(), "asst-4a");
        m.foldOrUp();
        m.foldOrUp();                       // 折 user-3a
        m.setFilter(FilterMode.USER_ONLY);  // 过滤切换清折叠
        assertThat(m.isFolded("user-3a")).isFalse();
        m.setFilter(FilterMode.DEFAULT);

        String currentId = "";
        for (int i = 0; i < 20; i++) {
            m.moveDown();
            currentId = selectedId(m);
            if ("user-3a".equals(currentId)) {
                break;
            }
        }
        assertThat(currentId).isEqualTo("user-3a");
        m.moveDown();
        assertThat(selectedId(m)).isEqualTo("asst-3a");
    }

    // ── pi :1006-1010（环绕）＋ 分词 AND/backspace/cancel 第一级 ──

    @Test
    void upDownWrapsAround() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi")),
            "user-1");
        assertThat(selectedId(m)).isEqualTo("user-1");
        m.moveUp();                         // 首行上移 ⇒ 末行
        assertThat(selectedId(m)).isEqualTo("asst-1");
        m.moveDown();                       // 末行下移 ⇒ 首行
        assertThat(selectedId(m)).isEqualTo("user-1");
    }

    @Test
    void searchTokensAreAndedAndBackspaceEdits() {
        var m = model(branchingEntries(), "asst-4a");
        m.appendSearch("branch");
        assertThat(m.searchQuery()).isEqualTo("branch");
        // 语料含 "branch" 的行才显（role/文本合并语料）。
        assertThat(m.filteredNodes()).extracting(n -> n.node().entry().id())
            .contains("user-3a", "asst-3a", "user-4a", "asst-4a", "user-3b")
            .doesNotContain("user-1");

        m.appendSearch(" response");
        // 两 token 全中才显：asst-3a "assistant branch A response"、
        // asst-3b "assistant branch B response"；asst-4a 语料无 "response"。
        assertThat(m.filteredNodes()).extracting(n -> n.node().entry().id())
            .containsExactly("asst-3a", "asst-3b");

        m.backspaceSearch();
        assertThat(m.searchQuery()).isEqualTo("branch respons");
        assertThat(m.clearSearchIfAny()).isTrue();   // 第一级：清搜索＋清折叠
        assertThat(m.searchQuery()).isEmpty();
        assertThat(m.clearSearchIfAny()).isFalse();  // 无搜索 ⇒ 交给 onCancel
    }

    // ── 过滤谓词边界（pi :341-353 逐字：usage 恒隐／当前叶恒显）─

    @Test
    void usageAlwaysHiddenAndCurrentLeafAlwaysVisible() {
        var usage = new Entry.Usage("usage-1", 0, "asst-1", T0,
            "assistant", "anthropic", "claude-sonnet-4", null, null,
            null, null, null, null, null);
        var entries = List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi"),
            usage);
        var m = model(entries, "asst-1");
        assertThat(m.filteredNodes())
            .noneMatch(n -> n.node().entry() instanceof Entry.Usage);

        // 纯工具 assistant 作当前叶 ⇒ 恒显（pi :346 的 !isCurrentLeaf 门）。
        var leafModel = model(List.of(
            userMessage("user-1", null, "hello"),
            toolCallOnlyAssistant("tool-asst-1", "user-1")),
            "tool-asst-1");
        assertThat(leafModel.filteredNodes())
            .anyMatch(n -> n.node().entry().id().equals("tool-asst-1"));
    }

    @Test
    void errorAssistantWithoutTextStaysVisible() {
        // pi :347-353：无文本但 stopReason 非 stop/toolUse ⇒ 显（errorMessage 行）。
        var errored = new Entry.Message("asst-err", 0, "user-1", T0,
            new Message.AssistantMessage(List.of(), StopReason.ERROR, null), null);
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            errored),
            "user-1");
        assertThat(m.filteredNodes())
            .anyMatch(n -> n.node().entry().id().equals("asst-err"));
    }

    // ── updateNodeLabel（pi :637-645）＋空树 ─────────────────

    @Test
    void updateNodeLabelReplacesLabelAndTimestamp() {
        var m = model(List.of(
            userMessage("user-1", null, "hello"),
            assistantMessage("asst-1", "user-1", "hi")),
            "asst-1");
        var ts = Instant.parse("2026-10-21T08:00:00Z");
        m.updateNodeLabel("user-1", "checkpoint", ts);
        var flat = m.filteredNodes().stream()
            .filter(n -> n.node().entry().id().equals("user-1")).findFirst().orElseThrow();
        assertThat(flat.node().label()).isEqualTo("checkpoint");
        assertThat(flat.node().labelTimestamp()).isEqualTo(ts);

        m.updateNodeLabel("user-1", null, null);   // 清除 ⇒ 两字段同删
        var cleared = m.filteredNodes().stream()
            .filter(n -> n.node().entry().id().equals("user-1")).findFirst().orElseThrow();
        assertThat(cleared.node().label()).isNull();
        assertThat(cleared.node().labelTimestamp()).isNull();
    }

    @Test
    void emptyTreeHasNoSelection() {
        var m = new TreeListModel(List.of(), null, MAX_VISIBLE, null, null);
        assertThat(m.filteredNodes()).isEmpty();
        assertThat(m.selectedNode()).isNull();
        assertThat(m.selectedEntryId()).isNull();
    }

    // ── 恢复选中粒度（D40 销号实证：initialSelectedId＝entry 节点）─

    @Test
    void initialSelectedIdRestoresSelectionAtEntryGranularity() {
        var m = model(branchingEntries(), "asst-4a", "user-3b", null);
        assertThat(selectedId(m)).isEqualTo("user-3b");
        // 未知 id ⇒ pi findNearestVisibleIndex 回退**最后一行**（:175-176）。
        var filtered = model(branchingEntries(), "asst-4a", "tool-x", null);
        assertThat(selectedId(filtered)).isEqualTo("user-4b");
    }

    @Test
    void toolResultLookupUsesToolCallMap() {
        // pi :248-259：assistant 的 toolCall 块平铺时收集，toolResult 行反查。
        var entries = List.of(
            userMessage("user-1", null, "hello"),
            toolCallOnlyAssistant("asst-1", "user-1"),
            new Entry.Message("result-1", 0, "asst-1", T0,
                new Message.ToolResultMessage("tc-asst-1", "read",
                    List.of(new ContentBlock.TextContent("file body")), false),
                null));
        var m = model(entries, "result-1");
        var info = m.toolCallMap().get("tc-asst-1");
        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("read");
        assertThat(info.arguments()).containsEntry("path", "test.ts");
    }
}
