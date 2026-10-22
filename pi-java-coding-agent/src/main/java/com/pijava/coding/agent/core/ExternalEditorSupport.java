package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 批 12 T3（docs/23 §3）：Ctrl+G 外部编辑器往返，pi
 * {@code utils/external-editor.ts:14-44}（{@code editInExternalEditor}）对位。
 *
 * <p>要点逐字：临时目录 {@code tmpdir/pi-editor-<random>/prompt.md}；
 * {@code command.split(" ")} 简单拆 argv（<b>不支持引号转义</b>——带空格路径
 * 照常断裂，pi 同）；stdio inherit（Windows {@code shell:true} ⇒
 * {@code cmd /c} 整串）；退出码 0 ⇒ 读回（stripBom＋去尾部换行），非 0 ⇒
 * 编辑器内容不动。</p>
 */
public final class ExternalEditorSupport {

    private ExternalEditorSupport() {}

    /** pi {@code editInExternalEditor} 结果：complete 带读回内容；其余编辑器不动。 */
    public sealed interface Result {
        /** 退出码 0：读回（已规范化）的内容。 */
        record Complete(String content) implements Result {}
        /** 非 0 退出：内容不动。 */
        record Failed(int exitCode) implements Result {}
    }

    /**
     * 把 {@code content} 写进临时文件、spawn 外部编辑器、阻塞等退出。
     *
     * @param command 编辑器命令行（{@code SettingsAccessors#getExternalEditorCommand()}）
     * @param content 初始内容
     * @return 完成（含读回内容）或失败（退出码）
     * @throws IOException 临时文件或进程启动失败
     * @throws InterruptedException 等待编辑器退出被中断
     */
    public static Result edit(String command, String content)
            throws IOException, InterruptedException {
        // pi：tmpdir/pi-editor-*/prompt.md。
        var dir = Files.createTempDirectory("pi-editor-");
        var file = dir.resolve("prompt.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            var pb = isWindows()
                // pi Windows shell:true ⇒ cmd /c 整串。
                ? new ProcessBuilder("cmd", "/c", command + " \"" + file + "\"")
                // pi：command.split(" ") 简单拆（不支持引号转义）——逐字对位。
                : new ProcessBuilder(append(command.split(" "), file.toString()));
            pb.inheritIO();
            int code = pb.start().waitFor();
            if (code != 0) {
                return new Result.Failed(code);
            }
            return new Result.Complete(normalize(Files.readString(file, StandardCharsets.UTF_8)));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    /** pi 读回规范化：stripBom＋去尾部换行（CR/LF 混合串）。 */
    static String normalize(String text) {
        var stripped = text.startsWith("﻿") ? text.substring(1) : text;
        return stripped.replaceAll("[\\r\\n]+$", "");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String[] append(String[] argv, String last) {
        var out = new String[argv.length + 1];
        System.arraycopy(argv, 0, out, 0, argv.length);
        out[argv.length] = last;
        return out;
    }
}
