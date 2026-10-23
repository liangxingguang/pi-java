package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * pi 的工具选择语义（B236，docs/11）：
 * {@code DEFAULT_TOOL_NAMES}/{@code isToolModifier}/{@code getToolListError}/
 * {@code applyToolModifiers}/{@code resolveDefaultTools}/{@code mergeDefaultTools}
 * （core/settings-manager.ts:83-137）＋ {@code sdk.ts:280-294} 的装配解析 ＋
 * {@code agent-session.ts:1534-1543} 的注册面门。{@code null ≙ pi undefined}。
 */
public final class ToolSelection {

    /** pi {@code DEFAULT_TOOL_NAMES}（settings-manager.ts:83，逐字顺序）。 */
    public static final List<String> DEFAULT_TOOL_NAMES = List.of("read", "bash", "edit", "write");

    private ToolSelection() {}

    /** pi {@code isToolModifier}（:86-89）。 */
    public static boolean isToolModifier(String entry) {
        return entry.startsWith("+") || entry.startsWith("-");
    }

    /**
     * pi {@code getToolListError}（:95-105）——<b>逐字错误文案</b>。
     * @return 问题描述；合法时 {@code null}
     */
    public static String getToolListError(List<String> entries) {
        var modifiers = entries.stream().filter(ToolSelection::isToolModifier).toList();
        if (modifiers.isEmpty()) {
            return null;
        }
        if (modifiers.size() < entries.size()) {
            return "tool names cannot be mixed with +name or -name entries";
        }
        var pattern = modifiers.stream().filter(e -> e.contains("*")).findFirst().orElse(null);
        if (pattern != null) {
            return "+name and -name entries take exact tool names, not patterns: " + pattern;
        }
        return null;
    }

    /** pi {@code applyToolModifiers}（:108-118）：按序应用 {@code +name} 增 / {@code -name} 删。 */
    public static List<String> applyToolModifiers(List<String> base, List<String> entries) {
        var tools = new ArrayList<>(base);
        for (var entry : entries) {
            if (!isToolModifier(entry)) {
                continue;
            }
            var name = entry.substring(1);
            var index = tools.indexOf(name);
            if (entry.startsWith("+") && index == -1 && !name.isEmpty()) {
                tools.add(name);
            } else if (entry.startsWith("-") && index != -1) {
                tools.remove(index);
            }
        }
        return tools;
    }

    /** pi {@code resolveDefaultTools}（:135-138）：裸名替换默认集，修饰符再按序叠。 */
    public static List<String> resolveDefaultTools(List<String> entries) {
        var plain = entries.stream().filter(e -> !isToolModifier(e)).toList();
        return applyToolModifiers(!plain.isEmpty() || entries.isEmpty() ? plain : DEFAULT_TOOL_NAMES, entries);
    }

    /** pi {@code mergeDefaultTools}（:124-129）：覆盖层全修饰符 ⇒ 追加到继承选择之后。 */
    public static List<String> mergeDefaultTools(List<String> base, List<String> overrides) {
        if (overrides == null) {
            return base;
        }
        if (base == null || !overrides.stream().allMatch(ToolSelection::isToolModifier)) {
            return overrides;
        }
        var merged = new ArrayList<>(base);
        merged.addAll(overrides);
        return merged;
    }

    /**
     * pi {@code sdk.ts:280-294} 的解析结果（{@code Resolved} 对象即「选择策略」）。
     * 匹配器在构造时固化（pi 是同形闭包）。
     */
    public static final class Resolved {
        private final List<String> initialActiveToolNames;
        /** pi {@code _allowedTools}；{@code null ≙ undefined}（无 allowlist）。 */
        private final Predicate<String> allowed;
        /** pi {@code _excludedTools}；{@code null ≙ undefined}。 */
        private final Predicate<String> excluded;
        /** pi {@code _allowlistFiltersMcp}（agent-session.ts:504-506）。 */
        private final boolean allowlistFiltersMcp;
        /**
         * 批 16（T5a）：pi {@code usesDefaultTools}（sdk.ts:472）——
         * {@code (tools 未给 || 纯 +/- 修饰符列表) && !noTools}；为 false 的会话
         * （显式纯名列表 {@code --tools}）reload 完全不碰工具集。
         */
        private final boolean usesDefaultTools;
        /**
         * 批 16（T5a）：pi {@code defaultToolModifiers}（sdk.ts:473）——
         * 修饰符列表；无 ⇒ 空表（pi 的 undefined，applyToolModifiers 空表即恒等）。
         */
        private final List<String> defaultToolModifiers;

