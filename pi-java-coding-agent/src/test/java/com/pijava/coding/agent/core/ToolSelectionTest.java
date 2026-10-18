package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B236（docs/11 §1.2/§1.4/§4.1）：pi 工具选择三层语义 ——
 * {@code getToolListError}/{@code applyToolModifiers}/{@code resolveDefaultTools}/
 * {@code mergeDefaultTools}（settings-manager.ts:83-137）＋ {@code resolve}
 * （sdk.ts:280-294）＋ 注册面门 {@code isAllowed}（agent-session.ts:1537-1541）。
 * §3.6 对账表 12 行全在此有对应断言。
 */
class ToolSelectionTest {

    // ── getToolListError（逐字文案）────────────────────────────────

    @Test
    void toolListErrorRejectsMixedPlainAndModifiers() {
        assertThat(ToolSelection.getToolListError(List.of("read", "+grep")))
            .isEqualTo("tool names cannot be mixed with +name or -name entries");
    }

    @Test
    void toolListErrorRejectsPatternsInModifiers() {
        assertThat(ToolSelection.getToolListError(List.of("-gr*")))
            .isEqualTo("+name and -name entries take exact tool names, not patterns: -gr*");
    }

    @Test
    void toolListErrorAcceptsValidLists() {
        assertThat(ToolSelection.getToolListError(List.of("read", "grep"))).isNull();
        assertThat(ToolSelection.getToolListError(List.of("+grep", "-bash"))).isNull();
        assertThat(ToolSelection.getToolListError(List.of())).isNull();
    }

    // ── applyToolModifiers ────────────────────────────────────────

    @Test
    void modifiersApplyInOrder() {
        var result = ToolSelection.applyToolModifiers(
            List.of("read", "bash", "edit", "write"), List.of("+grep", "-bash"));
        assertThat(result).containsExactly("read", "edit", "write", "grep");
    }

    @Test
    void duplicatePlusDoesNotAddTwice() {
        var result = ToolSelection.applyToolModifiers(
            List.of("read"), List.of("+grep", "+grep"));
        assertThat(result).containsExactly("read", "grep");
    }

    @Test
    void emptyModifierNameIsNotAdded() {
        // pi: `+name` 要求 name 非空（`index === -1 && name`）。
        var result = ToolSelection.applyToolModifiers(List.of("read"), List.of("+"));
        assertThat(result).containsExactly("read");
    }

    // ── resolveDefaultTools ───────────────────────────────────────

    @Test
    void plainNamesReplaceTheDefaultSelection() {
        assertThat(ToolSelection.resolveDefaultTools(List.of("grep", "find")))
            .containsExactly("grep", "find");
    }

    @Test
    void modifierOnlyListStacksOnDefaults() {
        assertThat(ToolSelection.resolveDefaultTools(List.of("+powershell", "-bash")))
            .containsExactly("read", "edit", "write", "powershell");
    }

    @Test
    void emptyListResolvesToEmpty() {
        // pi: defaultTools: [] 是「零默认工具」，不是「未设置」（undefined 在 accessor 层判）。
        assertThat(ToolSelection.resolveDefaultTools(List.of())).isEmpty();
    }

    // ── mergeDefaultTools ─────────────────────────────────────────

    @Test
    void mergeWithNullOverridesKeepsBase() {
        assertThat(ToolSelection.mergeDefaultTools(List.of("read"), null))
            .containsExactly("read");
    }

    @Test
    void modifierOnlyOverridesAppendToInherited() {
        assertThat(ToolSelection.mergeDefaultTools(List.of("grep", "find"), List.of("+ls", "-find")))
            .containsExactly("grep", "find", "+ls", "-find");
    }

    @Test
    void plainOverridesReplaceInherited() {
        assertThat(ToolSelection.mergeDefaultTools(List.of("grep"), List.of("read", "write")))
            .containsExactly("read", "write");
        assertThat(ToolSelection.mergeDefaultTools(null, List.of("+grep")))
            .containsExactly("+grep");
    }

