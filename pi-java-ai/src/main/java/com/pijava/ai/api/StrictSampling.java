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
     * Resolve whether the tool goes strict on this lane（通用子集，无 provider 专有谓词）。
     *
     * @param supportsStrictMode whether the lane/model supports strict tools
     */
    public static Boolean resolveStrict(ToolDefinition tool, boolean supportsStrictMode) {
        return resolveStrict(tool, supportsStrictMode, null);
    }

    /**
     * Resolve whether the tool goes strict on this lane.
     *
     * <p>对位 pi {@code resolveJsonSchemaStrictSampling(tool, supports, isUnsupportedKeyword)}：
     * 探测遍带 provider 专有谓词（Anthropic strict 拒绝的 minimum/format 等键），prefer 命中
     * ⇒ 回落 {@code null}，require 命中 ⇒ 抛错。其他车道传 {@code null}。</p>
     *
     * @param supportsStrictMode whether the lane/model supports strict tools
     * @param unsupportedKeyword provider 专有键值拒绝谓词，{@code null} 表示只做通用检查
     */
    public static Boolean resolveStrict(ToolDefinition tool, boolean supportsStrictMode,
                                        UnsupportedStrictKeyword unsupportedKeyword) {
        if (!(tool.constrainedSampling() instanceof JsonSchemaSampling(StrictMode strict))) {
            return null;
        }
        if (supportsStrictMode) {
            try {
                StrictJsonSchema.convert(tool.inputSchema(), unsupportedKeyword);
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
