package com.pijava.coding.agent.extension;

import java.nio.charset.StandardCharsets;

/**
 * L5 W3 测试探针：由 {@link ExtensionExec} 以子进程方式启动，产生确定字节输出。
 *
 * <p>参数：{@code ascii <n>} / {@code both <stdoutN> <stderrN>} /
 * {@code utf8-slow <repeat>}（逐字节写出＋flush＋1ms 间隔，保证读取端跨块）。</p>
 */
public final class ExecProbeMain {

    private ExecProbeMain() {}

    /** 子进程入口。 */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "ascii" -> write(System.out, Integer.parseInt(args[1]));
            case "both" -> {
                write(System.out, Integer.parseInt(args[1]));
                write(System.err, Integer.parseInt(args[2]));
            }
            case "utf8-slow" -> {
                String text = "汉字🙂";
                int repeat = Integer.parseInt(args[1]);
                for (int i = 0; i < repeat; i++) {
                    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                        System.out.write(b);
                        System.out.flush();
                        Thread.sleep(1);
                    }
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void write(java.io.PrintStream out, int count) {
        for (int i = 0; i < count; i++) {
            out.write('x');
        }
        out.flush();
    }
}