    // ── resolve：§3.6 对账表 12 行 ────────────────────────────────

    @Test
    void bareRunRegistersAllAndActivatesDefaultFour() {
        // 行 1：裸跑（无旗标无设置）—— 注册面全过，激活面 = DEFAULT_TOOL_NAMES 四件。
        var r = ToolSelection.resolve(null, null, false, false, null);
        for (var name : List.of("read", "bash", "edit", "write", "grep", "find", "ls", "powershell")) {
            assertThat(r.isAllowed(name)).as("isAllowed(%s)", name).isTrue();
        }
        for (var name : List.of("read", "bash", "edit", "write")) {
            assertThat(r.isActiveOnLoad(name)).as("active(%s)", name).isTrue();
        }
        for (var name : List.of("grep", "find", "ls", "powershell")) {
            assertThat(r.isActiveOnLoad(name)).as("inactive(%s)", name).isFalse();
        }
    }

    @Test
    void settingsDefaultToolsReplaceTheActiveSelection() {
        // 行 2/3：settings 侧 getDefaultTools() 已 resolveDefaultTools，这里收解析后名表。
        var r = ToolSelection.resolve(null, null, false, false, List.of("grep", "find"));
        assertThat(r.isActiveOnLoad("grep")).isTrue();
        assertThat(r.isActiveOnLoad("find")).isTrue();
        assertThat(r.isActiveOnLoad("read")).isFalse();
        assertThat(r.isAllowed("read")).isTrue();
        var plus = ToolSelection.resolve(null, null, false, false,
            ToolSelection.resolveDefaultTools(List.of("+powershell")));
        assertThat(plus.isActiveOnLoad("powershell")).isTrue();
        assertThat(plus.isActiveOnLoad("bash")).isTrue();
    }

    @Test
    void modifierOnlyToolsStackOnTheDefaultSelection() {
        // 行 4：--tools +powershell —— 全修饰符名单不是 allowlist，是改默认选择。
        var r = ToolSelection.resolve(List.of("+powershell"), null, false, false, null);
        assertThat(r.isAllowed("grep")).isTrue();
        assertThat(r.isActiveOnLoad("powershell")).isTrue();
        assertThat(r.isActiveOnLoad("read")).isTrue();
        assertThat(r.isActiveOnLoad("grep")).isFalse();
    }

    @Test
    void plainToolsFormAnAllowlist() {
        // 行 5：--tools read,grep —— 注册面只留点名者（内建不匹配即不注册）。
        var r = ToolSelection.resolve(List.of("read", "grep"), null, false, false, null);
        assertThat(r.isAllowed("read")).isTrue();
        assertThat(r.isAllowed("grep")).isTrue();
        assertThat(r.isAllowed("bash")).isFalse();
        assertThat(r.isActiveOnLoad("read")).isTrue();
        assertThat(r.isActiveOnLoad("grep")).isTrue();
        assertThat(r.isActiveOnLoad("bash")).isFalse();
    }

    @Test
    void patternToolsMatchAndActivate() {
        // 行 6：--tools 'gr*' —— 模式点名即激活（含默认不激活者）。
        var r = ToolSelection.resolve(List.of("gr*"), null, false, false, null);
        assertThat(r.isAllowed("grep")).isTrue();
        assertThat(r.isActiveOnLoad("grep")).isTrue();
        assertThat(r.isAllowed("read")).isFalse();
    }

    @Test
    void excludedWinsOverAllowlisted() {
        // 行 7：--tools read --exclude-tools read —— 注册面空。
        var r = ToolSelection.resolve(List.of("read"), List.of("read"), false, false, null);
        assertThat(r.isAllowed("read")).isFalse();
        assertThat(r.isActiveOnLoad("read")).isFalse();
    }

