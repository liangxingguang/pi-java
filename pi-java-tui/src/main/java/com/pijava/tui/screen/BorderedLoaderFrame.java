package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.component.LoaderSpinner;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * L7 W1（docs/22 §4.5，C7/C8）：pi {@code BorderedLoader}（bordered-loader.ts）
 * 的共享渲染帧——上下 {@code ─} 框线（{@code DynamicBorder} 的
 * {@code "─" × max(1,width)}，border 色）＋ spinner 行 ＋ cancel hint。
 *
 * <p>消费点＝pi 两侧一致：{@code /bug} 的摘要/上传（{@link BugReportLoaderScreen}，
 * bug-report.ts:275 对位）与会话分享（{@code ShareFlow.Loader}，session-share.ts:114/
 * :167 对位）。abort/done 语义（pi {@code CancellableLoader} 的 Esc ⇒ abort ＋
 * {@code signal.aborted}）由各 overlay 自持——改造前已逐字对位（C8）。</p>
 *
 * <p>形状注记：pi 的框线是两条水平 {@code ─}（非盒式 Panel）；cancellable=false
 * 时无 hint 行（pi {@code options.cancellable} 门）。spinner 帧由 {@code startNanos}
 * 时间驱动（{@link LoaderSpinner}，docs/22 §4.1）。</p>
 */
final class BorderedLoaderFrame {

    private BorderedLoaderFrame() {}

    /**
     * 渲染一帧带框 loader。
     *
     * @param spinnerFrame 当前帧字符（空 ⇒ 无 spinner 符号）
     * @param message      文案（逐字，pi 两侧消费点均经 {@code BorderedLoader} 原样透传）
     * @param cancellable  是否带 {@code (esc to cancel)} hint 行（pi cancellable 门）
     * @param width        终端列数（{@code ─} 重复宽度）
     */
    static Element render(String spinnerFrame, String message, boolean cancellable, int width) {
        var children = new ArrayList<Element>();
        var border = "\u2500".repeat(Math.max(1, width));
        children.add(borderRow(border));
        var loaderSpans = new ArrayList<dev.tamboui.text.Span>();
        if (spinnerFrame != null && !spinnerFrame.isEmpty()) {
            loaderSpans.add(new dev.tamboui.text.Span(spinnerFrame + " ", SemanticColors.accent()));
        }
        loaderSpans.add(new dev.tamboui.text.Span(message, SemanticColors.muted()));
        children.add(TamboUIAdapter.richText(
            dev.tamboui.text.Text.from(dev.tamboui.text.Line.from(loaderSpans))));
        if (cancellable) {
            children.add(TamboUIAdapter.spacer(1));
            children.add(TamboUIAdapter.text("(esc to cancel)").dim());
        }
        children.add(TamboUIAdapter.spacer(1));
        children.add(borderRow(border));
        return TamboUIAdapter.column(children);
    }

    private static Element borderRow(String border) {
        return TamboUIAdapter.richText(dev.tamboui.text.Text.from(dev.tamboui.text.Line.from(
            List.of(new dev.tamboui.text.Span(border, SemanticColors.border())))));
    }
}
