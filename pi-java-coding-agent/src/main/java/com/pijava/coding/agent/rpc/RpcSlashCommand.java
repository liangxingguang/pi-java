package com.pijava.coding.agent.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import com.pijava.coding.agent.core.source.SourceInfo;

/**
 * get_commands 载荷 —— 单个「可经 prompt 调用」命令的元数据（B247 对齐 pi
 * {@code rpc-types.ts:77-89} {@code RpcSlashCommand}：内置不在其中，形状无
 * {@code argumentHint}）。{@code description} 缺席即省略（pi {@code description?}）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcSlashCommand(
    String name,
    String description,
    Source source,
    SourceInfo sourceInfo
) {
    /** pi {@code source: "extension" | "prompt" | "skill"}（rpc-types.ts:87）。 */
    public enum Source {
        /** 扩展注册命令（java 暂无此臂——扩展命令与内置同池无溯源，窄差 D5/B221）。 */
        EXTENSION("extension"),
        /** prompt template。 */
        PROMPT("prompt"),
        /** 技能（{@code skill:<name>}）。 */
        SKILL("skill");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        /** 线格式字面量。 */
        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
