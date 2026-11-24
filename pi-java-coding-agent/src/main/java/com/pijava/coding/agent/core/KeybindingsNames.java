package com.pijava.coding.agent.core;

import java.util.Map;
import java.util.Set;

/**
 * pi {@code keybindings.ts} 的键名表（L7 W8／K17）：legacy 动作名迁移与
 * {@code KeyId} 命名键集合。拆出以保持 {@link KeybindingsManager} 在
 * Checkstyle 500 行硬限内。
 */
final class KeybindingsNames {

    private KeybindingsNames() {}

    /** pi 默认键位名集合里 Java 键面使用的命名键。 */
    static final Set<String> NAMED_KEYS = Set.of(
        "esc", "enter", "tab", "backspace", "delete", "up", "down", "left", "right",
        "home", "end", "pageup", "pagedown", "insert", "null");

    /** pi {@code KEYBINDING_NAME_MIGRATIONS}（keybindings.ts:202-264）全表。 */
    static final Map<String, String> LEGACY_NAMES = Map.ofEntries(
        Map.entry("cursorUp", "tui.editor.cursorUp"),
        Map.entry("cursorDown", "tui.editor.cursorDown"),
        Map.entry("cursorLeft", "tui.editor.cursorLeft"),
        Map.entry("cursorRight", "tui.editor.cursorRight"),
        Map.entry("cursorWordLeft", "tui.editor.cursorWordLeft"),
        Map.entry("cursorWordRight", "tui.editor.cursorWordRight"),
        Map.entry("cursorLineStart", "tui.editor.cursorLineStart"),
        Map.entry("cursorLineEnd", "tui.editor.cursorLineEnd"),
        Map.entry("jumpForward", "tui.editor.jumpForward"),
        Map.entry("jumpBackward", "tui.editor.jumpBackward"),
        Map.entry("pageUp", "tui.editor.pageUp"),
        Map.entry("pageDown", "tui.editor.pageDown"),
        Map.entry("deleteCharBackward", "tui.editor.deleteCharBackward"),
        Map.entry("deleteCharForward", "tui.editor.deleteCharForward"),
        Map.entry("deleteWordBackward", "tui.editor.deleteWordBackward"),
        Map.entry("deleteWordForward", "tui.editor.deleteWordForward"),
        Map.entry("deleteToLineStart", "tui.editor.deleteToLineStart"),
        Map.entry("deleteToLineEnd", "tui.editor.deleteToLineEnd"),
        Map.entry("yank", "tui.editor.yank"),
        Map.entry("yankPop", "tui.editor.yankPop"),
        Map.entry("undo", "tui.editor.undo"),
        Map.entry("newLine", "tui.input.newLine"),
        Map.entry("submit", "tui.input.submit"),
        Map.entry("tab", "tui.input.tab"),
        Map.entry("copy", "tui.input.copy"),
        Map.entry("selectUp", "tui.select.up"),
        Map.entry("selectDown", "tui.select.down"),
        Map.entry("selectPageUp", "tui.select.pageUp"),
        Map.entry("selectPageDown", "tui.select.pageDown"),
        Map.entry("selectConfirm", "tui.select.confirm"),
        Map.entry("selectCancel", "tui.select.cancel"),
        Map.entry("interrupt", "app.interrupt"),
        Map.entry("clear", "app.clear"),
        Map.entry("exit", "app.exit"),
        Map.entry("suspend", "app.suspend"),
        Map.entry("cycleThinkingLevel", "app.thinking.cycle"),
        Map.entry("cycleModelForward", "app.model.cycleForward"),
        Map.entry("cycleModelBackward", "app.model.cycleBackward"),
        Map.entry("selectModel", "app.model.select"),
        Map.entry("expandTools", "app.tools.expand"),
        Map.entry("toggleThinking", "app.thinking.toggle"),
        Map.entry("toggleSessionNamedFilter", "app.session.toggleNamedFilter"),
        Map.entry("externalEditor", "app.editor.external"),
        Map.entry("followUp", "app.message.followUp"),
        Map.entry("dequeue", "app.message.dequeue"),
        Map.entry("pasteImage", "app.clipboard.pasteImage"),
        Map.entry("newSession", "app.session.new"),
        Map.entry("tree", "app.session.tree"),
        Map.entry("fork", "app.session.fork"),
        Map.entry("resume", "app.session.resume"),
        Map.entry("treeFoldOrUp", "app.tree.foldOrUp"),
        Map.entry("treeUnfoldOrDown", "app.tree.unfoldOrDown"),
        Map.entry("treeEditLabel", "app.tree.editLabel"),
        Map.entry("treeToggleLabelTimestamp", "app.tree.toggleLabelTimestamp"),
        Map.entry("toggleSessionPath", "app.session.togglePath"),
        Map.entry("toggleSessionSort", "app.session.toggleSort"),
        Map.entry("renameSession", "app.session.rename"),
        Map.entry("deleteSession", "app.session.delete"),
        Map.entry("deleteSessionNoninvasive", "app.session.deleteNoninvasive"));
}
