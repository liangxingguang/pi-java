package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12 T3（docs/23 §3/§7）：pi {@code external-editor.ts:14-44} 对位——
 * 退出码 0 ⇒ Complete（BOM＋尾换行剥离）；非 0 ⇒ Failed 内容不动；
 * 读回规范化（stripBom＋去尾部换行）。
 */
class ExternalEditorSupportTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
        .toLowerCase(java.util.Locale.ROOT).contains("win");

    /** 不动文件、退出码 0 的「编辑器」（Windows cmd 内建／Unix true）。 */
    private static String noopEditor() {
        return WINDOWS ? "rem" : "true";
    }

    /** 不动文件、退出码非 0 的「编辑器」（不存在命令：cmd 9009／sh 127）。 */
    private static String failingEditor() {
        return "pi-java-nonexistent-editor-xyz";
    }

    @Test
    void exitZeroReadsBackNormalizedContent() throws Exception {
        var result = ExternalEditorSupport.edit(noopEditor(), "hello\r\n\r\n");

        // 不修改文件的编辑器 ⇒ 读回原内容（尾部换行被剥离，pi :38-41）。
        assertThat(result)
            .isEqualTo(new ExternalEditorSupport.Result.Complete("hello"));
    }

    @Test
    void nonZeroExitLeavesContentUntouched() throws Exception {
        var result = ExternalEditorSupport.edit(failingEditor(), "draft text");

        assertThat(result).isInstanceOf(ExternalEditorSupport.Result.Failed.class);
        assertThat(((ExternalEditorSupport.Result.Failed) result).exitCode()).isNotZero();
    }

    @Test
    void normalizeStripsBomAndTrailingNewlines() {
        assertThat(ExternalEditorSupport.normalize("﻿body\r\n")).isEqualTo("body");
        assertThat(ExternalEditorSupport.normalize("line\n\n\n")).isEqualTo("line");
        assertThat(ExternalEditorSupport.normalize("keep\ninternal\n\nend")).isEqualTo(
            "keep\ninternal\n\nend");
        // BOM 只剥开头一个；中段 \r\n 不动。
        assertThat(ExternalEditorSupport.normalize("a\r\nb﻿")).isEqualTo("a\r\nb﻿");
    }
}
