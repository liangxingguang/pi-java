package com.pijava.coding.agent.core.slash;

import com.pijava.coding.agent.core.source.SourceInfo;

/**
 * 批 23（docs/34 T4）：pi {@code SlashCommandInfo}（types.ts 的 getCommands 元素，
 * agent-session.ts:3361-3381 对位）——扩展 {@code ctx.getCommands()} 的返回元素。
 *
 * @param name       调用名（扩展命令是撞名编号后的 {@code invocationName}；
 *                   技能带 {@code skill:} 前缀）
 * @param description 一行描述（可空）
 * @param source     来源字面量：{@code "extension"|"prompt"|"skill"}
 * @param sourceInfo 来源章（可空＝旧无溯源路）
 */
public record SlashCommandInfo(String name, String description, String source,
                               SourceInfo sourceInfo) {}
