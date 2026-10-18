package com.pijava.coding.agent.core.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * B247：资源来源元数据（pi {@code core/source-info.ts:6-12} {@code SourceInfo}）。
 *
 * <p>RPC {@code get_commands} 条目与补全目录来源标签携带；{@code baseDir} 缺席即省略
 * （pi {@code baseDir?}）。放叶子包 {@code core.source}（裁决⑦）：{@code prompt} 包被
 * {@code core.AgentSession} 引用，本类型若放 {@code core} 则 prompt→core 成包环。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceInfo(
    String path,
    String source,
    Scope scope,
    Origin origin,
    String baseDir
) {
    /** pi {@code SourceScope}（source-info.ts:2）。 */
    public enum Scope {
        /** 全局（agentDir）域。 */
        USER("user"),
        /** 项目（cwd）域。 */
        PROJECT("project"),
        /** 显式路径等临时来源（pi scope 缺省值）。 */
        TEMPORARY("temporary");

        private final String wire;

        Scope(String wire) {
            this.wire = wire;
        }

        /** 线格式字面量。 */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** pi {@code SourceOrigin}（source-info.ts:3）；java 无包源加载，现恒 {@link #TOP_LEVEL}。 */
    public enum Origin {
        /** 包内资源（包域未做，仅列全闭集）。 */
        PACKAGE("package"),
        /** 顶层资源（java 现唯一产出）。 */
        TOP_LEVEL("top-level");

        private final String wire;

        Origin(String wire) {
            this.wire = wire;
        }

        /** 线格式字面量。 */
        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
