package com.pijava.coding.agent.extension.event;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.CompactionAbortToken;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.extension.event.ExtensionResults.BoundaryContextPreview;
import com.pijava.coding.agent.extension.event.ExtensionResults.CompactionPreparation;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBoundaryDraft;
import com.pijava.coding.agent.extension.event.ExtensionResults.TreePreparation;

/**
 * pi {@code ExtensionEvent} 联合（types.ts:1377-1409）的对齐子集 —— docs/14 的 18 个
 * ＋ docs/15（批 5）的 11 个结果臂/生命周期事件 ＋ docs/17（批 7）provider 请求面
 * ＋ docs/18（批 8）tree 两事件。剩余随各批逐批扩。
 *
 * <p>两族语义（pi runner.ts）：</p>
 * <ul>
 *   <li><b>引擎钩子族</b>（{@link ContextEvent}/{@link ContextWithSystemEvent}/
 *       {@link ToolCallEvent}/{@link ToolResultEvent}）—— 结果型：处理器可返回结果对象
 *       改写行为（合并语义见 {@code EngineHookBridge}，pi runner.ts:1183-1359 逐字）。</li>
 *   <li><b>宿主事件族</b>（其余）—— 观察型（返回值被忽略）或结果臂型
 *       （{@code message_end}/boundary/before 族/input/user_bash，语义见各记录与
 *       {@code SessionEventFirer}/{@code EngineHookBridge}）。</li>
 * </ul>
 *
 * <p>变体携带不同字段 ⇒ sealed interface + record（CLAUDE.md 规范）。
 * {@code null ≙ pi 的 undefined}。结果类型全部在 {@link ExtensionResults}。</p>
 */
public sealed interface ExtensionEvent {

    // ═══════════════════════════════════════════════════════════
    // 引擎钩子族（结果型）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code ContextEvent}（types.ts:871）。{@code messages} 是**活的可变列表**
     * （裁决④：pi 处理器可原地改 {@code event.messages}，{@code runner.ts:1313} 注释
     * 同构）；系统消息已被滤除，处理器看不到。
     */
    record ContextEvent(List<Message> messages) implements ExtensionEvent {}

    /**
     * pi {@code ContextWithSystemEvent}（types.ts:882）。{@code messages} 含系统消息
     * （全量，原地可变同 {@link ContextEvent}）。
     */
    record ContextWithSystemEvent(List<Message> messages) implements ExtensionEvent {}

    /**
     * pi {@code ToolCallEvent}（types.ts:1165-1228）。{@code input} 原地可变
     * （pi types.ts:1224-1227 注释：后面的处理器看得到前面的改写）。
     * java 不做 pi 的逐工具判别子类型（{@code BashToolCallEvent} 等 9 支），
     * {@code input} 恒 map 形（窄差 D2）；{@code parentToolCallId} 不带（G-10 零对应，D7）。
     */
    record ToolCallEvent(String toolCallId, String toolName,
                         Map<String, Object> input) implements ExtensionEvent {}

