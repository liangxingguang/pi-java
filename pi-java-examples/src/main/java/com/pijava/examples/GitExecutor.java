package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionExec;

/**
 * docs/52 §0/§2.8：git 两件共享的命令执行接缝——生产默认 {@code ctx::exec}，
 * 测试注入 fake 记录 argv 并按脚本返回，避开真实进程与 PATH 依赖。
 */
@FunctionalInterface
interface GitExecutor {
    ExtensionExec.ExecResult exec(String command, List<String> args);
}
