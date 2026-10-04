package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.message.Message;

/**
 * pi {@code packages/ai/test/system-message-replay.test.ts:110-145} 里那**三条**纯函数用例的移植
 * （包 A2 只移植了文件里重放那一半 —— 这三条要的函数当时还不存在，{@code 原 docs/51 §3 F1}）。
 *
 * <p>⚠️ 与 pi 夹具的两处**有意差异**，都是 java 没有对应形状的地方：</p>
 *
 * <ul>
 *   <li>pi 第 112 行拿 {@code {...tool("a"), execute: () => {}}}（多一个可执行字段）证明
 *       「声明比较不看可执行字段」；java 的可执行面在 agent-core，ai 层的
 *       {@link ToolDefinition} 装的是 {@code label}/{@code promptSnippet}/
 *       {@code promptGuidelines}/{@code renderShell} 四个**展示与提示词**元数据 ⇒ 本夹具
 *       改拿它们证明同一件事（{@code toToolDeclaration} 剥掉的是它们）；</li>
 *   <li>pi 第 114 行用 {@code constrainedSampling: false} 证明「第四键参与比较」；
 *       java 没有该字段（{@code 原 docs/50 §10 L-A}）⇒ 该断言**缺席**，登记在 L-A。</li>
 * </ul>
 *
 * <p>另外补了 {@code getDeclaredTools} 与 {@code resolveTranscriptTools} 的直测 —— pi 在那份
 * 夹具里没直测它们，但 {@code transcript-tool-changes.test.ts} 的线格用例把它们当中间量用，
 * 本包的车道（A3c）直接依赖这两个函数的语义（{@code 原 docs/51 §2 P3}）。</p>
 */
class ToolStateChangesTest {

    // ── declarationsEqual（pi :111-115）────────────────────────────────

    /**
     * ⚠️ 探针靶心：{@code toToolDeclaration} 若没剥掉四个元数据字段，本用例红
     * （{@code ToolDefinition} 的 record {@code equals} 是**全组件**比较，会判不等）。
     */
    @Test
    void comparesDeclarationsWithoutDisplayOrPromptMetadata() {
        var plain = tool("a");
        var decorated = new ToolDefinition("a", "a tool", schema(), "A label",
            "use a when…", List.of("prefer a"), "self");

        assertThat(Transcripts.declarationsEqual(decorated, plain)).isTrue();

        assertThat(Transcripts.declarationsEqual(plain, tool("a", "changed"))).isFalse();
    }

    /** 定义不同（此处只差 schema）⇒ 不等。 */
    @Test
    void comparesTheSchemaItself() {
        var objectSchema = tool("a");
        var stringSchema = new ToolDefinition("a", "a tool",
            Map.of("type", "string"), "a", null, List.of(), "default");

        assertThat(Transcripts.declarationsEqual(objectSchema, stringSchema)).isFalse();
    }

    // ── getToolStateChanges（pi :117-124）─────────────────────────────

    /**
     * pi 的逐字用例，**并且钉住两侧的顺序** —— pi 的 {@code filter} 保序：
     * {@code toolsAdded} 跟 {@code current}（{@code b(changed)} 在前、{@code c} 在后）、
     * {@code toolsRemoved} 跟 {@code previous}（{@code a} 在前、{@code b} 在后）。
     *
     * <p>顺序不是装饰：生产者按它决定 {@code tool_addition}/{@code tool_removal} 块的次序。</p>
     */
    @Test
    void changedDefinitionsAreARemovalPlusAnAddition() {
        var changes = Transcripts.getToolStateChanges(
            List.of(tool("a"), tool("b")),
            List.of(tool("b", "changed"), tool("c")));

        assertThat(changes.toolsAdded()).containsExactly(
            Transcripts.toToolDeclaration(tool("b", "changed")),
            Transcripts.toToolDeclaration(tool("c")));
        assertThat(changes.toolsRemoved()).containsExactly(
            new ToolReference("a"), new ToolReference("b"));
    }

