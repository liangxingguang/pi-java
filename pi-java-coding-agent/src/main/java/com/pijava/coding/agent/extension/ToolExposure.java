package com.pijava.coding.agent.extension;

/**
 * Where a tool is exposed (pi {@code ToolExposure},
 * {@code core/extensions/types.ts:501-516} 逐字五档；批 27/B162 裁决④落地——
 * 批 24 的 {@code McpToolExposure} 是配置面适配，本枚举是运行面统一形）。
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
