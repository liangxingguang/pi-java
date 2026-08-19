package com.pijava.coding.agent.rpc;

/**
 * get_commands 载荷 —— 单个斜杠命令的元数据（对齐 pi {@code RpcSlashCommand}）。
 */
public record RpcSlashCommand(
    String name,
    String description,
    String argumentHint
) {}
