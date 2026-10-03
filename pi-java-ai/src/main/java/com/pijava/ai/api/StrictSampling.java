package com.pijava.ai.api;

/**
 * pi {@code resolveJsonSchemaStrictSampling}（{@code constrained-sampling.ts:208-228}）：
 * 判定单个工具在当前车道是否走 strict。
 *
 * <ul>
 *   <li>非 json_schema 配置 ⇒ {@code null}；</li>
 *   <li>支持且 schema 可转换 ⇒ {@link Boolean#TRUE}；</li>
 *   <li>prefer 但不可转换/不支持 ⇒ {@code null}（回落普通工具）；</li>
 *   <li>require 但不可转换/不支持 ⇒ {@link IllegalStateException}（pi 文案逐字）。</li>
 * </ul>
 */
public final class StrictSampling {

    private StrictSampling() {}

    /**
     * Resolve whether the tool goes strict on this lane.
     *
     * @param supportsStrictMode whether the lane/model supports strict tools
     */
    public static Boolean resolveStrict(ToolDefinition tool, boolean supportsStrictMode) {
        if (!(tool.constrainedSampling() instanceof JsonSchemaSampling(StrictMode strict))) {
            return null;
        }
        if (supportsStrictMode) {
            try {
                StrictJsonSchema.convert(tool.inputSchema());
                return Boolean.TRUE;
            } catch (UnsupportedStrictJsonSchemaException error) {
                if (strict != StrictMode.REQUIRE) {
                    return null;
                }
                throw new IllegalStateException(
                    "Tool \"" + tool.name() + "\" requires JSON-schema constrained sampling, but "
                    + error.getMessage() + ".");
            }
        }
        if (strict == StrictMode.REQUIRE) {
            throw new IllegalStateException(
                "Tool \"" + tool.name() + "\" requires JSON-schema constrained sampling, "
                + "but strict tools are unsupported.");
        }
        return null;
    }
}
