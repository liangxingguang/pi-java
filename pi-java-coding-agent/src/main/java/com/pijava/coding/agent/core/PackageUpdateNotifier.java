package com.pijava.coding.agent.core;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.coding.agent.pkg.PackageEnvironment;
import com.pijava.coding.agent.pkg.PackageUpdate;

/**
 * pi 启动包更新通知（批 19 T7，docs/30 §3）——{@code checkForPackageUpdates}
 * （interactive-mode.ts:1263-1279）＋{@code showPackageUpdateNotification}
 * （:4667-4684）对位。
 *
 * <p>fire-and-forget 虚拟线程；{@code PI_OFFLINE}/异常/空表 ⇒ 静默（pi 吞错
 * 对位）。通知块的 warning 色边框无 java 对位 ⇒ dim 系统文本（窄差 D83 同族，
 * 启动崩溃提示先例）。</p>
 */
public final class PackageUpdateNotifier {

    private PackageUpdateNotifier() {}

    /**
     * pi {@code checkForPackageUpdates} 的 fire-and-forget 调度：离线直返；异常
     * 吞掉；非空 ⇒ {@code onUpdates}（调用方负责编排到 UI 线程）。
     */
    public static void checkAsync(Supplier<List<PackageUpdate>> check,
            Consumer<List<PackageUpdate>> onUpdates) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            final List<PackageUpdate> updates;
            try {
                updates = check.get();
            } catch (RuntimeException e) {
                return;   // pi :1273-1278 catch ⇒ []
            }
            if (updates == null || updates.isEmpty()) {
                return;
            }
            onUpdates.accept(updates);
        });
    }

    /** pi {@code showPackageUpdateNotification} 文案逐字（{@code APP_NAME} 既定
     *  翻译 {@code pi}→{@code pi-java}；色/边框裁——见类注）。 */
    public static String notificationText(List<PackageUpdate> updates) {
        var lines = new StringBuilder()
            .append("Package Updates Available\n")
            .append("Package updates are available. Run pi-java update --extensions\n")
            .append("Packages:");
        for (var update : updates) {
            lines.append("\n- ").append(update.displayName());
        }
        return lines.toString();
    }
}