        private Resolved(List<String> initialActiveToolNames, Predicate<String> allowed,
                         Predicate<String> excluded, boolean allowlistFiltersMcp,
                         boolean usesDefaultTools, List<String> defaultToolModifiers) {
            this.initialActiveToolNames = initialActiveToolNames;
            this.allowed = allowed;
            this.excluded = excluded;
            this.allowlistFiltersMcp = allowlistFiltersMcp;
            this.usesDefaultTools = usesDefaultTools;
            this.defaultToolModifiers = defaultToolModifiers;
        }

        /** pi {@code usesDefaultTools}（sdk.ts:472）。 */
        public boolean usesDefaultTools() {
            return usesDefaultTools;
        }

        /** pi {@code defaultToolModifiers}（sdk.ts:473）；空表 ≙ undefined。 */
        public List<String> defaultToolModifiers() {
            return defaultToolModifiers;
        }

        /** pi {@code _isAllowedTool}（agent-session.ts:1537-1541）——<b>注册面</b>门。 */
        public boolean isAllowed(String name) {
            if (excluded != null && excluded.test(name)) {
                return false;
            }
            if (allowed == null || allowed.test(name)) {
                return true;
            }
            return !allowlistFiltersMcp && ToolNameMatcher.isMcpToolName(name);
        }

        /**
         * pi 的<b>激活面</b>：先过注册面门（激活只发生在注册工具上，
         * agent-session.ts:3573 的 {@code _isAllowedTool} 过滤）；allowlist 在册时
         * 「点名/匹配即激活」（:3571-3577，含默认不激活的 powershell）；否则看初始激活名表
         * （sdk.ts:294 → _buildRuntime activeToolNames）。
         */
        public boolean isActiveOnLoad(String name) {
            return isAllowed(name)
                && (allowed != null ? allowed.test(name) : initialActiveToolNames.contains(name));
        }
    }

    /**
     * pi {@code sdk.ts:280-294}（B236 核心解析）。前置：{@code tools} 已经
     * {@link #getToolListError} 校验（CLI 在解析期验，pi args.ts:155-159 同位）。
     *
     * @param tools                {@code --tools} 条目；null ≙ 未给，空表 ≙ 给了但全空（pi 的 []）
     * @param excludeTools         {@code --exclude-tools} 条目；null ≙ 未给
     * @param noToolsAll           {@code --no-tools}（pi {@code noTools === "all"}）
     * @param noBuiltinTools       {@code --no-builtin-tools}（pi {@code noTools === "builtin"}）
     * @param settingsDefaultTools {@code SettingsAccessors.getDefaultTools()}；null ≙ undefined
     */
    public static Resolved resolve(List<String> tools, List<String> excludeTools,
                                   boolean noToolsAll, boolean noBuiltinTools,
                                   List<String> settingsDefaultTools) {
        // pi :282 —— "all" 与 "builtin" 都把默认选择清空；差别只在 allowlist（下）。
        var defaultToolNames = (noToolsAll || noBuiltinTools)
            ? List.<String>of()
            : (settingsDefaultTools != null ? settingsDefaultTools : DEFAULT_TOOL_NAMES);
        // pi :285-286 —— 全修饰符名单不是 allowlist，是改默认选择。
        var toolModifiers = tools != null && tools.stream().anyMatch(ToolSelection::isToolModifier)
            ? tools : null;
        var selectedToolNames = toolModifiers != null
            ? applyToolModifiers(defaultToolNames, toolModifiers) : tools;
        // pi :287-291 —— 修饰符名单一般不成 allowlist；只有叠加在 noTools:"all" 上时
        // 例外（pi 注释：让 +name 在零基础上点名激活）。
        List<String> allowedToolNames = toolModifiers != null
            ? (noToolsAll ? selectedToolNames : null)
            : (tools != null ? tools : (noToolsAll ? List.of() : null));
        // pi :292-294
        Predicate<String> excluded =
            excludeTools != null ? ToolNameMatcher.create(excludeTools) : null;
        var initialActiveToolNames = (selectedToolNames != null ? selectedToolNames : defaultToolNames)
            .stream().filter(name -> excluded == null || !excluded.test(name)).toList();
        // pi agent-session.ts:504-506
        var allowlistFiltersMcp = allowedToolNames != null
            && (allowedToolNames.isEmpty()
                || allowedToolNames.stream().anyMatch(e -> e.startsWith("mcp__")));
        // 批 16（T5a，pi sdk.ts:472-473）：reload 的 defaultTools diff 前置状态。
        // pi 的 noTools 真值 ⇔ java 的 noToolsAll || noBuiltinTools（:281 的
        // `options.noTools ? [] : ...` 同口径）。
        var usesDefaultTools = (tools == null || toolModifiers != null)
            && !noToolsAll && !noBuiltinTools;
        return new Resolved(initialActiveToolNames,
            allowedToolNames != null ? ToolNameMatcher.create(allowedToolNames) : null,
            excluded, allowlistFiltersMcp, usesDefaultTools,
            toolModifiers != null ? List.copyOf(toolModifiers) : List.of());
    }
}
