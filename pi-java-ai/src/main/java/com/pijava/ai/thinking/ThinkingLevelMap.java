package com.pijava.ai.thinking;

import java.util.Map;
import java.util.Optional;

/**
 * 每模型的思考级别翻译表（pi {@code Model.thinkingLevelMap}，{@code types.ts:86}）：
 *
 * <pre>{@code Partial<Record<ModelThinkingLevel, string | null>>}</pre>
 *
 * <p>语义是<b>「把 pi 的级别名翻译成 provider 自己的 effort 名」</b> ＋ <b>「标出哪些级别不支持」</b>
 * —— 它<b>不是</b>「级别 → 请求配置」的映射（后者由车道按 {@code compat} 旗标决定，见
 * {@code anthropic-messages.ts:1152-1180}）。</p>
 *
 * <p><b>三态</b>（pi 的 {@code Partial<Record<K, string | null>>} 在 Java 上的方言）：</p>
 *
 * <table border="1">
 *   <caption>三态对照</caption>
 *   <tr><th>pi</th><th>Java（本记录）</th><th>含义</th></tr>
 *   <tr><td>键缺席（{@code undefined}）</td><td>{@link #hasEntry} 为假</td>
 *       <td>用 provider 默认；{@code off..high} 视为支持</td></tr>
 *   <tr><td>值为 {@code null}</td><td>{@code Optional.empty()} 值 ＋ 键在场</td>
 *       <td><b>显式不支持</b>（{@link #explicitlyUnsupported}）</td></tr>
 *   <tr><td>值为字符串</td><td>{@code Optional.of(s)}</td>
 *       <td>该级别的 provider effort 名</td></tr>
 * </table>
 *
 * <p>⚠️ {@code Map.copyOf} 不允许 {@code null} 值，所以 pi 的「值是 null」在 Java 上用
 * {@code Optional.empty()} 表达 —— 这正好也把「缺席」与「显式 null」分开。</p>
 *
 * <p><b>真实样本</b>（pi 生成器里的字面常量，{@code generate-models.ts}）：
 * {@code DEEPSEEK_V4 = {minimal:null, low:null, medium:null, high:"high", max:"max"}}；
 * {@code Gemma 4 = {off:null, minimal:"MINIMAL", low:null, medium:null, high:"HIGH"}}。</p>
 */
public record ThinkingLevelMap(
    Map<ModelThinkingLevel, Optional<String>> entries
) {
    /** Compact constructor that defensively copies the entries. */
    public ThinkingLevelMap {
        entries = Map.copyOf(entries);
    }

    /**
     * pi 的 {@code map[level]} —— <b>缺席与显式 null 都返回空</b>。
     *
     * <p>要区分二者用 {@link #hasEntry}（键在场）或 {@link #explicitlyUnsupported}。</p>
     */
    public Optional<String> mapped(ModelThinkingLevel level) {
        return entries.getOrDefault(level, Optional.empty());
    }

    /** pi 的 {@code level in map} —— {@code xhigh}/{@code max} 的 <b>opt-in</b> 判据。 */
    public boolean hasEntry(ModelThinkingLevel level) {
        return entries.containsKey(level);
    }

    /** pi 的 {@code map[level] === null} —— 显式标为不支持。 */
    public boolean explicitlyUnsupported(ModelThinkingLevel level) {
        return entries.containsKey(level) && entries.get(level).isEmpty();
    }

    /**
     * pi 的 {@code model.thinkingLevelMap?.off !== null}
     * （{@code anthropic-messages.ts:1179}）：决定 {@code Off} 时是否发显式的「关闭」参数。
     *
     * <p>⚠️ <b>键缺席也算「支持 off」</b> —— {@code undefined !== null} 为真。只有<b>显式</b>
     * {@code off: null} 才是不支持（例如 Kimi K2.7 Code：官方拒绝
     * {@code thinking:{type:"disabled"}}，只能省略该参数）。</p>
     */
    public boolean supportsExplicitOff() {
        return !explicitlyUnsupported(ModelThinkingLevel.off());
    }

    /** 空表：所有级别走 provider 默认（{@code off..high} 支持，{@code xhigh}/{@code max} 不支持）。 */
    public static ThinkingLevelMap empty() {
        return new ThinkingLevelMap(Map.of());
    }

    /** 从条目构造（键在场 ＋ 值为 {@code Optional.empty()} ≙ pi 的显式 {@code null}）。 */
    public static ThinkingLevelMap of(Map<ModelThinkingLevel, Optional<String>> entries) {
        return new ThinkingLevelMap(entries);
    }
}
