package com.pijava.coding.agent.core;

/**
 * 会话解析的干净结局（批 14，docs/25 T6c，裁决②）——pi 的
 * {@code console.error("Error: <msg>") + process.exit(1)} 与
 * {@code console.log(<msg>) + process.exit(0)} 对位：消息已按 pi 文案成形，
 * {@link #code()} 即进程退出码，{@link #errorPrefix()} 决定是否带
 * {@code "Error: "} 前缀（pi 的失败带前缀走 stderr，用户放弃
 * "Aborted."/"No session selected" 不带前缀走 stdout）。
 *
 * <p>{@code Main.run} 在最外层 catch 打印＋返回码——取代旧的
 * {@code IllegalStateException} 栈 trace 冒顶。</p>
 */
public final class SessionResolutionExit extends RuntimeException {

    private final int code;
    private final boolean errorPrefix;

    private SessionResolutionExit(int code, boolean errorPrefix, String message) {
        super(message, null, false, false);
        this.code = code;
        this.errorPrefix = errorPrefix;
    }

    /** pi {@code console.error("Error: <msg>") + exit 1} 对位。 */
    public static SessionResolutionExit error(String message) {
        return new SessionResolutionExit(1, true, message);
    }

    /** pi {@code console.log(<msg>) + exit 0} 对位（不带 {@code "Error: "} 前缀）。 */
    public static SessionResolutionExit plain(int code, String message) {
        return new SessionResolutionExit(code, false, message);
    }

    /** Process exit code. */
    public int code() {
        return code;
    }

    /** Whether the message prints with the {@code "Error: "} prefix on stderr. */
    public boolean errorPrefix() {
        return errorPrefix;
    }
}
