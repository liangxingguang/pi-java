package com.pijava.coding.agent.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Settings lifecycle: load, global/project deep merge, project trust,
 * modified-field tracking and flush (Phase 3 design §12.2).
 *
 * <p>Priority: CLI &gt; project &gt; global. {@link #effective()} recomputes
 * the deep merge on every call so per-field setters (via
 * {@link SettingsAccessors}) take effect immediately without a reload.</p>
 */
public final class SettingsManager {

    private final SettingsStorage storage;
    private Settings global = new Settings();
    private Settings project = new Settings();
    private boolean projectTrusted = true;
    private final Set<String> modifiedFields = new HashSet<>();
    private final SettingsAccessors accessors = new SettingsAccessors(this);
    private final SettingsNetworkAccessors network = new SettingsNetworkAccessors(this);
    private final SettingsInstallAccessors install = new SettingsInstallAccessors(this);
    private final SettingsUiPrefsAccessors uiPrefs = new SettingsUiPrefsAccessors(this);

    private SettingsManager(SettingsStorage storage) {
        this.storage = storage;
    }

    /**
     * Load settings from the real filesystem.
     *
     * @param projectTrustOverride {@code --approve}/{@code --no-approve} value
     *                             or null (falls back to the configured default)
     */
    public static SettingsManager load(Boolean projectTrustOverride) {
        return load(null, null, projectTrustOverride);
    }

    /**
     * Load settings from a specific agent and project directory — for callers that act on a
     * directory other than the process's own, such as the {@code mcp} subcommand.
     *
     * @param agentDir the global agent directory, or {@code null} for the default
     * @param projectDir the project directory, or {@code null} for the working directory
     * @param projectTrustOverride {@code --approve}/{@code --no-approve} value
     *                             or null (falls back to the configured default)
     * @return the loaded manager
     */
    public static SettingsManager load(java.nio.file.Path agentDir, java.nio.file.Path projectDir,
                                       Boolean projectTrustOverride) {
        var storage = agentDir == null || projectDir == null
            ? new FileSettingsStorage()
            : new FileSettingsStorage(agentDir, projectDir);
        var manager = new SettingsManager(storage);
        manager.reload();
        // 批 11 T3（pi main.ts:596/874 启动期应用对位）：读全局域；测试道
        // withStorage 不应用（不改 JVM 全局态）。
        HttpProxySupport.apply(manager.global().httpProxy);
        manager.setProjectTrusted(
            resolveTrust(projectTrustOverride, manager.merged().defaultProjectTrust));
        return manager;
    }

    /** Create a manager backed by the given storage (tests/in-memory). */
    public static SettingsManager withStorage(SettingsStorage storage) {
        var manager = new SettingsManager(storage);
        manager.reload();
        return manager;
    }

    /** Re-read both scopes from storage and rebuild the merged view. */
    public void reload() {
        global = storage.readGlobal();
        project = storage.readProject();
        migrate(global);
        migrate(project);
        modifiedFields.clear();
    }

    /** Deep-merged effective settings (project overrides global). */
    public Settings effective() {
        return merge(global, project);
    }

    /**
     * The raw global scope (mutated by {@link SettingsAccessors}).
     * 批 15 转 public：pi {@code settingsManager.getGlobalSettings()} 是公共面
     * （bug 上报包的 settings 节操作数）。
     */
    public Settings global() {
        return global;
    }

    /** The raw project scope（pi {@code getProjectSettings()} 对位，批 15 转 public）。 */
    public Settings project() {
        return project;
    }

    /** Per-field accessors (design split to stay under 500 lines/file). */
    public SettingsAccessors accessors() {
        return accessors;
    }

    /** 网络面访问器（批 11 裁决⑧拆分：httpIdleTimeoutMs/websocketConnectTimeoutMs/cacheWarming）。 */
    public SettingsNetworkAccessors network() {
        return network;
    }

    /** 安装/分析面访问器（批 11 裁决⑧拆分：deviceId/changelog/analytics 族）。 */
    public SettingsInstallAccessors install() {
        return install;
    }

    /** UI 偏好面访问器（批 11 裁决⑧拆分：showCacheMissNotices/warnings/fullscreen 族）。 */
    public SettingsUiPrefsAccessors uiPrefs() {
        return uiPrefs;
    }

    /** Record a field as modified so {@link #flush()} persists it. */
    void markModified(String field) {
        modifiedFields.add(field);
    }

    /** Whether the current project scope is trusted. */
    public boolean isProjectTrusted() {
        return projectTrusted;
    }

    /**
     * Change project trust. When untrusted, the project scope is cleared
     * <b>in memory only</b> so only global settings apply (pi
     * {@code setProjectTrusted}，settings-manager.ts:473-496——
     * {@code projectSettings = {}} 不落盘；信任恢复时从存储重读 project 域）。
     *
     * <p>批 19 修真：旧实现 {@code storage.writeProject(new Settings())} 会把
     * 项目 settings 文件整本覆写为空——pi 无此行为（纯内存清空），未信任项目里
     * 跑 {@code list}/{@code update} 会数据丢失。</p>
     */
    public void setProjectTrusted(boolean trusted) {
        if (this.projectTrusted == trusted) {
            return;
        }
        this.projectTrusted = trusted;
        if (!trusted) {
            project = new Settings();
        } else {
            project = storage.readProject();
            migrate(project);
        }
    }

    /** Persist modified global fields. */
    public void flush() {
        if (!modifiedFields.isEmpty()) {
            storage.writeGlobal(global);
            modifiedFields.clear();
        }
    }

    // ── 批 19（docs/30 T2）写回面：字段级 merge 落盘 ─────────────
    //
    // pi 的 set* 写回是「改内存 → markModified → save() 立即落盘」，落盘经
    // persistScopedSettings（settings-manager.ts:596-625）做字段级 merge——
    // 文件锁内读当前文件、只覆盖 modified 字段、保留未知键与其余字段（含
    // 并发进程写进来的新键）。下列 setter 对位此语义：改内存＋经
    // {@link SettingsStorage#withLock} 立即写回单字段，<b>不</b>经
    // {@link #flush()} 的整本写路径。

    /** pi {@code setPackages}（:1080-1085）：写全局 {@code packages} 并立即落盘。 */
    public void setPackages(List<PackageSource> packages) {
        global.packages = packages == null ? null : List.copyOf(packages);
        persistField(SettingsScope.GLOBAL, "packages", global.packages);
    }

    /** pi {@code setProjectPackages}（:1086-1090，经 {@code updateProjectSettings}
     *  的信任门 :553-556）。 */
    public void setProjectPackages(List<PackageSource> packages) {
        assertProjectTrustedForWrite();
        project.packages = packages == null ? null : List.copyOf(packages);
        persistField(SettingsScope.PROJECT, "packages", project.packages);
    }

    /** pi {@code setExtensionPaths}（:1096-1100）。 */
    public void setExtensions(List<String> extensions) {
        global.extensions = copyOrNull(extensions);
        persistField(SettingsScope.GLOBAL, "extensions", global.extensions);
    }

    /** pi {@code setProjectExtensionPaths}（:1102-1106）。 */
    public void setProjectExtensions(List<String> extensions) {
        assertProjectTrustedForWrite();
        project.extensions = copyOrNull(extensions);
        persistField(SettingsScope.PROJECT, "extensions", project.extensions);
    }

    /** pi {@code setSkillPaths}（:1112-1116）。 */
    public void setSkills(List<String> skills) {
        global.skills = copyOrNull(skills);
        persistField(SettingsScope.GLOBAL, "skills", global.skills);
    }

    /** pi {@code setProjectSkillPaths}（:1118-1122）。 */
    public void setProjectSkills(List<String> skills) {
        assertProjectTrustedForWrite();
        project.skills = copyOrNull(skills);
        persistField(SettingsScope.PROJECT, "skills", project.skills);
    }

    /** pi {@code setPromptTemplatePaths}（:1128-1132）。 */
    public void setPrompts(List<String> prompts) {
        global.prompts = copyOrNull(prompts);
        persistField(SettingsScope.GLOBAL, "prompts", global.prompts);
    }

    /** pi {@code setProjectPromptTemplatePaths}（:1134-1138）。 */
    public void setProjectPrompts(List<String> prompts) {
        assertProjectTrustedForWrite();
        project.prompts = copyOrNull(prompts);
        persistField(SettingsScope.PROJECT, "prompts", project.prompts);
    }

    /** pi {@code setThemePaths}（:1144-1148）。 */
    public void setThemes(List<String> themes) {
        global.themes = copyOrNull(themes);
        persistField(SettingsScope.GLOBAL, "themes", global.themes);
    }

    /** pi {@code setProjectThemePaths}（:1150-1154）。 */
    public void setProjectThemes(List<String> themes) {
        assertProjectTrustedForWrite();
        project.themes = copyOrNull(themes);
        persistField(SettingsScope.PROJECT, "themes", project.themes);
    }

    /** pi {@code assertProjectTrustedForWrite}（:553-556）——模型可见文案逐字。 */
    private void assertProjectTrustedForWrite() {
        if (!projectTrusted) {
            throw new IllegalStateException(
                "Project is not trusted; refusing to write project settings");
        }
    }

    private static List<String> copyOrNull(List<String> values) {
        return values == null ? null : List.copyOf(values);
    }

    /**
     * pi {@code persistScopedSettings}（:596-625）对位：锁内读当前文件 → 只替换
     * 目标键 → 写回（2 空格缩进 ≙ {@code JSON.stringify(merged, null, 2)}）。
     * {@code value == null} ⇒ 删键（pi 写 {@code undefined} 被 stringify 丢弃同结局）。
     */
    private void persistField(SettingsScope scope, String field, Object value) {
        storage.withLock(scope, current -> mergeFieldJson(current, field, value));
    }

    /** 字段级 merge 的纯函数核（测试直调）。 */
    static String mergeFieldJson(String current, String field, Object value) {
        try {
            var tree = current != null && !current.isBlank()
                ? Json.mapper().readTree(stripBom(current)) : null;
            var object = tree instanceof ObjectNode node
                ? node : Json.mapper().createObjectNode();
            if (value == null) {
                object.remove(field);
            } else {
                object.set(field, Json.mapper().valueToTree(value));
            }
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (java.io.IOException e) {
            throw new SettingsStorageException("Cannot merge settings field: " + field, e);
        }
    }

    /** U+FEFF（写成常量避免源码内嵌不可见 BOM 字面量）。 */
    private static final String BOM = "﻿";

    private static String stripBom(String content) {
        // pi stripBom（utils/text.ts）对位。
        return content.startsWith(BOM) ? content.substring(1) : content;
    }

    /** The merged view used by {@link #load} for trust resolution. */
    private Settings merged() {
        return merge(global, project);
    }

    private static boolean resolveTrust(Boolean override, String defaultTrust) {
        if (override != null) {
            return override;
        }
        return !"never".equals(defaultTrust);
    }

    /**
     * Migrate legacy settings fields (Phase 3 design §12.3): {@code queueMode}
     * → {@code steeringMode}, {@code websockets} → {@code transport}. The old
     * keys are consumed (removed from the unknown passthrough) once migrated.
     */
    private static void migrate(Settings settings) {
        if (settings.steeringMode == null) {
            var legacy = settings.unknown().get("queueMode");
            if (legacy instanceof String value) {
                settings.steeringMode = value;
                settings.removeUnknown("queueMode");
            }
        }
        if (settings.transport == null) {
            var legacy = settings.unknown().get("websockets");
            if (legacy instanceof String value) {
                settings.transport = value;
                settings.removeUnknown("websockets");
            }
        }
    }

    private Settings merge(Settings base, Settings override) {
        var baseNode = (ObjectNode) Json.mapper().valueToTree(base);
        var overrideNode = (ObjectNode) Json.mapper().valueToTree(override);
        deepMerge(baseNode, overrideNode);
        var merged = Json.mapper().convertValue(baseNode, Settings.class);
        // pi mergeDefaultTools（settings-manager.ts:124-129）经 deepMergeSettings 特判（:139-145）：
        // JSON 深合并里数组整体替换；pi 对 defaultTools 另立语义——覆盖层全是 +name/-name
        // 修饰符时追加到继承选择之后（逐层修饰），否则整体替换。
        merged.defaultTools = ToolSelection.mergeDefaultTools(base.defaultTools, override.defaultTools);
        return merged;
    }

    private static void deepMerge(ObjectNode base, ObjectNode override) {
        var properties = override.properties();
        for (var entry : properties) {
            var value = entry.getValue();
            // A null in the overriding scope means "not set" — fall through
            // to the base (global) value instead of clobbering it.
            if (value.isNull()) {
                continue;
            }
            var current = base.get(entry.getKey());
            if (current instanceof ObjectNode currentObject && value instanceof ObjectNode valueObject) {
                deepMerge(currentObject, valueObject);
            } else {
                base.set(entry.getKey(), value);
            }
        }
    }
}
