package com.pijava.tui.component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.tamboui.style.Style;

/**
 * Markdown 顶层块缓存（R31；pi {@code Markdown} 组件 markdown.ts:299-330/:393-421
 * 的 java 对位）。
 *
 * <p>三层语义：①词法缓存——归一化源（tab→3 空格）→ 块表，跨主题/宽度保留
 * （≙ pi {@code cachedTokens}，java 以帧界裁剪代替 WeakRef）；②块缓存——块
 * record → 逻辑行，命中要求主题与默认样式相等（≙ pi {@code blocks} 的
 * {@code width/nextTokenType} 复用条件＋{@code invalidate} 清
 * {@code defaultStylePrefix}）；③帧界——{@link #beginFrame()}/{@link #endFrame()}
 * 只保留本帧用到的源与块（≙ pi 每 render 重建 {@code blocks}，流式旧快照自然
 * 丢弃）。</p>
 *
 * <p>键用 {@link MarkdownBlocks.Block} record 的值相等（pi 用 {@code token.raw}）：
 * java 行级 parser 无跨块引用链接定义，同一块内容渲染必然相同。本类非线程安全，
 * 由渲染线程独占。</p>
 */
public final class MarkdownRenderCache {

    private record Entry(List<LogicalLine> lines, MarkdownTheme theme, Style style) {}

    private final Map<String, List<MarkdownBlocks.Spaced>> parsed = new HashMap<>();
    private final Map<MarkdownBlocks.Block, Entry> rendered = new HashMap<>();
    private final Set<String> usedSources = new HashSet<>();
    private final Set<MarkdownBlocks.Block> usedBlocks = new HashSet<>();
    private int renderedBlocks;
    private int hits;

    /**
     * Markdown → 逻辑行（命中复用已渲染块）。
     *
     * @param markdown     源文本（{@code null}/空白 ⇒ 空表）
     * @param theme        当前 Markdown 主题
     * @param defaultStyle pi 的 {@code defaultTextStyle} 对位
     * @return 逻辑行；注释-only 源 ⇒ 单个空行（pi {@code return [""]} 对位）
     */
    public List<LogicalLine> render(String markdown, MarkdownTheme theme, Style defaultStyle) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        var normalized = markdown.replace("\t", "   ");
        var blocks = parsed.get(normalized);
        if (blocks == null) {
            blocks = MarkdownBlocks.parse(normalized);
            parsed.put(normalized, blocks);
        }
        usedSources.add(normalized);
        if (blocks.isEmpty()) {
            return List.of(MarkdownRenderer.blank());
        }
        return MarkdownRenderer.assemble(blocks, theme, defaultStyle,
            block -> linesFor(block, theme, defaultStyle));
    }

    private List<LogicalLine> linesFor(MarkdownBlocks.Block block, MarkdownTheme theme,
                                       Style defaultStyle) {
        usedBlocks.add(block);
        var entry = rendered.get(block);
        if (entry != null && entry.theme().equals(theme) && entry.style().equals(defaultStyle)) {
            hits++;
            return entry.lines();
        }
        var lines = MarkdownRenderer.emitBlock(block, theme, defaultStyle);
        rendered.put(block, new Entry(lines, theme, defaultStyle));
        renderedBlocks++;
        return lines;
    }

    /** 开启一帧：清「本帧使用」标记（{@link #endFrame()} 据此裁剪）。 */
    public void beginFrame() {
        usedSources.clear();
        usedBlocks.clear();
    }

    /** 结束一帧：丢弃本帧未用到的源与块（pi 每 render 重建 blocks 的对位）。 */
    public void endFrame() {
        parsed.keySet().retainAll(usedSources);
        rendered.keySet().retainAll(usedBlocks);
        usedSources.clear();
        usedBlocks.clear();
    }

    /** 全清（主题/设置全量失效路径；{@code MessageBubble} 的 {@code invalidate} 对位）。 */
    public void invalidate() {
        parsed.clear();
        rendered.clear();
        usedSources.clear();
        usedBlocks.clear();
    }

    /** 观察点：实际执行块渲染的累计次数。 */
    public int renderedBlockCount() {
        return renderedBlocks;
    }

    /** 观察点：块缓存命中的累计次数。 */
    public int cacheHitCount() {
        return hits;
    }

    /** 观察点：当前保留的块条目数（帧尾裁剪后）。 */
    public int cachedBlockCount() {
        return rendered.size();
    }

    /** 观察点：当前保留的词法源数（帧尾裁剪后）。 */
    public int cachedSourceCount() {
        return parsed.size();
    }
}