    @Test
    void excludeToolsDenylistKeepsRest() {
        // 行 8：--exclude-tools bash —— 注册 7 件，激活面去掉 bash。
        var r = ToolSelection.resolve(null, List.of("bash"), false, false, null);
        assertThat(r.isAllowed("bash")).isFalse();
        assertThat(r.isAllowed("grep")).isTrue();
        assertThat(r.isActiveOnLoad("read")).isTrue();
        assertThat(r.isActiveOnLoad("bash")).isFalse();
        assertThat(r.isActiveOnLoad("edit")).isTrue();
        assertThat(r.isActiveOnLoad("write")).isTrue();
    }

    @Test
    void noToolsEmptiesTheRegistry() {
        // 行 9：--no-tools —— 空 allowlist ⇒ 注册面空（pi getAllTools()==[]）。
        var r = ToolSelection.resolve(null, null, true, false, null);
        assertThat(r.isAllowed("read")).isFalse();
        assertThat(r.isActiveOnLoad("read")).isFalse();
        // 空名单连 MCP 保留都没有（allowlistFiltersMcp = true）。
        assertThat(r.isAllowed("mcp__x__y")).isFalse();
    }

    @Test
    void noBuiltinToolsKeepsRegistryButActivatesNothing() {
        // 行 10：--no-builtin-tools —— 注册面保留，激活面空（扩展工具面 java 无）。
        var r = ToolSelection.resolve(null, null, false, true, null);
        assertThat(r.isAllowed("read")).isTrue();
        assertThat(r.isActiveOnLoad("read")).isFalse();
        assertThat(r.isActiveOnLoad("bash")).isFalse();
    }

    @Test
    void emptyToolsValueIsAnEmptyAllowlist() {
        // 行 11：--tools "" —— 给了但全空 ⇒ 零工具（pi 的 [] 非 undefined）。
        var r = ToolSelection.resolve(List.of(), null, false, false, null);
        assertThat(r.isAllowed("read")).isFalse();
        assertThat(r.isActiveOnLoad("read")).isFalse();
    }

    @Test
    void noToolsPlusModifierActivatesTheNamedTool() {
        // 行 12：--no-tools --tools +grep —— pi sdk.ts:287-290 例外臂：修饰符叠在
        // noTools:"all" 上时成为 allowlist，零基础上点名激活。
        var r = ToolSelection.resolve(List.of("+grep"), null, true, false, null);
        assertThat(r.isAllowed("grep")).isTrue();
        assertThat(r.isActiveOnLoad("grep")).isTrue();
        assertThat(r.isAllowed("read")).isFalse();
        assertThat(r.isActiveOnLoad("read")).isFalse();
    }

    // ── mcp__ 保留（裁决③）────────────────────────────────────────

    @Test
    void allowlistWithoutMcpEntriesKeepsMcpToolsRegisteredButInactive() {
        // pi _isAllowedTool 末行：非空 allowlist 无 mcp__ 条目 ⇒ MCP 工具保留注册
        // （给 codemode/tool_search），但不点名 ⇒ 不激活。
        var r = ToolSelection.resolve(List.of("read", "grep"), null, false, false, null);
        assertThat(r.isAllowed("mcp__radius__search")).isTrue();
        assertThat(r.isAllowed("read_mcp_resource")).isTrue();
        assertThat(r.isActiveOnLoad("mcp__radius__search")).isFalse();
    }

    @Test
    void allowlistWithMcpEntriesFiltersMcpToolsByMatch() {
        // allowlist 含 mcp__ 条目 ⇒ 保留条款关闭，MCP 工具按匹配器过滤。
        var r = ToolSelection.resolve(List.of("read", "mcp__radius__*"), null, false, false, null);
        assertThat(r.isAllowed("mcp__radius__search")).isTrue();
        assertThat(r.isActiveOnLoad("mcp__radius__search")).isTrue();
        assertThat(r.isAllowed("mcp__other__search")).isFalse();
    }
}
