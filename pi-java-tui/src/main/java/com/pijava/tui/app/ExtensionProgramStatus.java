package com.pijava.tui.app;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.extension.ExtensionBlockedStatus;
import com.pijava.tui.util.ProgramStatusProtocol.Kind;
import com.pijava.tui.util.ProgramStatusReporter;
import com.pijava.tui.util.ProgramStatusReporter.BlockedStatus;

/**
 * 扩展 UI 的 OSC 7501 阻塞来源簿（docs/26 §4.3，K39）：编辑器槽对话框共用
 * {@code extension-dialog} 来源（最近者优先），custom overlay 各自独立来源
 * {@code extension-overlay:N}（pi {@code interactive-mode.ts} 的
 * {@code setBlocked} 接线）。
 */
final class ExtensionProgramStatus {

    private static final String DIALOG_SOURCE = "extension-dialog";

    private final List<BlockedStatus> dialogs = new CopyOnWriteArrayList<>();
    private final Set<String> overlaySources = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextOverlayId = new AtomicInteger();
    private volatile ProgramStatusReporter reporter;

    /** 接线/更换上报器（{@code null} ⇒ 不报）。 */
    void setReporter(@Nullable ProgramStatusReporter value) {
        this.reporter = value;
        refresh();
    }

    /** 打开一个编辑器槽对话框（压入 blocked 栈并重报顶层）。 */
    void pushDialog(BlockedStatus blocked) {
        dialogs.add(blocked);
        refresh();
    }

    /** 关闭一个编辑器槽对话框并重报新的顶层。 */
    void popDialog(BlockedStatus blocked) {
        dialogs.remove(blocked);
        refresh();
    }

    /** 打开 custom overlay：登记的来源键在关闭时交回 {@link #closeOverlay}。 */
    String openOverlay(@Nullable ExtensionBlockedStatus blocked) {
        String source = "extension-overlay:" + nextOverlayId.getAndIncrement();
        overlaySources.add(source);
        var current = reporter;
        if (current != null) {
            current.setBlocked(source, convert(blocked));
        }
        return source;
    }

    /** 关闭 custom overlay 的阻塞来源。 */
    void closeOverlay(String source) {
        overlaySources.remove(source);
        var current = reporter;
        if (current != null) {
            current.setBlocked(source, null);
        }
    }

    /** 会话复位：清空对话框栈与全部 overlay 来源。 */
    void reset() {
        dialogs.clear();
        for (String source : overlaySources) {
            closeOverlay(source);
        }
        overlaySources.clear();
        refresh();
    }

    private void refresh() {
        var current = reporter;
        if (current == null) {
            return;
        }
        BlockedStatus top = dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
        current.setBlocked(DIALOG_SOURCE, top);
    }

    private static @Nullable BlockedStatus convert(@Nullable ExtensionBlockedStatus value) {
        if (value == null) {
            return null;
        }
        Kind kind = Kind.fromWire(value.kind());
        return new BlockedStatus(kind != null ? kind : Kind.QUESTION, value.message());
    }
}
