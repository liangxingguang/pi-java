package com.pijava.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 包 A3b：{@link ToolChangeDeclaration}（pi {@code agent-loop.ts:281-333}）的四条分支。
 *
 * <p><b>为什么除了 conformance 还要这一份</b>：{@code conformance/pi-out/S*.pi.jsonl} 覆盖了
 * 「起手无声明 ⇒ 新建一条」与「上下文被整体替换 ⇒ 重新声明」两条（S2/S9 的帧序），
 * <b>但没有任何剧本会产生 pending 系统消息</b> ⇒ 第 ③ 支（合并进 pending 那条消息）
 * 在端到端差分里不可达。那一支正是 R3 补 {@code NextTurnUpdate.messages} 的全部理由，
 * 故在此直测。</p>
 *
 * <p>⚠️ 三条标了「identity」的用例断言的是**列表对象身份**（pi 的三条返回路径各自保留
 * 调用方的对象）：`declareToolChanges` 无变化时返回入参本身，调用方据此知道「什么都没发生」。
 * 用 {@code isSameAs}/{@code isNotSameAs} 而不是 {@code isEqualTo} —— record 的相等会把
 * 「换了一份等值的列表」也算通过。</p>
 */
class ToolChangeDeclarationTest {

    // ── ③ 无 pending：新建一条 ───────────────────────────────────────

    /** pi 的 conformance 形状：上下文只有工具、转录里什么都没声明 ⇒ 起手必须宣告一次。 */
    @Test
    void insertsADeclarationBeforeTheFirstNonSystemMessage() {
        var user = user("hi");
        var pending = List.<Message>of(user);
        var context = new Context(null, new ArrayList<>(), List.of(agentTool("echo")));

        var result = ToolChangeDeclaration.declare(context, pending);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(Message.SystemMessage.class);
        var declared = (Message.SystemMessage) result.get(0);
        assertThat(declared.content()).containsExactly(new ContentBlock.TextContent(""));
        assertThat(declared.toolsAdded()).extracting(ToolDefinition::name).containsExactly("echo");
        assertThat(declared.toolsRemoved()).isEmpty();
        assertThat(result.get(1)).isSameAs(user);
    }

    /** 删减方向：可执行集**变小** ⇒ {@code toolsRemoved} 只装名字。 */
    @Test
    void declaresRemovalsWhenTheExecutableSetShrinks() {
        var context = new Context(null, new ArrayList<>(List.of(system(List.of(decl("a"), decl("b"))))),
            List.of(agentTool("a")));

        var result = ToolChangeDeclaration.declare(context, List.of(user("hi")));

        assertThat(result.get(0)).isInstanceOf(Message.SystemMessage.class);
        var declared = (Message.SystemMessage) result.get(0);
        assertThat(declared.toolsAdded()).isEmpty();
        assertThat(declared.toolsRemoved()).extracting(r -> r.name()).containsExactly("b");
    }

    /** 「定义变了 ＝ 先删后加」：同名工具在两侧各出现一次。 */
    @Test
    void declaresAChangedDefinitionOnBothSides() {
        var context = new Context(null,
            new ArrayList<>(List.of(system(List.of(decl("a", "old"))))), List.of(agentTool("a", "new")));

        var result = ToolChangeDeclaration.declare(context, List.of(user("hi")));

        assertThat(result.get(0)).isInstanceOf(Message.SystemMessage.class);
        var declared = (Message.SystemMessage) result.get(0);
        assertThat(declared.toolsAdded()).extracting(ToolDefinition::name).containsExactly("a");
        assertThat(declared.toolsAdded().get(0).description()).isEqualTo("new");
        assertThat(declared.toolsRemoved()).extracting(r -> r.name()).containsExactly("a");
    }

    /** 无变化 ⇒ 原入参**原样返回**（同一列表对象）。 */
    @Test
    void returnsThePendingListIdentityWhenNothingChanged() {
        var pending = List.<Message>of(user("hi"));
        var context = new Context(null,
            new ArrayList<>(List.of(system(List.of(decl("echo"))))), List.of(agentTool("echo")));

        assertThat(ToolChangeDeclaration.declare(context, pending)).isSameAs(pending);
    }

    // ── ④ 有 pending 系统消息：写回它 ─────────────────────────────────

