package com.pijava.coding.agent.extension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** L5 W3：{@link ExtensionExec} 测试的跨平台子进程命令构造。 */
final class ExecTestSupport {

    private ExecTestSupport() {}

    /** 当前测试 JVM 的 java 可执行文件。 */
    static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** {@code java -cp <test-classes> ExecProbeMain <probeArgs>} 的参数表。 */
    static List<String> probeArgs(String... probeArgs) {
        var args = new ArrayList<String>();
        args.add("-cp");
        args.add(testClassesPath());
        args.add(ExecProbeMain.class.getName());
        args.addAll(List.of(probeArgs));
        return args;
    }

    private static String testClassesPath() {
        try {
            return Path.of(ExecProbeMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
