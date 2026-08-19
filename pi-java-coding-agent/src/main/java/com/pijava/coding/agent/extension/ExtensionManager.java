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

    /** 加载所有已发现扩展，返回已加载扩展名。 */
    public Set<String> loadAll() {
        return loadAll(ExtensionManager.class.getClassLoader());
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

    private void load(PiExtension ext) {
        loaded.put(ext.name(), ext);
        ext.register(context);
    }
}
