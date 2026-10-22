package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.ChatMessage;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Line;

/**
 * 批 12 T10（docs/23 §3）：fullscreen 退出时的 transcript 倾倒——pi
 * {@code stopInteractiveTui}（interactive-mode.ts:881-887）的
 * {@code "transcript"} 臂对位：alt 屏内容随退出丢失前，把对话滚动回退
 * 内联重绘进主缓冲。
 *
 * <p>复用 {@link ScrollbackTranscript} 的 stdout 打印面（regular 内联模式
 * 同款管线）：entries ⇒ {@link ChatMessage#from} ⇒ flatten 行 ⇒
 * MarkupParser＋样式 ⇒ ANSI 打印，不新造渲染管线。</p>
 */
public final class TranscriptDump {

    private TranscriptDump() {}

    /**
     * 把会话全量条目按终端宽打印到 stdout（调用点已退出 alt 屏）。
     *
     * @param session   会话（{@code accumulatedEntries()} 全量条目）
     * @param width     终端列数（换行宽）
     * @param outputPad 水平留白（{@code uiPrefs().getOutputPad()}，与聊天区同格）
     */
    public static void print(AgentSession session, int width, int outputPad) {
        print(session, width, outputPad, System.out);
    }

    /** 输出流注入形态（launcher 退出臂与测试面）。 */
    public static void print(AgentSession session, int width, int outputPad,
                             java.io.PrintStream out) {
        int safeWidth = Math.max(1, width);
        var messages = new ArrayList<ChatMessage>();
        for (var entry : session.accumulatedEntries()) {
            var message = ChatMessage.from(entry, outputPad);
            if (message != null) {
                messages.add(message);
            }
        }
        var transcript = new ScrollbackTranscript(new ScrollbackTranscript.Sink() {
            @Override
            public void println(Line line) {
                out.println(toAnsi(line, safeWidth));
            }

            @Override
            public boolean replaceLastBlock(int lineCount, List<Line> block) {
                return false;   // 倾倒不重写（draft 不入——sync 传 null）
            }
        });
        transcript.sync(messages, null, safeWidth);
    }

    /** 单行 Line ⇒ ANSI 串（{@code InlineTuiShell.toAnsi} 同款 buffer 手法）。 */
    private static String toAnsi(Line line, int width) {
        var one = Buffer.empty(new Rect(0, 0, width, 1));
        one.setLine(0, 0, line);
        return one.toAnsiStringTrimmed();
    }
}
