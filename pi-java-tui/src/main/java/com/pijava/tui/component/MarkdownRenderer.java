package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Lightweight Markdown → widget conversion (Phase 3 design §5.1).
 *
 * <p>Phase 3 supports headings, bold/italic markup, fenced code blocks,
 * bullet lists and quotes with a hand-rolled line parser; tables, images,
 * LaTeX and mermaid arrive Phase 6.</p>
 */
public final class MarkdownRenderer {

    /** Render markdown text into a widget column. */
    public Element render(String markdown) {
        return TamboUIAdapter.column(parseBlocks(markdown).stream()
            .map(this::renderBlock)
            .toList());
    }

    private Element renderBlock(Block block) {
        return switch (block) {
            case Block.Heading(int level, String text) ->
                TamboUIAdapter.text(text).bold();
            case Block.Paragraph(String text) ->
                TamboUIAdapter.markupText(text);
            case Block.Code(String lang, String code) ->
                TamboUIAdapter.panel(TamboUIAdapter.text(code)).gray().rounded();
            case Block.ListBlock(List<String> items) ->
                TamboUIAdapter.column(items.stream()
                    .map(item -> TamboUIAdapter.text("\u2022 " + item))
                    .toList());
            case Block.Quote(String text) ->
                TamboUIAdapter.text(text).dim();
        };
    }

    /** Parse markdown into a block list (line-based, Phase 3 subset). */
    static List<Block> parseBlocks(String markdown) {
        var blocks = new ArrayList<Block>();
        var lines = markdown.split("\r?\n", -1);
        var index = 0;
        while (index < lines.length) {
            var line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            if (line.startsWith("```")) {
                var lang = line.substring(3).trim();
                var code = new StringBuilder();
                index++;
                while (index < lines.length && !lines[index].startsWith("```")) {
                    code.append(lines[index]).append('\n');
                    index++;
                }
                index++; // closing fence
                blocks.add(new Block.Code(lang, code.toString().stripTrailing()));
                continue;
            }
            if (line.startsWith("### ")) {
                blocks.add(new Block.Heading(3, line.substring(4)));
            } else if (line.startsWith("## ")) {
                blocks.add(new Block.Heading(2, line.substring(3)));
            } else if (line.startsWith("# ")) {
                blocks.add(new Block.Heading(1, line.substring(2)));
            } else if (line.startsWith("> ")) {
                blocks.add(new Block.Quote(line.substring(2)));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                var items = new ArrayList<String>();
                while (index < lines.length
                        && (lines[index].startsWith("- ")
                            || lines[index].startsWith("* "))) {
                    items.add(lines[index].substring(2));
                    index++;
                }
                blocks.add(new Block.ListBlock(items));
                continue;
            } else {
                blocks.add(new Block.Paragraph(line));
            }
            index++;
        }
        return blocks;
    }

    /** Parsed markdown block (internal). */
    sealed interface Block {
        record Heading(int level, String text) implements Block {}
        record Paragraph(String text) implements Block {}
        record Code(String language, String code) implements Block {}
        record ListBlock(List<String> items) implements Block {}
        record Quote(String text) implements Block {}
    }
}
