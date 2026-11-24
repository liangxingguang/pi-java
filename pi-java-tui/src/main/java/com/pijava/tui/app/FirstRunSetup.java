package com.pijava.tui.app;

import java.nio.file.Files;
import java.util.Map;

import com.pijava.coding.agent.core.FileSettingsStorage;

/**
 * 首次设置向导触发条件（L7 W7，S18）：pi
 * {@code cli/startup-ui.ts:132-148 shouldRunFirstTimeSetup} 的 java 对位。
 *
 * <p>四条同时成立才运行：官方发行版（java 无 fork 重命名面 ⇒ 恒 true）、
 * {@code PI_EXPERIMENTAL=1}、未覆盖 agent 目录（{@code PI_JAVA_CODING_AGENT_DIR}
 * 或评测属性）、全局 {@code settings.json} 尚不存在。</p>
 */
public final class FirstRunSetup {

    private FirstRunSetup() {}

    /** 生产判定（读进程环境与真实 settings.json）。 */
    public static boolean shouldRun() {
        boolean override = System.getProperty(FileSettingsStorage.AGENT_DIR_PROPERTY) != null;
        boolean settingsExists = Files.exists(FileSettingsStorage.defaultAgentDir()
            .resolve("settings.json"));
        return shouldRun(System.getenv(), settingsExists, !override);
    }

    /**
     * 纯函数判定（测试面）。
     *
     * @param env              环境变量
     * @param settingsExists   全局 settings.json 是否存在
     * @param officialDistribution 是否官方发行版
     * @return 是否应弹首次设置向导
     */
    static boolean shouldRun(Map<String, String> env, boolean settingsExists,
                             boolean officialDistribution) {
        return officialDistribution
            && "1".equals(env.get("PI_EXPERIMENTAL"))
            && !env.containsKey("PI_JAVA_CODING_AGENT_DIR")
            && !settingsExists;
    }
}
