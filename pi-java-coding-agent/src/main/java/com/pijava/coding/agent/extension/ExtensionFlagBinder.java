package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pijava.coding.agent.cli.Args;

/**
 * 批 16（T1c，docs/27）：pi {@code applyExtensionFlagValues}
 * （agent-session-services.ts:82-128）对位——扩展加载完后把命令行的未知
 * {@code --flag} 绑定进共享 flag 值表。
 *
 * <p>{@code Args.unmatched} 是扁平 token 列表（{@code --k=v} 整串；裸 {@code --k}
 * 的值已被 ArgsParser 吞成下一个元素），这里先按 pi args.ts:247-271 的三态
 * 重新配对成 {@code Map<String, Object>}：</p>
 * <ul>
 *   <li>{@code --k=v} ⇒ {@code (k, v)}（等号后字符串）；</li>
 *   <li>裸 {@code --k} 跟值 token（不以 {@code -}/{@code @} 起头）⇒ {@code (k, v)}；</li>
 *   <li>否则 ⇒ {@code (k, true)}。</li>
 * </ul>
 *
 * <p>绑定规则逐字：BOOLEAN 型<b>恒写 {@code true}</b>（解析期吞下的字符串值被忽略）；
 * STRING 型收到 {@code true}（无值）⇒ 诊断
 * {@code Extension flag "--<name>" requires a value}；未注册名汇总成一条
 * {@code Unknown options: --a, --b}（单数不加 s）。</p>
 */
public final class ExtensionFlagBinder {

    private ExtensionFlagBinder() {}

    /**
     * 配对＋绑定。
     *
     * @param unmatchedTokens {@code Args.unmatched()}（扁平 token 列表）
     * @param context         扩展上下文（持注册声明与共享值表）；{@code null} ⇒
     *                        无注册面（--no-extensions 早退臂），所有 flag 报未知
     * @return 诊断列表（pi {@code AgentSessionRuntimeDiagnostic} 对位；type 恒 "error"）
     */
    public static List<Args.ArgDiagnostic> apply(List<String> unmatchedTokens,
                                                 DefaultExtensionContext context) {
        var parsed = pair(unmatchedTokens);
        var diagnostics = new ArrayList<Args.ArgDiagnostic>();
        var unknown = new ArrayList<String>();
        for (var entry : parsed.entrySet()) {
            var name = entry.getKey();
            var flag = context == null ? null : context.findFlag(name);
            if (flag == null) {
                unknown.add(name);
                continue;
            }
            if (flag.type() == ExtensionFlag.Type.BOOLEAN) {
                context.flagValues().put(name, Boolean.TRUE);
                continue;
            }
            if (entry.getValue() instanceof String value) {
                context.flagValues().put(name, value);
                continue;
            }
            diagnostics.add(new Args.ArgDiagnostic("error",
                "Extension flag \"--" + name + "\" requires a value"));
        }
        if (!unknown.isEmpty()) {
            var label = unknown.size() == 1 ? "Unknown option: " : "Unknown options: ";
            diagnostics.add(new Args.ArgDiagnostic("error",
                label + unknown.stream().map(n -> "--" + n)
                    .collect(Collectors.joining(", "))));
        }
        return diagnostics;
    }

    /** pi args.ts:247-271 三态配对（后到值覆盖先到，Map 语义同 pi）。 */
    static Map<String, Object> pair(List<String> unmatchedTokens) {
        var parsed = new LinkedHashMap<String, Object>();
        for (int i = 0; i < unmatchedTokens.size(); i++) {
            var token = unmatchedTokens.get(i);
            if (!token.startsWith("--")) {
                continue; // 防御：ArgsParser 只把 -- 旗与值放进 unmatched
            }
            var body = token.substring(2);
            var eqIndex = body.indexOf('=');
            if (eqIndex != -1) {
                parsed.put(body.substring(0, eqIndex), body.substring(eqIndex + 1));
                continue;
            }
            if (i + 1 < unmatchedTokens.size()
                    && !unmatchedTokens.get(i + 1).startsWith("-")
                    && !unmatchedTokens.get(i + 1).startsWith("@")) {
                parsed.put(body, unmatchedTokens.get(++i));
            } else {
                parsed.put(body, Boolean.TRUE);
            }
        }
        return parsed;
    }
}