    /**
     * pi {@code ToolResultEvent}（types.ts:1239-1316）。{@code input} 是改写后参数
     * （pi 的 {@code prepared.args}）。
     */
    record ToolResultEvent(String toolCallId, String toolName, Map<String, Object> input,
                           List<ContentBlock> content, Object details, Object structuredContent,
                           boolean isError, ToolResult.UsageInfo usage)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 宿主事件族（观察型，docs/14）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code agent_start}（types.ts:929，无载荷）。 */
    record AgentStartEvent() implements ExtensionEvent {}

    /** pi {@code agent_end}（types.ts:934）。 */
    record AgentEndEvent(List<Message> messages) implements ExtensionEvent {}

    /** pi {@code agent_settled}（types.ts:956-960 族；aborted 为两锚点间新增字段）。 */
    record AgentSettledEvent(boolean aborted) implements ExtensionEvent {}

    /** pi {@code turn_start}（types.ts:1030）。 */
    record TurnStartEvent(int turnIndex, long timestamp) implements ExtensionEvent {}

    /** pi {@code message_start}（types.ts:1046）。 */
    record MessageStartEvent(Message message) implements ExtensionEvent {}

    /** pi {@code message_update}（types.ts:1052）：partial 快照＋底层流事件。 */
    record MessageUpdateEvent(Message message, StreamEvent assistantMessageEvent)
            implements ExtensionEvent {}

    /**
     * pi {@code message_end}（types.ts:1062）——docs/15 起结果臂落地
     * （{@code MessageEndEventResult} 替换语义见 {@code EngineHookBridge} 的
     * sink 适配器，裁决①）；观察订阅与结果订阅同批火一次。
     */
    record MessageEndEvent(Message message) implements ExtensionEvent {}

    /** pi {@code tool_execution_start}（types.ts:1067）。 */
    record ToolExecutionStartEvent(String toolCallId, String toolName,
                                   Map<String, Object> args) implements ExtensionEvent {}

    /** pi {@code tool_execution_update}（types.ts:1076）。 */
    record ToolExecutionUpdateEvent(String toolCallId, String toolName,
                                    Map<String, Object> args, Object partialResult)
            implements ExtensionEvent {}

    /**
     * pi {@code tool_execution_end}（types.ts:1086-1097 逐字段：toolCallId/toolName/result/
     * isError/durationMs?——⚠️ **无 {@code args}**，pi 的 end 是独立 interface，不继承
     * start/update 的字段；{@code durationMs} 可空 ≙ 键省略）。
     */
    record ToolExecutionEndEvent(String toolCallId, String toolName,
                                 Object result, boolean isError, Long durationMs)
            implements ExtensionEvent {}

    /**
     * pi {@code session_compact}（types.ts:787-795）——docs/15 起带
     * {@code compactionEntry}（落库的同一条目，窄差 D4 销号）；
     * {@code fromExtension} 读条目的 {@code fromHook}（D6 销号）。
     */
    record SessionCompactEvent(String reason, boolean willRetry, boolean fromExtension,
                               Entry.Compaction compactionEntry) implements ExtensionEvent {}

    /** pi {@code session_compact_failed}（types.ts:799）。 */
    record SessionCompactFailedEvent(String reason, String errorMessage, boolean aborted,
                                     boolean willRetry, boolean fromExtension)
            implements ExtensionEvent {}

    /** pi {@code session_info_changed}（types.ts:750；{@code name} 为 null ≙ undefined）。 */
    record SessionInfoChangedEvent(String name) implements ExtensionEvent {}

    /** pi {@code thinking_level_select}（types.ts:1114-1118）。 */
    record ThinkingLevelSelectEvent(ModelThinkingLevel level, ModelThinkingLevel previousLevel)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 生命周期（docs/15，pi types.ts:740-819）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code session_start}（types.ts:740-748）——reason 五级：
     * {@code "startup"|"reload"|"new"|"resume"|"fork"}（初始装配恒 startup，CLI resume
     * 也是 —— pi 逐字；reload 臂随 B246）。{@code previousSessionFile} 装会话 <b>id</b>
     * （裁决⑤，new/resume/fork 时有值）。
     */
    record SessionStartEvent(String reason, String previousSessionFile)
            implements ExtensionEvent {}

    /**
     * pi {@code session_shutdown}（types.ts:812-819）——reason：
     * {@code "quit"|"reload"|"new"|"resume"|"fork"}；{@code targetSessionFile}
     * 装目标会话 id（替换时有值）。
     */
    record SessionShutdownEvent(String reason, String targetSessionFile)
            implements ExtensionEvent {}

    /**
     * pi {@code session_before_switch}（types.ts:756-762，可取消）——reason：
     * {@code "new"|"resume"}；{@code targetSessionFile} 装目标会话 id（new 臂为 null）。
     */
    record SessionBeforeSwitchEvent(String reason, String targetSessionFile)
            implements ExtensionEvent {}

    /**
     * pi {@code session_before_fork}（types.ts:764-769，可取消）——
     * {@code position}: {@code "before"|"at"}；{@code forkCopy}（整枝拷贝）映射
     * {@code {entryId:当前叶, position:"at"}}（窄差 D17）。
     */
    record SessionBeforeForkEvent(String entryId, String position) implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 压缩（docs/15，pi types.ts:771-784）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code SessionBeforeCompactEvent}（types.ts:771-784，可取消/可接管）——
     * {@code preparation} 是 {@code CompactionService.Plan} 的视图（无 fileOps，窄差 D22）；
     * {@code branchEntries} 是当前 branch 条目；{@code signal} 是 per-operation 取消令牌
     * （pi {@code AbortSignal} 对位）。
     */
    record SessionBeforeCompactEvent(CompactionPreparation preparation,
                                     List<Entry> branchEntries,
                                     String customInstructions, String reason,
                                     boolean willRetry, CompactionAbortToken signal)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // tree 导航（docs/18，pi types.ts:832-846）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code session_before_tree}（types.ts:832-838，可取消）——{@code signal}
     * 是分支摘要中止令牌（pi AbortSignal 对位 {@link CompactionAbortToken}，
     * 与 {@link SessionBeforeCompactEvent} 同型）。
     */
    record SessionBeforeTreeEvent(TreePreparation preparation,
                                  CompactionAbortToken signal) implements ExtensionEvent {}

    /**
     * pi {@code session_tree}（types.ts:840-846，观察型）——{@code summaryEntry}/
     * {@code fromExtension} 可空；fromExtension 仅在有摘要时有值（pi :4145）。
     */
    record SessionTreeEvent(String newLeafId, String oldLeafId,
                            Entry.BranchSummary summaryEntry, Boolean fromExtension)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 输入与 bash（docs/15，pi types.ts:1120-1160）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code InputEvent}（types.ts:1143-1154）——{@code images} 恒 null
     * （java prompt 无图片面，窄差 D12）；{@code source}:
     * {@code "interactive"|"rpc"|"extension"}（extension 无生产者，E5-12 在册）；
     * {@code streamingBehavior}: {@code "steer"|"followUp"}，空闲时 null。
     */
    record InputEvent(String text, List<ContentBlock> images, String source,
                      String streamingBehavior) implements ExtensionEvent {}

    /** pi {@code UserBashEvent}（types.ts:1125-1134）。 */
    record UserBashEvent(String command, boolean excludeFromContext, String cwd)
            implements ExtensionEvent {}

    /**
     * pi {@code BeforeAgentStartEvent}（types.ts:917-928）——{@code systemPrompt}
     * 是当前生效值（渲染后）。
     *
     * <p>批 16（T3c，裁决⑪）：新增 {@code sections}——pi
     * {@code systemPromptOptions.sections} 的可变语义直译：record 组件但持<b>可变
     * LinkedHashMap</b>，同一对象贯穿所有处理器（后者见前者改动，
     * runner.ts:1425-1471）；firer 建基线拷贝传入、结束后读回写 harness。
     * record 的不可变惯例在此让位逐字语义。原 {@code systemPromptOptions} 其余
     * 部分不移植（窄差 D11）。</p>
     */
    record BeforeAgentStartEvent(String prompt, String systemPrompt,
                                 java.util.Map<String, String> sections)
            implements ExtensionEvent {}

    /**
     * 批 16（T6a）：pi {@code ModelSelectEvent}（types.ts:1103-1111）——
     * {@code source} ∈ {@code "set"|"cycle"|"restore"}（"restore" pi 本锚点亦无
     * 发射点，仅声明）；同模型（equals 门，pi modelsAreEqual 对位）不发射。
     */
    record ModelSelectEvent(com.pijava.ai.model.ModelId<?> model,
                            com.pijava.ai.model.ModelId<?> previousModel,
                            String source) implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // boundary 族（docs/15，pi types.ts:1000-1044）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code TurnEndEvent}（types.ts:1037-1044）＋{@code BoundaryState}（:987-992）——
     * 键名 {@code continue} ⇒ 组件名 {@code shouldContinue}（关键字规避）；
     * {@code outcome}: {@code "completed"|"aborted"|"error"}（:942）。
     */
    record TurnEndEvent(int turnIndex, Message.AssistantMessage message,
                        List<Message.ToolResultMessage> toolResults,
                        String messageEntryId, List<String> toolResultEntryIds,
                        List<SessionBoundaryDraft> entries, boolean shouldContinue,
                        BoundaryContextPreview context, String outcome)
            implements ExtensionEvent {}

    /** pi {@code AgentBeforeSettleEvent}（types.ts:1000-1002）＋{@code BoundaryState}。 */
    record AgentBeforeSettleEvent(List<SessionBoundaryDraft> entries, boolean shouldContinue,
                                  BoundaryContextPreview context, String outcome)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // provider 请求面（docs/17，pi types.ts:885-914）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code BeforeProviderRequestEvent}（types.ts:887-891）——{@code payload} 是
     * <b>车道原生对象</b>（SDK params 类／{@code Map}／{@code ObjectNode}／
     * {@code GooglePayload}；裁决⑤）。结果型：处理器返回非 null ⇒ 替换当前载荷
     * （pi runner.ts:1360-1392 的链式语义，后处理器见到前值）。
     */
    record BeforeProviderRequestEvent(Object payload) implements ExtensionEvent {}

    /**
     * pi {@code BeforeProviderHeadersEvent}（types.ts:893-902）——{@code headers} 是
     * <b>可变</b> map（原位改写，返回值被忽略；{@code null} 值＝删除该头，裁决⑥）。
     * 头集＝models.json 三层（provider⨁model⨁override）合并值（窄差 D33）。
     */
    record BeforeProviderHeadersEvent(Map<String, String> headers) implements ExtensionEvent {}

    /** pi {@code AfterProviderResponseEvent}（types.ts:904-908）——观察型。 */
    record AfterProviderResponseEvent(int status, Map<String, String> headers)
            implements ExtensionEvent {}

    /**
     * pi {@code ProviderStreamEvent}（types.ts:910-914）——{@code data} 是适配器所有的
     * 原始事件对象（<b>只读</b>）；{@code api} 在目录未命中（ModelInfo.minimal）时为
     * {@code null}（pi 的必填字段在 java 数据面缺席）。
     */
    record ProviderStreamEvent(String provider, String api, String model, Object data)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // ui_prompt 事件对（docs/28 批 17 T2，pi types.ts:1012-1030）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code UIPromptStartEvent}（types.ts:1018-1024）——五个阻塞 UI 方法
     * （select/confirm/input/editor/custom）被调用时发射；带深度计数，嵌套只发
     * 最外层。{@code reason} 恒 {@code "ui_prompt"}（同既有 spec 惯例不列字段）；
     * {@code kind} ∈ {@code "select"|"confirm"|"input"|"editor"|"custom"}
     * （pi {@code UIPromptKind} 五字面量，types.ts:1012）；{@code title} null ≙ 键省略。
     */
    record UiPromptStartEvent(String kind, String title) implements ExtensionEvent {}

    /** pi {@code UIPromptEndEvent}（types.ts:1025-1030）——深度归零时发，kind/title 取最外层记录值。 */
    record UiPromptEndEvent(String kind, String title) implements ExtensionEvent {}

    /**
     * pi {@code CacheWarmingDecisionEvent}（cache-warmer.ts:112-115，批 13）——
     * 每次暖刷新前问扩展：四键逐字（{@code type} 由
     * {@link ExtensionEventSpec#CACHE_WARMING_DECISION} 承载）。
     * {@code action} 是 pi 自己的决策，扩展经结果覆写（last override wins）。
     */
    record CacheWarmingDecisionEvent(double warmCost, double missCost,
                                     double continuationProbability,
                                     com.pijava.coding.agent.core.CacheWarming.Action action)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // MCP / 信任（docs/34 批 23）
    // ═══════════════════════════════════════════════════════════

    /**
     * 批 23（T2）：pi {@code mcp_servers_change}（types.ts:728-733）——扩展在装配后
     * 注册/注销 MCP 服务器时广播；载荷是变更后的<b>全量</b>注册列表
     * （{@code servers: runtime.mcpServers.list()}）。订阅此事件＝声明本扩展负责
     * 连接已注册服务器（无订阅者时注册方收扩展错误——runner.ts:749-765）。
     */
    record McpServersChangeEvent(
            java.util.List<com.pijava.mcp.config.RegisteredMcpServer> servers)
            implements ExtensionEvent {}

    /**
     * 批 23（T3）：pi {@code project_trust}（types.ts:685-689）——启动信任解析
     * 询问扩展（first-wins：首个返回非 undecided 的处理器胜出）。在会话装配前
     * 的预信任装载期发射（pi resource-loader.ts:505-525）。
     */
    record ProjectTrustEvent(String cwd) implements ExtensionEvent {}
}