    /**
     * 合并支：pending 里那条消息的工具字段是**意图**，基准要先把它剥掉，
     * 差写回它自己 —— 于是模型看到的是**一条**同时带正文与工具增删的更新，
     * 而不是另起一条（pi {@code :311-315}）。
     */
    @Test
    void mergesTheDeltaIntoThePendingSystemMessage() {
        var prepared = new Message.SystemMessage("sections changed", Instant.ofEpochMilli(7), Map.of(),
            List.of(), List.of());
        var context = new Context(null,
            new ArrayList<>(List.of(system(List.of(decl("echo"))))), List.of(agentTool("echo"), agentTool("extra")));

        var result = ToolChangeDeclaration.declare(context, List.of(prepared));

        assertThat(result).hasSize(1);
        var merged = (Message.SystemMessage) result.get(0);
        assertThat(merged.content()).containsExactly(new ContentBlock.TextContent("sections changed"));
        assertThat(merged.timestamp()).isEqualTo(Instant.ofEpochMilli(7));
        assertThat(merged.toolsAdded()).extracting(ToolDefinition::name).containsExactly("extra");
        assertThat(merged.toolsRemoved()).isEmpty();
        assertThat(merged).isNotSameAs(prepared);
    }

    /** 无变化且 pending 自己也没声明过工具 ⇒ 连那条消息都不换（保留调用方对象）。 */
    @Test
    void keepsThePendingMessageIdentityWhenItDeclaresNothingAndNothingChanged() {
        var prepared = new Message.SystemMessage("sections changed", Instant.ofEpochMilli(7), Map.of(),
            List.of(), List.of());
        var pending = List.<Message>of(prepared);
        var context = new Context(null,
            new ArrayList<>(List.of(system(List.of(decl("echo"))))), List.of(agentTool("echo")));

        assertThat(ToolChangeDeclaration.declare(context, pending)).isSameAs(pending);
    }

    /**
     * pending 自己声明过工具 ⇒ 即使差为空也要**重写**它：那份声明是意图，
     * 基准已经把它剥掉了，留着它会让「重放恰好得到 {@code context.tools}」不成立。
     */
    @Test
    void rewritesAPendingMessageThatDeclaresStaleTools() {
        var prepared = new Message.SystemMessage("", Instant.ofEpochMilli(7), Map.of(),
            List.of(decl("stale")), List.of());
        var context = new Context(null,
            new ArrayList<>(List.of(system(List.of(decl("echo"))))), List.of(agentTool("echo")));

        var result = ToolChangeDeclaration.declare(context, List.of(prepared));

        var rewritten = (Message.SystemMessage) result.get(0);
        assertThat(rewritten.toolsAdded()).isEmpty();
        assertThat(rewritten.toolsRemoved()).isEmpty();
        assertThat(rewritten.timestamp()).isEqualTo(Instant.ofEpochMilli(7));
    }

    /** 锚点是 pending 里**最后**一条系统消息，不是第一条。 */
    @Test
    void anchorsOnTheLastPendingSystemMessage() {
        var first = new Message.SystemMessage("first", Instant.ofEpochMilli(1), Map.of(), List.of(), List.of());
        var last = new Message.SystemMessage("last", Instant.ofEpochMilli(2), Map.of(), List.of(), List.of());
        var context = new Context(null, new ArrayList<>(), List.of(agentTool("echo")));

        var result = ToolChangeDeclaration.declare(context, List.of(first, user("hi"), last));

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isSameAs(first);
        assertThat(result.get(2)).isInstanceOf(Message.SystemMessage.class);
        var merged = (Message.SystemMessage) result.get(2);
        assertThat(merged.content()).containsExactly(new ContentBlock.TextContent("last"));
        assertThat(merged.toolsAdded()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static Message system(List<ToolDefinition> added) {
        return new Message.SystemMessage("", Instant.EPOCH, Map.of(), added, List.of());
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ToolDefinition decl(String name) {
        return decl(name, name + " tool");
    }

    private static ToolDefinition decl(String name, String description) {
        return new ToolDefinition(name, description, Map.of("type", "object"));
    }

    private static AgentTool<?, ?> agentTool(String name) {
        return agentTool(name, name + " tool");
    }

    private static AgentTool<?, ?> agentTool(String name, String description) {
        return new TestTool(name, description);
    }

    /** 剧本工具的骨架：只有名字/描述参与声明比较，执行不被使用。 */
    private record TestTool(String name, String description) implements AgentTool<Void, Void> {
        @Override public String label() { return name; }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult<Void> execute(String toolCallId, Void params, AbortSignal signal,
                ToolUpdateCallback<Void> onUpdate, ToolContext context) {
            return ToolResult.success(null);
        }
    }
}
