package com.pijava.coding.agent.extension;

/**
 * pi {@code ExtensionError}（runner.ts 的 {@code emitError} 载荷）——
 * {@code extensionPath} 在 java 是**扩展名**（java 无扩展文件路径概念，
 * JAR/ServiceLoader 装配只有 {@link com.pijava.coding.agent.extension.PiExtension#name()}）。
 *
 * @param extensionName 出错的扩展名
 * @param event         事件线名（pi {@code ExtensionEvent["type"]}）
 * @param message       错误文本（pi {@code error} —— RPC 线上只带这个）
 * @param stack         栈轨迹（pi {@code stack}；不上 RPC 线，可空）
 */
public record ExtensionError(String extensionName, String event, String message, String stack) {

    /** 从异常构造（pi runner.ts 的 {@code err instanceof Error ? err.message : String(err)} 同口径）。 */
    public static ExtensionError of(String extensionName, String event, Throwable t) {
        var message = t.getMessage() != null ? t.getMessage() : t.toString();
        var trace = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(trace));
        return new ExtensionError(extensionName, event, message, trace.toString());
    }
}
