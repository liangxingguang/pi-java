package com.pijava.ai.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;

/**
 * pi {@code packages/ai/src/utils/transcript.ts} 的重放一半。
 *
 * <p>包 A2（{@code docs/49}）落的是 provider <b>消费</b>侧需要的六个函数：车道不再各自读
 * {@code request.systemPrompt()} / {@code request.tools()}，而是从这里取「前导系统消息的
 * 文本」与「重放后的工具表」。归一那一半（{@code normalizeContext}）在
 * {@link ContextNormalizer}。</p>
 *
 * <p>包 A3（{@code docs/51}）补上<b>工具增删状态线</b>的另一半 ——
 * {@link #getDeclaredTools} / {@link #declarationsEqual} / {@link #getToolStateChanges} /
 * {@link #hasToolRedefinitions} / {@link #hasNonAdditiveToolChanges} /
 * {@link #resolveTranscriptTools}，外加两个结果类型 {@link ToolStateChanges} 与
 * {@link TranscriptTools}。生产者（{@code declareToolChanges}）在 agent-core 的
 * {@code ToolChangeDeclaration}。</p>
 *
 * <p>包 B87② 落地的 {@link #toToolDeclaration} 位置早于 A3：它不是状态线，而是两个写者
 * （{@code SessionJson} 的系统消息落线、{@code PiMessagesApi} 的线格）共用的投影。</p>
 */
public final class Transcripts {

    private Transcripts() {}

