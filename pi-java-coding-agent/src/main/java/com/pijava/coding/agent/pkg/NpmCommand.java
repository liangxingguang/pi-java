package com.pijava.coding.agent.pkg;

import java.util.List;

/**
 * pi {@code getNpmCommand}（package-manager.ts:1781-1791）——settings
 * {@code npmCommand} 键的解析与校验。argv 形：首项命令、余项参数。
 *
 * <p>HTTP 实现（{@code HttpNpmResolver}）下命令本体不被消费（窄差 D104），
 * 但校验面保留逐字——配置非法时与 pi 同点同文案报错。</p>
 *
 * @param command 命令本体
 * @param args    尾随参数
 */
public record NpmCommand(String command, List<String> args) {

    /** pi 默认：未配置/空数组 ⇒ {@code npm} 无参数。 */
    public static final NpmCommand DEFAULT = new NpmCommand("npm", List.of());

    /**
     * pi 逐字：未配置或空数组 ⇒ {@link #DEFAULT}；首项为空 ⇒ 抛
     * {@code Invalid npmCommand: first array entry must be a non-empty command}
     *（模型可见文案，逐字）。
     */
    public static NpmCommand resolve(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return DEFAULT;
        }
        var command = configured.get(0);
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid npmCommand: first array entry must be a non-empty command");
        }
        return new NpmCommand(command, List.copyOf(configured.subList(1, configured.size())));
    }
}
