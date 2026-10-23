package com.pijava.coding.agent.spi;

import java.util.Optional;

import com.pijava.coding.agent.core.SettingsManager;

/**
 * {@code -r/--resume} session-selector SPI (batch 14, docs/25 T11，裁决①).
 *
 * <p>pi 的 {@code selectSession}（cli/session-picker.ts:15-55）对所有 mode 生效
 * （print/rpc 敲 {@code -r} 也弹选择器）；java 的会话解析在 coding-agent 深处，
 * 而 TUI 模块依赖 coding-agent（反向依赖不可）——故选择器经本 SPI 由 pi-java-tui
 * 注册（{@code META-INF/services/com.pijava.coding.agent.spi.SessionSelectorProvider}），
 * print/json/rpc/interactive 四模式自动同获。</p>
 *
 * <p>SPI 缺席（裁 tui 的精简部署）时解析层报干净错误（窄差 D70）。</p>
 */
public interface SessionSelectorProvider {

    /**
     * pi {@code selectSession} 对位：全屏跑会话选择器。
     *
     * @param currentScope     项目内会话加载器（pi {@code SessionManager.list(cwd, ...)}）
     * @param allScope         跨项目会话加载器（pi {@code SessionManager.listAll(...)}）
     * @param settings         设置（主题/键位来源）
     * @param useThemeOverride {@code --use-theme} 覆盖（{@code null} ≙ pi 未给）
     * @return 选中会话的文件 path；用户取消 ⇒ {@code empty}
     */
    Optional<String> selectSession(SessionListLoader currentScope, SessionListLoader allScope,
                                   SettingsManager settings, String useThemeOverride);
}