    /** 「定义变了 ＝ 先删后加」：同名工具在两侧**各出现一次**。 */
    @Test
    void aRedefinedToolAppearsOnBothSides() {
        var changes = Transcripts.getToolStateChanges(
            List.of(tool("a")), List.of(tool("a", "changed")));

        assertThat(changes.toolsAdded()).extracting(ToolDeclaration::name).containsExactly("a");
        assertThat(changes.toolsRemoved()).extracting(ToolReference::name).containsExactly("a");
    }

    @Test
    void identicalStatesProduceEmptyChanges() {
        var changes = Transcripts.getToolStateChanges(List.of(tool("a")), List.of(tool("a")));

        assertThat(changes.toolsAdded()).isEmpty();
        assertThat(changes.toolsRemoved()).isEmpty();
    }

    /** 删除：只在 {@code toolsRemoved} 一侧。 */
    @Test
    void aDroppedToolIsRemovalOnly() {
        var changes = Transcripts.getToolStateChanges(List.of(tool("a"), tool("b")), List.of(tool("a")));

        assertThat(changes.toolsAdded()).isEmpty();
        assertThat(changes.toolsRemoved()).extracting(ToolReference::name).containsExactly("b");
    }

    // ── getDeclaredTools（pi :169-177）────────────────────────────────

    /**
     * ⚠️ 与 {@link Transcripts#getCurrentTools} 的**唯一**区别是看不看 {@code toolsRemoved}：
     * 本函数列「历史上声明过什么」，按**首次声明序**，重声明换值不改位置。
     */
    @Test
    void declaredToolsIgnoreRemovalsAndKeepFirstDeclarationOrder() {
        var messages = List.of(
            system(List.of(tool("a"), tool("b")), List.of()),
            system(List.of(), List.of(new ToolReference("a"))),
            system(List.of(tool("b", "changed")), List.of()));

        assertThat(Transcripts.getDeclaredTools(messages)).extracting(ToolDefinition::name)
            .containsExactly("a", "b");
        assertThat(Transcripts.getDeclaredTools(messages).get(1).description()).isEqualTo("changed");
        // 对照：当前工具表**看** removals，且重声明同样换值不改位
        assertThat(Transcripts.getCurrentTools(messages)).extracting(ToolDefinition::name)
            .containsExactly("b");
    }

    // ── hasToolRedefinitions / hasNonAdditiveToolChanges（pi :126-144）──

    /** pi 的主夹具：有 {@code toolsRemoved} ⇒ 非增量；但没有重定义。 */
    @Test
    void aRemovalIsNonAdditiveButNotARedefinition() {
        var messages = List.of(
            system(List.of(tool("first")), List.of()),
            system(List.of(tool("second")), List.of(new ToolReference("first"))));

        assertThat(Transcripts.hasNonAdditiveToolChanges(messages)).isTrue();
        assertThat(Transcripts.hasToolRedefinitions(messages)).isFalse();
    }

    /** 纯「加」的历史：两个判据**都**为假 ⇒ 车道可以就地锚定。 */
    @Test
    void aPurelyAdditiveHistoryIsReplayableInPlace() {
        var messages = List.of(
            system(List.of(tool("a")), List.of()),
            system(List.of(tool("b")), List.of()));

        assertThat(Transcripts.hasNonAdditiveToolChanges(messages)).isFalse();
        assertThat(Transcripts.hasToolRedefinitions(messages)).isFalse();
    }

    /** 同名重声明 ⇒ 两个判据**都**为真（pi 的 {@code redeclared} 用例）。 */
    @Test
    void aRedeclarationIsBothNonAdditiveAndARedefinition() {
        var messages = List.of(
            system(List.of(tool("a")), List.of()),
            system(List.of(tool("a", "changed")), List.of()));

        assertThat(Transcripts.hasNonAdditiveToolChanges(messages)).isTrue();
        assertThat(Transcripts.hasToolRedefinitions(messages)).isTrue();
    }