    /**
     * pi {@code transcript.ts:36-39} —— 转录**以下标 0 的系统消息**开头时返回它，否则 {@code null}。
     *
     * <p>判据是下标而不是「第一条 role=system」：pi 的三条车道逐字用 {@code i === 0}
     * （{@code openai-completions.ts:1249}、{@code mistral-conversations.ts:788}、
     * {@code openai-responses-shared.ts:218}）。</p>
     */
    public static Message.SystemMessage getInitialSystemMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(0) instanceof Message.SystemMessage system ? system : null;
    }

    /**
     * pi {@code transcript.ts:123-130} —— 把工具剥成**声明形状**（三键
     * {@code {name, description, parameters}}），pi 用它做「比较或持久化前的归一」
     * （{@code declarationsEqual} 与 {@code getToolStateChanges} 都走它）。
     *
     * <p>java 的两个写者共用它：{@code SessionJson} 的系统消息落线与 {@code PiMessagesApi}
     * 的线格 —— 包 B87② 之前这两处各写各的（一个是 {@code ToolDefinition} 全形、
     * 一个是手写三键），线上形状因此不一致。</p>
     *
     * <p>⚠️ pi 在这里还有一支 {@code ...(constrainedSampling === undefined ? {} :
     * { constrainedSampling })}，java 没有该字段（{@code docs/50 §10 L-A}）。</p>
     */
    public static ToolDeclaration toToolDeclaration(ToolDefinition tool) {
        return new ToolDeclaration(tool.name(), tool.description(), tool.inputSchema());
    }

    /**
     * {@link #toToolDeclaration} 的**反向**投影 —— 把线格上的声明形状装回转录里的工具槽。
     *
     * <p>⚠️ pi **没有**这个函数，因为 pi 的转录槽本就收声明形状（{@code SystemMessage.toolsAdded?: Tool[]}
     * 里的 {@code Tool} 就是三键）。java 的形状不同：{@link Message.SystemMessage#toolsAdded()}
     * 收的是 {@link ToolDefinition} 全形（包 A1 的取舍），所以在「把 {@code ToolStateChanges}
     * 写回一条系统消息」这条路上需要一次声明 → 全形的转换。</p>
     *
     * <p>它**有损**（{@code label}/{@code promptSnippet}/{@code promptGuidelines}/{@code renderShell}
     * 落回缺省），但那个方向本来就是 pi 的转录不具备的信息 —— 与
     * {@code MessageJsonCodec.decodeToolsAdded} 从三键 JSON 读回 {@code ToolDefinition} 是同一件事，
     * 且往返稳定（{@link #toToolDeclaration} 再剥一次仍是同一个声明）。</p>
     */
    public static ToolDefinition toToolDefinition(ToolDeclaration declaration) {
        return new ToolDefinition(declaration.name(), declaration.description(), declaration.parameters());
    }

    /**
     * pi {@code transcript.ts:42-44} —— 丢掉前导系统消息（供「提示走独立字段」的 API 用）。
     *
     * <p>⚠️ 与 pi 的一处不可观察差异：pi 在没有前导系统消息时返回**同一个数组**，
     * 这里两个分支都返回不可变副本（对象身份不是可观察行为）。</p>
     */
    public static List<Message> withoutInitialSystemMessage(List<Message> messages) {
        var supplied = messages == null ? List.<Message>of() : messages;
        if (getInitialSystemMessage(supplied) == null) {
            return List.copyOf(supplied);
        }
        return List.copyOf(supplied.subList(1, supplied.size()));
    }

    /**
     * pi {@code transcript.ts:47-53} —— 按序重放每条系统消息的工具增删。
     *
     * <p>每条消息内**先 removals 后 additions**（{@code :49-51}）；重声明的同名工具
     * **保留首次位置、换值**（pi 的 {@code Map.set} 对已存在键不改插入序，与 java 的
     * {@code LinkedHashMap.put} 同义）。</p>
     */
    public static List<ToolDefinition> getCurrentTools(List<Message> messages) {
        var tools = new LinkedHashMap<String, ToolDefinition>();
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            for (var removed : system.toolsRemoved()) {
                tools.remove(removed.name());
            }
            for (var added : system.toolsAdded()) {
                tools.put(added.name(), added);
            }
        }
        return List.copyOf(tools.values());
    }

    /**
     * pi {@code transcript.ts:57-74} —— 把每条系统消息重放成一条前导消息。
     *
     * <p>四条纪律逐条对齐 pi：content 逐条取文本、**非空才收**、以 {@code "\n\n"} 连；
     * sections 按名覆盖；tools 走 {@link #getCurrentTools}（故出参**没有** {@code toolsRemoved}）；
     * {@code timestamp} 取**第一条**系统消息的。</p>
     *
     * <p>「一条系统消息都没有且没有工具」⇒ {@code null}（pi {@code :71} 的
     * {@code timestamp === undefined && tools.length === 0}）。</p>
     *
     * <p>sections 值为 {@code null} ⇒ <b>删掉具名段</b>（pi {@code :81-83}）。⚠️ 顺序语义与
     * pi 的 {@code Map} 一致：覆盖**保留首次位置**，删掉再设**排到末尾**。</p>
     *
     * <p>⚠️ 出参的 {@code sections}/{@code toolsAdded} 为空时是空集合，而 pi **省略键**
     * —— 落线由 {@code SessionJson} 的空值省略门负责（A1 的 M4 探针已钉住那道门）。</p>
     */
    public static Message.SystemMessage getCurrentSystemMessage(List<Message> messages) {
        var content = new ArrayList<String>();
        var sections = new LinkedHashMap<String, String>();
        Instant timestamp = null;
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            if (timestamp == null) {
                timestamp = system.timestamp();
            }
            var text = MessageTexts.contentText(system.content());
            if (!text.isEmpty()) {
                content.add(text);
            }
            for (var entry : system.sections().entrySet()) {
                if (entry.getValue() == null) {
                    sections.remove(entry.getKey());
                } else {
                    sections.put(entry.getKey(), entry.getValue());
                }
            }
        }
        var tools = getCurrentTools(messages);
        if (timestamp == null && tools.isEmpty()) {
            return null;
        }
        return new Message.SystemMessage(String.join("\n\n", content),
            timestamp == null ? Instant.EPOCH : timestamp, sections, tools, List.of());
    }

    /** pi {@code transcript.ts:77-80} —— 重放后的系统提示文本（无系统消息 ⇒ 空串）。 */
    public static String getCurrentSystemPrompt(List<Message> messages) {
        var message = getCurrentSystemMessage(messages);
        return message == null ? "" : MessageTexts.getSystemMessageText(message);
    }

    /**
     * pi {@code transcript.ts:104-110} —— 头 + 全部非系统消息（顺序保持）。
     *
     * <p>对已经折叠过的转录**幂等**（pi 夹具 {@code system-message-replay.test.ts:59}）。</p>
     */
    public static TranscriptContext collapseSystemMessages(TranscriptContext context) {
        var supplied = context.messages();
        var head = getCurrentSystemMessage(supplied);
        var messages = new ArrayList<Message>(supplied.size() + 1);
        if (head != null) {
            messages.add(head);
        }
        for (var message : supplied) {
            if (message instanceof Message.SystemMessage) {
                continue;
            }
            messages.add(message);
        }
        return new TranscriptContext(messages);
    }

    /**
     * pi {@code transcript.ts:113-120} —— 模型接受中途系统消息就原样保留，否则折叠。
     *
     * <p>判据字段是 {@link ModelCompat#supportsMidConvoSystemMessages()}（pi
     * {@code types.ts:731}）：{@code null}（用户没写）与 {@code false} 同义 —— pi 的
     * {@code resolveTranscript(context, undefined)} 走折叠支。</p>
     */
    public static TranscriptContext resolveTranscript(TranscriptContext context, ModelInfo model) {
        boolean supports = model != null
            && Boolean.TRUE.equals(model.compat().supportsMidConvoSystemMessages());
        return supports ? context : collapseSystemMessages(context);
    }

    // ── 工具增删状态线（包 A3，docs/51）─────────────────────────────────

    /**
     * pi {@code transcript.ts:169-177} —— 这段历史里**出现过的所有定义**，按首次声明序，
     * 重声明换值不改位置。
     *
     * <p>⚠️ 与 {@link #getCurrentTools} 的**唯一**区别：本函数**不看 {@code toolsRemoved}**
     * —— 它回答「历史上声明过什么」（Anthropic 的 deferred 列表要的就是这个），
     * {@code getCurrentTools} 回答「现在还剩什么」。两个都要留。</p>
     */
    public static List<ToolDefinition> getDeclaredTools(List<Message> messages) {
        var definitions = new LinkedHashMap<String, ToolDefinition>();
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            for (var tool : system.toolsAdded()) {
                definitions.put(tool.name(), tool);
            }
        }
        return List.copyOf(definitions.values());
    }

    /**
     * pi {@code transcript.ts:140-142} —— 两个工具是否对模型宣告了**同一个接口**。
     *
     * <p>判据逐字对齐 pi：两侧都先过 {@link #toToolDeclaration}，再比**归一后的序列化串**
     * （{@code JSON.stringify(left) === JSON.stringify(right)}）。pi 的注释解释了为什么用
     * 序列化而不是深比较：投影会丢掉 typebox 的 symbol 键与 {@code undefined} 字段，
     * 并且让两侧键序一致 ⇒ 精确、且不引深比较依赖。</p>
     *
     * <p>⚠️ <b>已知与 pi 的一处不可对齐</b>（{@code docs/51 §3 F7}、§9 R4）：pi 的
     * {@code JSON.stringify} 对 {@code parameters} 的**嵌套键序**敏感，而 java 的
     * {@code ToolDefinition.inputSchema}/{@code ToolDeclaration.parameters} 经
     * {@code Map.copyOf} 之后插入序**已经丢了**（迭代序由哈希与每 JVM 的盐决定）
     * ⇒ 根本无「键序」可比。这里的归一因此把嵌套 Map 也**按键排序**
     * （{@code ORDER_MAP_ENTRIES_BY_KEYS}），换来确定性。两者只在「仅键序不同」时结论分叉
     * —— 那个输入在 java 侧已经构造不出来。</p>
     */
    public static boolean declarationsEqual(ToolDefinition left, ToolDefinition right) {
        return declarationJson(toToolDeclaration(left)).equals(declarationJson(toToolDeclaration(right)));
    }

    /**
     * pi {@code transcript.ts:151-167} —— 两次完整状态的差分。
     *
     * <p>「定义变了」在两侧各记一次：当前侧算**加**、此前侧算**删**（{@code docs/51 §2 P4}）。
     * 两侧的顺序分别跟 {@code current} / {@code previous}。</p>
     */
    public static ToolStateChanges getToolStateChanges(List<ToolDefinition> previous,
                                                       List<ToolDefinition> current) {
        var previousByName = new LinkedHashMap<String, ToolDefinition>();
        for (var tool : previous) {
            previousByName.put(tool.name(), tool);
        }
        var currentByName = new LinkedHashMap<String, ToolDefinition>();
        for (var tool : current) {
            currentByName.put(tool.name(), tool);
        }
        var added = new ArrayList<ToolDeclaration>();
        for (var tool : current) {
            var priorTool = previousByName.get(tool.name());
            if (priorTool == null || !declarationsEqual(priorTool, tool)) {
                added.add(toToolDeclaration(tool));
            }
        }
        var removed = new ArrayList<ToolReference>();
        for (var tool : previous) {
            var currentTool = currentByName.get(tool.name());
            if (currentTool == null || !declarationsEqual(tool, currentTool)) {
                removed.add(new ToolReference(tool.name()));
            }
        }
        return new ToolStateChanges(added, removed);
    }

    /**
     * pi {@code transcript.ts:179-194} —— 同名的工具被声明了两次**且定义不同**。
     *
     * <p>按名引用工具的车道（Anthropic 的 {@code tool_addition}/{@code tool_removal} 块）
     * 表达不了这件事 ⇒ 那是它们的<b>回退判据之一</b>（{@code docs/51 §2 P8}）。</p>
     */
    public static boolean hasToolRedefinitions(List<Message> messages) {
        var declared = new LinkedHashMap<String, ToolDefinition>();
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            for (var tool : system.toolsAdded()) {
                var previousTool = declared.get(tool.name());
                if (previousTool != null && !declarationsEqual(previousTool, tool)) {
                    return true;
                }
                declared.put(tool.name(), tool);
            }
        }
        return false;
    }

    /**
     * pi {@code transcript.ts:196-208} —— 这段工具史里存在**删除**或**同名重声明**
     * （即「只加」不足以重放它）。
     *
     * <p>这是「能不能就地锚定增量」的总闸：一旦为真，车道必须发**完整的当前工具表**。</p>
     */
    public static boolean hasNonAdditiveToolChanges(List<Message> messages) {
        var declared = new HashSet<String>();
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            if (!system.toolsRemoved().isEmpty()) {
                return true;
            }
            for (var tool : system.toolsAdded()) {
                if (!declared.add(tool.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * pi {@code transcript.ts:220-234} —— 把工具声明切分给「顶层请求字段」与「就地锚定」。
     *
     * <p>{@code anchorsAdditions = supportsToolAdditions && !hasNonAdditiveToolChanges(messages)}
     * —— 车道侧还要再与「支持中途系统消息」相与（pi 在三条车道各写一次那个式子）。</p>
     *
     * <p>⚠️ {@code anchorsAdditions} 为真时 {@code requestTools} **只是前导消息声明的那一份**，
     * 后续增量不在请求级字段里 —— 它们靠车道的就地锚定发出去（{@code docs/51 §2 P3}）。</p>
     */
    public static TranscriptTools resolveTranscriptTools(List<Message> messages,
                                                         boolean supportsToolAdditions) {
        boolean anchorsAdditions = supportsToolAdditions && !hasNonAdditiveToolChanges(messages);
        List<ToolDefinition> requestTools;
        if (anchorsAdditions) {
            var head = getInitialSystemMessage(messages);
            requestTools = head == null ? List.of() : head.toolsAdded();
        } else {
            requestTools = getCurrentTools(messages);
        }
        return new TranscriptTools(requestTools, anchorsAdditions);
    }

    /**
     * 稳定序列化器 —— {@link #declarationsEqual} 的判据载体，逐字对应 pi 的
     * {@code JSON.stringify}。
     *
     * <p>顶层三键的顺序由 {@link ToolDeclaration} 的 record 组件序固定；嵌套的
     * {@code parameters} 用 {@code ORDER_MAP_ENTRIES_BY_KEYS} 按键排序（理由见
     * {@code declarationsEqual} 的 javadoc）。</p>
     */
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static String declarationJson(ToolDeclaration declaration) {
        try {
            return CANONICAL_JSON.writeValueAsString(declaration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool declaration is not serializable", e);
        }
    }
}
