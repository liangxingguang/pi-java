package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pijava.agent.context.ContextUsage;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Row;

/**
 * Bottom status bar: session name + tokens + context usage + model, driven by
 * the agent-core {@link SessionSnapshot} (Phase 3 design §4.4).
 *
 * <p>B293：context 段逐字对位 pi footer（{@code footer.ts:160-212}）——
 * {@code percent%/window (auto)}；percent 为 {@code null}（压缩点后尚无可信用量）
 * 显示 {@code ?}；usage 整体缺席按 pi 的 {@code undefined} 分支显示 {@code 0.0%/0}；
 * {@code >70} 用 warning、{@code >90} 用 error 配色。</p>
 */
public final class StatusBar {

    /** Render the status bar for a snapshot. */
    public Row render(SessionSnapshot snapshot) {
        List<Element> columns = new ArrayList<>();
        columns.add(TamboUIAdapter.text(" " + snapshot.name()).dim());
        columns.add(TamboUIAdapter.spacerFill());
        columns.add(TamboUIAdapter.text("\u26A1 " + snapshot.totalTokens() + " tokens").dim());
        columns.add(TamboUIAdapter.text(" | ").dim());
        columns.add(contextElement(snapshot));
        columns.add(TamboUIAdapter.text(" | ").dim());
        columns.add(TamboUIAdapter.text(snapshot.model()).dim());
        return TamboUIAdapter.row(columns.toArray(Element[]::new))
            .length(1).addClass("StatusBar");
    }

    private static Element contextElement(SessionSnapshot snapshot) {
        String text = contextText(snapshot);
        Style style = contextStyle(snapshot);
        return style == null
            ? TamboUIAdapter.text(text)
            : TamboUIAdapter.richText(Text.from(Line.from(List.of(new Span(text, style)))));
    }

    /** pi footer 的 context 文本（{@code footer.ts:193-212}）。 */
    static String contextText(SessionSnapshot snapshot) {
        ContextUsage usage = snapshot.contextUsage();
        int window = usage == null ? 0 : usage.contextWindow();
        Double percent = usage == null ? null : usage.percent();
        String percentPart;
        if (usage == null) {
            // pi：`contextUsage?.percent !== null` 对 undefined 也为真 ⇒ 走 0 值分支。
            percentPart = "0.0%";
        } else if (percent == null) {
            // pi 的 `?/272k` 分支不带百分号。
            percentPart = "?";
        } else {
            percentPart = String.format(Locale.ROOT, "%.1f%%", percent);
        }
        return percentPart + "/" + formatTokens(window)
            + (snapshot.autoCompact() ? " (auto)" : "");
    }

    /** pi footer：{@code >90} error、{@code >70} warning（{@code footer.ts:196-201}）。 */
    private static Style contextStyle(SessionSnapshot snapshot) {
        Double percent = snapshot.contextUsage() == null
            ? null
            : snapshot.contextUsage().percent();
        double value = percent == null ? 0 : percent;
        if (value > 90) {
            return SemanticColors.error();
        }
        if (value > 70) {
            return SemanticColors.warning();
        }
        return null;
    }

    /** pi {@code formatTokens}（{@code footer.ts:25-31}）逐字移植。 */
    static String formatTokens(double count) {
        if (count < 1000) {
            return Long.toString((long) count);
        }
        if (count < 10_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1000);
        }
        if (count < 1_000_000) {
            return Math.round(count / 1000) + "k";
        }
        if (count < 10_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000);
        }
        return Math.round(count / 1_000_000) + "M";
    }
}
