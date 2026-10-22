package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.Element;

import com.pijava.tui.util.TamboUIAdapter;

/**
 * 待处理消息区（B175，原 docs/26 §3.6；pi {@code pendingMessagesContainer}，
 * {@code interactive-mode.ts:4648-4660}）：{@code queue_update} 数据驱动，
 * 渲染在聊天区与编辑器之间。每条一行 dim 文本：{@code Steering: <x>} /
 * {@code Follow-up: <x>}。
 */
public final class PendingQueueView {

    private List<String> steering = List.of();
    private List<String> followUp = List.of();
    // pi getAppKeyDisplay("app.message.dequeue")（:4736）；默认键显示，
    // 装配处按平台表注入（Windows/WSL ⇒ "Alt+Q"）。
    private String dequeueHint = "Alt+Up";

    /** dequeue 键显示名（pi {@code getAppKeyDisplay} 对位；空值忽略）。 */
    public void dequeueHint(String hint) {
        if (hint != null && !hint.isEmpty()) {
            this.dequeueHint = hint;
        }
    }

    /** Replace with queue_update contents. */
    public void update(List<String> steering, List<String> followUp) {
        this.steering = List.copyOf(steering);
        this.followUp = List.copyOf(followUp);
    }

    /** Whether both pending lists are empty. */
    public boolean isEmpty() {
        return steering.isEmpty() && followUp.isEmpty();
    }

    /**
     * Render the pending rows, or {@code null} when both queues are empty.
     * Dim markup follows the ToolCallCard/SlashCompleter convention.
     */
    public Element render() {
        if (isEmpty()) {
            return null;
        }
        var rows = new ArrayList<Element>();
        for (var text : steering) {
            rows.add(TamboUIAdapter.markupText("[dim]Steering: " + text + "[/]"));
        }
        for (var text : followUp) {
            rows.add(TamboUIAdapter.markupText("[dim]Follow-up: " + text + "[/]"));
        }
        // docs/21（§3.7）：pi interactive-mode.ts:4736-4738——末行 dim 取回提示。
        rows.add(TamboUIAdapter.markupText(
            "[dim]↳ " + dequeueHint + " to edit all queued messages[/]"));
        return TamboUIAdapter.column(rows);
    }

    /** Current steering texts (test hook). */
    public List<String> steering() {
        return steering;
    }

    /** Current follow-up texts (test hook). */
    public List<String> followUp() {
        return followUp;
    }
}
