package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 批 16（T4a，docs/27）：渲染器责任链注册表——pi runner.ts:789-795
 * {@code resolveToolRenderers} 逐字：resolver 按注册序（＝扩展加载序）成链，
 * 首个非 null 胜出；全链 null ⇒ {@code base}（调用方回落，如内置 ToolCallCard）。
 */
public final class ToolRendererRegistry {

    private final List<ToolRendererResolver> resolvers = new CopyOnWriteArrayList<>();

    /** 追加一个 resolver（pi loader.ts:367-371：push 进 per-extension 数组，追加序＝加载序）。 */
    public void register(ToolRendererResolver resolver) {
        resolvers.add(resolver);
    }

    /** 注册数（测试面）。 */
    public int size() {
        return resolvers.size();
    }

    /**
     * pi runner.ts:789-795 的责任链逐字（index 递归的循环形）：
     * {@code resolvers[i](toolName, () -> resolve(i + 1))}，链尾落 {@code base}。
     */
    public ToolCallRenderer resolve(String toolName, Supplier<ToolCallRenderer> base) {
        return resolveAt(0, toolName, base);
    }

    private ToolCallRenderer resolveAt(int index, String toolName,
                                       Supplier<ToolCallRenderer> base) {
        if (index >= resolvers.size()) {
            return base == null ? null : base.get();
        }
        return resolvers.get(index).resolve(toolName,
            () -> resolveAt(index + 1, toolName, base));
    }
}
