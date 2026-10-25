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
     * 批 18（T6）：内置扩展名表（ServiceLoader 发现名，不注册）——pi main 层
     * {@code builtinExtensions}（createExtensions 中 isBuiltinExtension 的名表，
     * resource-loader.ts:382-387）对位，供包管理器 resolve 的内置臂使用。
     */
    public static List<String> builtinExtensionNames() {
        var tccl = Thread.currentThread().getContextClassLoader();
        var loader = tccl != null ? tccl : ExtensionManager.class.getClassLoader();
        var names = new ArrayList<String>();
        for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
            names.add(ext.name());
        }
        return names;
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

    /**
     * 批 16（T1e，裁决⑩）：{@code --help} 轻量装载——逐扩展 try/catch 隔离
     * （扩展 {@code register()} 抛错不该让 {@code --help} 崩；pi loader 同旨的
     * 异常隔离）。
     */
    public Set<String> loadAllIsolated() {
        var tccl = Thread.currentThread().getContextClassLoader();
        var loader = tccl != null ? tccl : ExtensionManager.class.getClassLoader();
        for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
            loadIsolated(ext);
        }
        return Set.copyOf(loaded.keySet());
    }

    /**
     * 批 19（T4 善后）：resolve 输出的隔离加载入口（{@code --help} 轻量道）——
     * {@code builtin:} 前缀跳过；目录经 {@code resolveExtensionEntries} 取入口
     * JAR 逐个隔离加载；其余按 JAR 隔离加载。
     */
    public Set<String> loadResolvedIsolated(String path) {
        if (path.startsWith(com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX)) {
            return Set.of();
        }
        var p = Path.of(path);
        if (java.nio.file.Files.isDirectory(p)) {
            var entries = com.pijava.coding.agent.pkg.PackageResourceScanner
                .resolveExtensionEntries(p);
            if (entries == null) {
                return Set.of();
            }
            var loaded = new LinkedHashSet<String>();
            for (var entry : entries) {
                loaded.addAll(loadJarIsolated(Path.of(entry)));
            }
            return Set.copyOf(loaded);
        }
        return loadJarIsolated(p);
    }

    /** {@link #loadJar(Path)} 的逐扩展隔离变体（{@code --help} 轻量装载用）。 */
    public Set<String> loadJarIsolated(Path jar) {
        var names = new LinkedHashSet<String>();
        try (var loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
                loadIsolated(ext, jar.toString());
                names.add(ext.name());
            }
            return Set.copyOf(names);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 从外部 JAR 加载扩展（URLClassLoader），返回已加载扩展名。 */
    public Set<String> loadJar(Path jar) {
        var names = new LinkedHashSet<String>();
        try (var loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
                load(ext, jar.toString());
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
        load(ext, com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX + ext.name());
    }

    /**
     * 批 23（docs/34 T1，裁决⑥）：带路径身份的加载——{@code extensionPath} 是
     * pi {@code extension.path} 对位：ServiceLoader 发现 ⇒ 合成 {@code builtin:<name>}
     * （resource-loader.ts:110 同约定）；{@link #loadJar} ⇒ JAR 路径串。
     * MCP 注册的所有权门与命令 sourceInfo 盖章都靠它。
     */
    private void load(PiExtension ext, String extensionPath) {
        loaded.put(ext.name(), ext);
        // docs/14：per-extension 上下文 —— 扩展的 on() 订阅带扩展名归属
        // （pi 的 extension.handlers 按扩展分桶 + 错误按 extensionPath 归属）。
        ext.register(context.forExtension(ext.name(), extensionPath));
    }

    /** 逐扩展异常隔离的 {@link #load}（批 16 T1e：--help 装载不被单个扩展搞崩）。 */
    private void loadIsolated(PiExtension ext) {
        loadIsolated(ext,
            com.pijava.coding.agent.pkg.PackageManager.BUILTIN_PATH_PREFIX + ext.name());
    }

    /** 逐扩展异常隔离的 {@link #load(PiExtension, String)}。 */
    private void loadIsolated(PiExtension ext, String extensionPath) {
        try {
            load(ext, extensionPath);
        } catch (Throwable e) {
            java.util.logging.Logger.getLogger(ExtensionManager.class.getName())
                .warning("Extension '" + ext.name() + "' register() threw: " + e);
        }
    }
}