    /**
     * ⚠️ 同名**同定义**重声明：非增量（按名去重 ⇒ 第二次出现即算重放不了），
     * 但**不是**重定义 —— 两者不是同一个判据，别合并。
     */
    @Test
    void aSameDefinitionRedeclarationIsNonAdditiveButNotARedefinition() {
        var messages = List.of(
            system(List.of(tool("a")), List.of()),
            system(List.of(tool("a")), List.of()));

        assertThat(Transcripts.hasNonAdditiveToolChanges(messages)).isTrue();
        assertThat(Transcripts.hasToolRedefinitions(messages)).isFalse();
    }

    // ── resolveTranscriptTools（pi :220-234）──────────────────────────

    /**
     * 车道不支持就地锚定（= 今天**所有**车道，{@code 原 docs/51 §3 F5}）⇒ 出参是完整的当前工具表，
     * {@code anchorsAdditions} 为假。
     */
    @Test
    void withoutAnchorSupportTheRequestCarriesTheCompleteCurrentTools() {
        var result = Transcripts.resolveTranscriptTools(additive().messages(), false);

        assertThat(result.anchorsAdditions()).isFalse();
        assertThat(result.requestTools()).extracting(ToolDefinition::name)
            .containsExactly("first", "second");
    }

    /**
     * ⚠️ 本用例是 {@code resolveTranscriptTools} 的**靶心**：锚定支下请求级字段
     * **只有前导消息声明的那一份**，后续增量**不在**里面（它们靠车道的就地锚定发出去，
     * {@code 原 docs/51 §2 P3} 的警告）。把它写成 {@code requestTools == getCurrentTools(messages)}
     * 的夹具会漏掉这个差异。
     */
    @Test
    void withAnchorSupportTheRequestCarriesOnlyTheLeadingDeclarations() {
        var result = Transcripts.resolveTranscriptTools(additive().messages(), true);

        assertThat(result.anchorsAdditions()).isTrue();
        assertThat(result.requestTools()).extracting(ToolDefinition::name).containsExactly("first");
    }

    /** 有删除 / 重定义 ⇒ 锚定不成立，退回完整当前表（哪怕车道声明支持）。 */
    @Test
    void nonAdditiveHistoryFallsBackToTheCompleteCurrentTools() {
        var messages = List.of(
            system(List.of(tool("first")), List.of()),
            system(List.of(tool("second")), List.of(new ToolReference("first"))));

        var result = Transcripts.resolveTranscriptTools(messages, true);

        assertThat(result.anchorsAdditions()).isFalse();
        assertThat(result.requestTools()).extracting(ToolDefinition::name).containsExactly("second");
    }

    /** 没有前导系统消息 ⇒ pi 的 {@code ?? []}：请求级工具表是空的，仍然算锚定。 */
    @Test
    void anchoringWithoutALeadingSystemMessageYieldsNoRequestTools() {
        var messages = List.<Message>of(
            new Message.UserMessage(List.of()),
            system(List.of(tool("a")), List.of()));

        var result = Transcripts.resolveTranscriptTools(messages, true);

        assertThat(result.anchorsAdditions()).isTrue();
        assertThat(result.requestTools()).isEmpty();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /** 纯增量的转录：前导声明 {@code first}，中途追加 {@code second}。 */
    private static TranscriptContext additive() {
        return new TranscriptContext(List.of(
            system(List.of(tool("first")), List.of()),
            new Message.UserMessage(List.of()),
            system(List.of(tool("second")), List.of())));
    }

    private static Message system(List<ToolDefinition> added, List<ToolReference> removed) {
        return new Message.SystemMessage("", Instant.EPOCH, Map.of(), added, removed);
    }

    private static ToolDefinition tool(String name) {
        return tool(name, name + " tool");
    }

    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, schema());
    }

    /** 用 {@link LinkedHashMap} 而不是 {@code Map.of}：让「键序」在构造侧就不是常量。 */
    private static Map<String, Object> schema() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return schema;
    }
}
