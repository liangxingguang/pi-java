package com.pijava.agent.tool;

/**
 * Where a tool is exposed (pi {@code ToolExposure},
 * {@code core/extensions/types.ts:516} 逐字五档；批 30/T1 从 coding-agent
 * {@code extension/ToolExposure} 迁入——pi 把它放在 {@code ToolDefinition} 本体上，
 * java 的 {@link AgentTool} 是两型合并，故运行面统一形随 {@code AgentTool} 居此）。
 *
 * <ul>
 *   <li>{@code direct}：激活即声明给模型；注册时默认激活</li>
 *   <li>{@code modelOnly}：可被声明（激活），但永不可被其他工具经
 *       {@code ctx.executeTool()} 调用（{@code tool_search} 自身即此档）</li>
 *   <li>{@code codemode}：可被 codemode 脚本调用，不声明</li>
 *   <li>{@code deferred}：不声明，直到 {@code tool_search} 加载；可被工具间调用</li>
 *   <li>{@code hidden}：注册了但不可达</li>
 * </ul>
 */
public enum ToolExposure {

    /** pi {@code "direct"}。 */
    DIRECT("direct"),

    /** pi {@code "model-only"}。 */
    MODEL_ONLY("model-only"),

    /** pi {@code "codemode"}。 */
    CODEMODE("codemode"),

    /** pi {@code "deferred"}。 */
    DEFERRED("deferred"),

    /** pi {@code "hidden"}。 */
    HIDDEN("hidden");

    private final String wire;

    ToolExposure(String wire) {
        this.wire = wire;
    }

    /** The literal pi writes. */
    public String wire() {
        return wire;
    }
}
