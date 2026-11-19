package com.pijava.examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/bash-spawn-hook.ts:1-30} 移植（docs/54 §2.4）——
 * 演示 spawnHook 对 command/cwd/env 的变换。Java 的 BashTool 无 spawnHook 钩子（D240），
 * 降级为：注册同名 bash（形状对齐）＋变换纯函数单测（不接入执行链）。
 */
public final class BashSpawnHookExtension implements PiExtension {

    /** pi spawnHook 变换：注入 source、改 cwd、加 env 标记（纯函数）。 */
    record SpawnTransform(String command, String cwd, Map<String, String> env) {}

    @Override
    public String name() {
        return "bash-spawn-hook";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(BashTool.create(null, true, null)); // 同名覆盖，形状对齐
    }

    /** pi spawnHook：命令前置 source ~/.profile，cwd 用 hookDir，env 加 PI_SPAWN_HOOK。 */
    static SpawnTransform apply(String command, String cwd, String hookDir,
            Map<String, String> env) {
        var newCommand = "source ~/.profile\n" + command;
        var newCwd = hookDir == null || hookDir.isEmpty() ? cwd : hookDir;
        var newEnv = new LinkedHashMap<String, String>(env == null ? Map.of() : env);
        newEnv.put("PI_SPAWN_HOOK", "1");
        return new SpawnTransform(newCommand, newCwd, Map.copyOf(newEnv));
    }
}
