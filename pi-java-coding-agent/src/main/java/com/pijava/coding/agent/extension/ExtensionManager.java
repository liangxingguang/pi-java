package com.pijava.coding.agent.extension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 扩展管理器 —— 从 classpath ServiceLoader 发现扩展并装配进 {@link ExtensionContext}。
 *
 * <p>{@link #loadJar(Path)} 用独立 {@link URLClassLoader} 从外部 JAR 加载（父加载器
 * 为应用 classpath，扩展引用 PiExtension/ExtensionContext 走父委托）。</p>
 */
public final class ExtensionManager {

    private final ExtensionContext context;
    private final Map<String, PiExtension> loaded = new LinkedHashMap<>();

    /** @param context 扩展注册目标上下文 */
    public ExtensionManager(ExtensionContext context) {
        this.context = context;
    }

    /** 从 classpath ServiceLoader 发现 PiExtension。 */
    public List<PiExtension> discover() {
        var found = new ArrayList<PiExtension>();
        for (var ext : ServiceLoader.load(PiExtension.class)) {
            found.add(ext);
        }
        return found;
    }

    /**
     * 加载所有已发现扩展，返回已加载扩展名。
     *
     * <p>用线程上下文 classloader（ServiceLoader 标准惯例；CLI 主线程的 TCCL 即应用
     * loader，生产行为不变）——docs/15 起测试可经 TCCL 注入作用域 loader，让
     * {@code AgentSession.create} 的装配期扩展发现不污染全局 classpath。</p>
     */
    public Set<String> loadAll() {
        var tccl = Thread.currentThread().getContextClassLoader();
        return loadAll(tccl != null ? tccl : ExtensionManager.class.getClassLoader());
    }

    /** 用指定 classloader 发现并加载（测试用作用域 loader，避免污染全局 classpath）。 */
    Set<String> loadAll(ClassLoader loader) {
        for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
            load(ext);
        }
        return Set.copyOf(loaded.keySet());
    }

    /** 从外部 JAR 加载扩展（URLClassLoader），返回已加载扩展名。 */
    public Set<String> loadJar(Path jar) {
        var names = new LinkedHashSet<String>();
        try (var loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
                load(ext);
                names.add(ext.name());
            }
            return Set.copyOf(names);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 卸载指定扩展（从已加载表移除；扩展无 close 生命周期）。 */
    public void unload(String name) {
        loaded.remove(name);
    }

    /** 已加载扩展名。 */
    public Set<String> loadedNames() {
        return Set.copyOf(loaded.keySet());
    }

    /**
     * 收集所有已加载扩展的会话开始资源贡献（合并保序）。
     *
     * <p>单个扩展的 {@code sessionStartResources()} 抛异常被隔离（记录告警），
     * 该扩展贡献视为空——扩展错误永不阻断会话装配。</p>
     */
    public ResourcePaths sessionStartResources() {
        var merged = ResourcePaths.none();
        for (var ext : loaded.values()) {
            try {
                var paths = ext.sessionStartResources();
                if (paths != null) {
                    merged = merged.plus(paths);
                }
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(ExtensionManager.class.getName())
                    .warning("Extension '" + ext.name()
                        + "' sessionStartResources() threw: " + e);
            }
        }
        return merged;
    }

    private void load(PiExtension ext) {
        loaded.put(ext.name(), ext);
        // docs/14：per-extension 上下文 —— 扩展的 on() 订阅带扩展名归属
        // （pi 的 extension.handlers 按扩展分桶 + 错误按 extensionPath 归属）。
        ext.register(context.forExtension(ext.name()));
    }
}
