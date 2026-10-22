package com.pijava.coding.agent.core;

/**
 * UI 偏好面设置访问器（批 11 裁决⑧的 {@link SettingsAccessors} 拆分）：
 * {@code showCacheMissNotices}/{@code warnings}/{@code showHardwareCursor}/
 * {@code fullscreen}×4。消费点（TUI）随「settings 消费点批」落地，本批只到
 * 声明面。
 */
public final class SettingsUiPrefsAccessors {

    private final SettingsManager manager;

    SettingsUiPrefsAccessors(SettingsManager manager) {
        this.manager = manager;
    }

    /** pi {@code getShowCacheMissNotices}（settings-manager.ts:942-944）：{@code ?? false}。 */
    public boolean getShowCacheMissNotices() {
        return Boolean.TRUE.equals(manager.effective().showCacheMissNotices);
    }

    /** pi {@code setShowCacheMissNotices}（:964-967）：写全局＋markModified。 */
    public void setShowCacheMissNotices(boolean show) {
        manager.global().showCacheMissNotices = show;
        manager.markModified("showCacheMissNotices");
    }

    /**
     * pi {@code getWarnings}（:1419-1421）：浅拷贝 {@code {...(warnings ?? {})}}——
     * java record 不可变即拷贝语义；缺席 ⇒ 全 null 的空形。
     */
    public Settings.Warnings getWarnings() {
        var warnings = manager.effective().warnings;
        return warnings == null ? new Settings.Warnings(null) : warnings;
    }

    /** pi {@code setWarnings}（:1423-1428）：写全局＋markModified。 */
    public void setWarnings(Settings.Warnings warnings) {
        manager.global().warnings = warnings;
        manager.markModified("warnings");
    }

    /** pi {@code getShowHardwareCursor}（:1363-1365）：
     *  {@code ?? process.env.PI_HARDWARE_CURSOR === "1"}。 */
    public boolean getShowHardwareCursor() {
        var value = manager.effective().showHardwareCursor;
        return value != null ? value : "1".equals(System.getenv("PI_HARDWARE_CURSOR"));
    }

    /** pi {@code setShowHardwareCursor}（:1367-1371）。 */
    public void setShowHardwareCursor(boolean show) {
        manager.global().showHardwareCursor = show;
        manager.markModified("showHardwareCursor");
    }

    /** pi {@code getFullscreenExitOutput}（:1251-1253）：非法值吞掉回落
     *  {@code "transcript"}。 */
    public String getFullscreenExitOutput() {
        var value = manager.effective().fullscreenExitOutput;
        return "resume-hint".equals(value) ? "resume-hint" : "transcript";
    }

    /** pi {@code setFullscreenExitOutput}（:1255-1260）。 */
    public void setFullscreenExitOutput(String mode) {
        manager.global().fullscreenExitOutput = mode;
        manager.markModified("fullscreenExitOutput");
    }

    /** pi {@code getFullscreenScrollbar}（:1263-1265）：{@code "always"/"hidden"}
     *  否则回落 {@code "auto"}。 */
    public String getFullscreenScrollbar() {
        var value = manager.effective().fullscreenScrollbar;
        return "always".equals(value) || "hidden".equals(value) ? value : "auto";
    }

    /** pi {@code setFullscreenScrollbar}（:1267-1272）。 */
    public void setFullscreenScrollbar(String mode) {
        manager.global().fullscreenScrollbar = mode;
        manager.markModified("fullscreenScrollbar");
    }

    /** pi {@code getFullscreenCopyOnSelect}（:1274-1276）：{@code ?? true}。 */
    public boolean getFullscreenCopyOnSelect() {
        var value = manager.effective().fullscreenCopyOnSelect;
        return value == null || value;
    }

    /** pi {@code setFullscreenCopyOnSelect}（:1278-1282）。 */
    public void setFullscreenCopyOnSelect(boolean copy) {
        manager.global().fullscreenCopyOnSelect = copy;
        manager.markModified("fullscreenCopyOnSelect");
    }

    /**
     * pi {@code getFullscreenWheelScrollLines}（:1284-1289）：number 且 finite ⇒
     * clamp 1–100 后 floor；否则 {@code "auto"}。返回类型 {@code Object}
     * （{@code String "auto"} 或 {@code Integer}）。
     */
    public Object getFullscreenWheelScrollLines() {
        var value = manager.effective().fullscreenWheelScrollLines;
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
            return clampWheelLines(n.doubleValue());
        }
        return "auto";
    }

    /** pi {@code setFullscreenWheelScrollLines}（:1291-1296）：同 clamp。 */
    public void setFullscreenWheelScrollLines(Object value) {
        manager.global().fullscreenWheelScrollLines = value instanceof Number n
            ? clampWheelLines(n.doubleValue()) : "auto";
        manager.markModified("fullscreenWheelScrollLines");
    }

    private static int clampWheelLines(double value) {
        return (int) Math.min(100, Math.max(1, Math.floor(value)));
    }
}
