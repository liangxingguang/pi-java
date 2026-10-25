package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.skill.SkillManager;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionManager;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.SessionEventFirer;

/**
 * 批 23（docs/34 T3）：启动信任解析的装配胶合——pi main.ts:719-777 对位。
 *
 * <p>流程（逐字序）：</p>
 * <ol>
 *   <li>{@code shouldResolve}＝无 override ∧ 无 cwd 缓存 ∧ 有信任敏感资源
 *       （pi main.ts:742-744）；</li>
 *   <li>否则走 else 臂（pi :745-749）：缓存 ?? override ?? （无资源∥已存=true）；</li>
 *   <li>shouldResolve ⇒ <b>先不信任</b>，做预信任扩展装配
 *       （pi resource-loader.ts:505-511 {@code loadProjectTrustExtensions} 对位：
 *       不信任态 resolve ⇒ 项目臂被信任门排掉，只装 user/global＋CLI 显式源），
 *       发 {@code project_trust} 事件，走 {@link ProjectTrustResolver} 七级链；</li>
 *   <li>定夺按 cwd 缓存（pi {@code projectTrustByCwd}——会话内 /new 不重问）＋
 *       {@code setProjectTrusted} 回填。</li>
 * </ol>
 *
 * <p>扩展装载跑两遍（预信任一遍、正式装配一遍）——pi 同为两遍（裁决③）。
 * 预信任道逐扩展异常隔离（{@code loadAllIsolated} 族——扩展 register 抛错
 * 不阻断启动，pi loader 的 errors 收集同旨）。</p>
 */
public final class ProjectTrustSetup {

    /** pi {@code projectTrustByCwd}（main.ts:741 按 cwd 的进程级缓存）。 */
    private static final Map<String, Boolean> TRUST_BY_CWD = new ConcurrentHashMap<>();

    private ProjectTrustSetup() {}

    /** 测试钩：清进程级缓存（夹具间隔离）。 */
    public static void clearCacheForTests() {
        TRUST_BY_CWD.clear();
    }

    /**
     * 在 {@code SettingsManager.load} 之后、装配消费信任之前调用。
     *
     * <p>cwd 取 settings 存储面的项目目录（被门的资源＝该目录的 {@code .pi-java}
     * 内容，判定与消费同根；pi 单源 process.cwd()）。内存存储（注入 settings
     * 的夹具缝）回落 {@code user.dir}。</p>
     *
     * @param args     CLI 参数（override/扩展开关/显式源）
     * @param settings 已加载的 settings（本函数负责最终 {@code setProjectTrusted}）
     * @param prompter 信任弹窗缝（null ⇒ 无 UI 臂）
     * @return 启动告警（扩展错误等，pi {@code projectTrustDiagnostics} 对位）
     */
    public static List<String> apply(Args args, SettingsManager settings,
                                     ProjectTrustPrompter prompter) {
        var warnings = new ArrayList<String>();
        Path cwd;
        Path agentDir;
        if (settings.storage() instanceof FileSettingsStorage files) {
            agentDir = files.globalPath().getParent();
            cwd = files.projectPath().getParent().getParent();
        } else {
            agentDir = FileSettingsStorage.defaultAgentDir();
            cwd = Path.of(System.getProperty("user.dir"));
        }
        var store = new ProjectTrustStore(agentDir);
        var cacheKey = ProjectTrustStore.normalizeCwd(cwd);
        var cached = TRUST_BY_CWD.get(cacheKey);
        Boolean override = args.projectTrustOverride();
        boolean hasResources = ProjectTrustResources.hasTrustRequiringProjectResources(cwd);

        // pi main.ts:742-749：shouldResolve 判定＋else 臂初值。
        boolean shouldResolve = override == null && cached == null && hasResources;
        if (!shouldResolve) {
            boolean trusted = cached != null ? cached
                : override != null ? override
                : (!hasResources || Boolean.TRUE.equals(store.get(cwd)));
            settings.setProjectTrusted(trusted);
            return warnings;
        }

        // pi main.ts:746-748：需要解析 ⇒ 先以不信任装配。
        settings.setProjectTrusted(false);

        // 预信任扩展装配（pi loadProjectTrustExtensions 对位）。
        var firer = preTrustFirer(args, settings);
        boolean trusted = ProjectTrustResolver.resolve(cwd, null, store,
            settings.global().defaultProjectTrust, firer, prompter != null, prompter,
            warnings::add);
        TRUST_BY_CWD.put(cacheKey, trusted);
        settings.setProjectTrusted(trusted);
        return warnings;
    }

    /**
     * 预信任扩展集的事件发射器；无扩展可装（-ne 且无显式源）⇒ {@code null}
     * （跳过七级链的第 ③ 级）。
     */
    private static SessionEventFirer preTrustFirer(Args args, SettingsManager settings) {
        // 批 14 早退臂同判（docs/25 T15）：-ne 且无显式路径 ⇒ 无扩展面。
        if (args.noExtensions() && args.extensions().isEmpty()) {
            return null;
        }
        var bus = new ExtensionEventBus();
        var context = new DefaultExtensionContext(null, new SkillManager(),
            ExtensionUI.noop(), bus);
        var manager = new ExtensionManager(context);
        // 不信任态 resolve：项目域被信任门排掉（批 18 信任门），user/global＋
        // CLI 显式源入集——pi loadCurrentExtensionSet({includeInlineFactories}) 对位。
        var assembly = SessionSetup.resolveAll(args, settings);
        if (!args.noExtensions()) {
            manager.loadAllIsolated();
            for (var path : SessionSetup.mergePaths(
                    SessionSetup.enabledPaths(assembly.cli().extensions()),
                    SessionSetup.enabledPaths(assembly.resolved().extensions()))) {
                manager.loadResolvedIsolated(path);
            }
        } else {
            // -ne：pi noExtensions ⇒ cliEnabledExtensions 仍载（resource-loader.ts:577）。
            for (var path : SessionSetup.enabledPaths(assembly.cli().extensions())) {
                manager.loadResolvedIsolated(path);
            }
        }
        return new SessionEventFirer(bus, context);
    }
}
