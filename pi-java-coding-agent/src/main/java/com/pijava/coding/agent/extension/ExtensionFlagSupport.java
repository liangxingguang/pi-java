package com.pijava.coding.agent.extension;

import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

/**
 * 批 16（T1e，docs/27 裁决⑩）：{@code --help} 的<b>轻量扩展装载</b>——pi 的
 * --help 在完整运行时（含扩展加载）建好之后打印（main.ts:877-884），扩展 flag
 * 列入 "Extension CLI Flags:" 段（args.ts:273-283）。
 *
 * <p>java 侧不建会话、不碰 session 文件、不设 user.home 重定向：裸 registries 的
 * {@link DefaultExtensionContext}（settings/models/repository 槽为 null——扩展
 * 读到即 NPE，由逐扩展隔离吞掉）。装载面＝SPI（TCCL）＋resolve() 装配枚举
 * （批 19 裁决④善后：旧 JAR 拷贝目录扫描退役）＋显式 {@code -e}（-ne 旁路
 * SPI/resolve 但不旁路显式，与 {@code AgentSession.loadExtensions} 同口径）。</p>
 */
public final class ExtensionFlagSupport {

    private ExtensionFlagSupport() {}

    /**
     * 收集所有已声明的扩展 flag（注册序）。任何扩展面故障 ⇒ 空表（--help 绝不崩）。
     */
    public static List<ExtensionFlag> collectForHelp(Args args) {
        try {
            var services = new SessionServices(null, null, ProviderRegistry.create(), null,
                new ToolRegistry(null), new CommandRegistry(), null,
                new PromptTemplateRegistry());
            var context = new DefaultExtensionContext(services, new SkillManager(),
                ExtensionUI.noop(), new ExtensionEventBus());
            var manager = new ExtensionManager(context);
            if (!args.noExtensions()) {
                manager.loadAllIsolated();
                loadResolvedIsolated(manager);
            }
            for (var explicit : args.extensions()) {
                manager.loadJarIsolated(Path.of(explicit).toAbsolutePath().normalize());
            }
            return context.allFlags();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 批 19：resolve() 装配枚举（pi --help 全运行时对位——含缺包直装语义）。
     *  失败（网络/信任门）只丢 resolve 臂，SPI 臂 flags 保留。 */
    private static void loadResolvedIsolated(ExtensionManager manager) {
        try {
            var cwd = Path.of(System.getProperty("user.dir"));
            var settings = com.pijava.coding.agent.core.SettingsManager.load(null);
            var pm = com.pijava.coding.agent.core.PackageManagers.create(cwd,
                com.pijava.coding.agent.core.FileSettingsStorage.defaultAgentDir(), settings);
            for (var resource : pm.resolve(null).extensions()) {
                if (resource.enabled()) {
                    manager.loadResolvedIsolated(resource.path());
                }
            }
        } catch (RuntimeException e) {
            // --help 绝不崩；resolve 臂故障静默（pi loader 同旨隔离）。
        }
    }
}
