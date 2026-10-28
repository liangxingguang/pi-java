package com.pijava.coding.agent.rpc;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.QueueMode;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.SessionTreeNode;

/**
 * RPC 状态/查询载荷构建族（批 29＝docs/41 结构债清空批，自 {@link RpcDispatcher}
 * 纯移动拆出；行级注释逐字随迁）。
 *
 * <p>无状态工具类：{@code AgentSession} 一律入参（dispatcher 的 session 字段会被
 * new_session/fork/clone 重绑）。</p>
 */
final class RpcSessionPayloads {

    private RpcSessionPayloads() {
    }

    // ── get_state / get_messages 载荷 ────────────────────────────────────

    static RpcSessionState buildState(AgentSession session, boolean streaming) {
        var harness = session.harness();
        var model = harness.getModel();
        // B223（原 docs/47，pi rpc-mode.ts:459）：读 settings getter（?? true 默认开），
        // 不再读引擎内存态（旧实现全新会话恒报 false）。
        boolean autoCompactionEnabled =
            session.services().settings().compaction().getCompactionEnabled();
        // 批 20（B31，pi rpc-mode.ts:450）：model 发整个 Model 对象（目录解析；
        // 未命中 ⇒ ModelInfo.minimal 容忍形状，窄差 D121），无模型 ⇒ 键省略
        //（pi undefined 同形；旧 "" 修真）。
        RpcModel wireModel = null;
        if (model != null) {
            // 批 20 评审回补（I-4）：读会话**装配期**目录快照（pi 内存注册表对位）
            // ——ModelsJsonConfig.allModels() 逐调用重读重解析 models.json：每次
            // get_state 一份盘读＋全目录解析，且文件损坏会让 get_state 整个失败
            // （pi 读内存运行时模型，无此失败面；真 home 继承亦随快照收敛到装配点）。
            var info = session.modelCatalog().find(model)
                .orElseGet(() -> com.pijava.ai.catalog.ModelInfo.minimal(model));
            wireModel = RpcModel.of(info, providerConfigOf(session, model.provider()),
                RpcExportSupport.mapper());
        }
        // 逐字段取自会话，与 pi rpc-mode.ts:450-465 的取值表一一对应（包④，原 docs/31 §8.37）。
        // 此前 isCompacting / sessionFile / sessionId / pendingMessageCount 是写死的
        // false / null / null / 0。
        // 批 20：sessionName 读 stored（B32——无 session_info ⇒ 键省略，pi
        // getSessionName 同形）；messageCount 改工作副本口径（B33——pi
        // agent.state.messages.length，压缩后与转录分叉正是要义）。
        return new RpcSessionState(
            wireModel,
            thinkingWire(harness.getThinkingLevel()),
            streaming,
            session.isCompacting(),
            queueWire(harness.steeringMode()),
            queueWire(harness.followUpMode()),
            session.sessionFile(),
            session.sessionId(),
            session.storedSessionName(),
            autoCompactionEnabled,
            harness.getMessages().size(),
            session.pendingMessageCount());
    }

    /**
     * 批 20（B31/T6 共用）：provider 的静态配置（api/baseUrl 缺省源＝pi
     * {@code model.baseUrl} 的目录值语义——<b>不取</b> CLI/settings 覆盖，裁决⑥）。
     * 非 {@link com.pijava.ai.provider.ConfigurableProvider}（测试 FauxProvider 等）
     * ⇒ {@code null}（两键省略）。
     */
    static com.pijava.ai.provider.ProviderConfig providerConfigOf(
            AgentSession session, String providerName) {
        return session.services().providers().get(providerName)
            .filter(com.pijava.ai.provider.ConfigurableProvider.class::isInstance)
            .map(p -> ((com.pijava.ai.provider.ConfigurableProvider) p).providerConfig())
            .orElse(null);
    }

    static List<Message> buildMessages(AgentSession session) {
        var transcript = harnessTranscript(session);
        return transcript.stream()
            .filter(e -> e instanceof com.pijava.agent.entry.Entry.Message m)
            .map(e -> ((com.pijava.agent.entry.Entry.Message) e).message())
            .toList();
    }

    static List<com.pijava.agent.entry.Entry> harnessTranscript(AgentSession session) {
        return session.harness().snapshot(session.laneName()).transcript();
    }

    // ── 次批辅助 ─────────────────────────────────────────────────────────

    /** 解析模型模式（"provider/model" 或纯 modelName）→ ModelId。 */
    static ModelId<?> resolveModel(String pattern) {
        var models = com.pijava.ai.provider.ModelsJsonConfig.allModels().listModels();
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("model pattern required");
        }
        if (p.contains("/")) {
            var parts = p.split("/", 2);
            return models.stream().map(com.pijava.ai.catalog.ModelInfo::id)
                .filter(id -> id.provider().equals(parts[0])
                    && id.modelName().equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + pattern));
        }
        return models.stream().map(com.pijava.ai.catalog.ModelInfo::id)
            .filter(id -> id.modelName().equals(p))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + pattern));
    }

    static ModelThinkingLevel cycleThinking(AgentSession session) {
        var levels = ThinkingLevel.ordered();
        ModelThinkingLevel current = session.harness().getThinkingLevel();
        int idx = current instanceof ModelThinkingLevel.Enabled e
            ? levels.indexOf(e.level()) : -1;
        return ModelThinkingLevel.of(levels.get((idx + 1) % levels.size()));
    }

    static List<String> availableModels() {
        return com.pijava.ai.provider.ModelsJsonConfig.allModels()
            .listModels().stream()
            .map(m -> m.id().provider() + "/" + m.id().modelName())
            .sorted().toList();
    }

    static List<String> availableThinkingLevels() {
        return ThinkingLevel.ordered().stream()
            .map(ThinkingLevel::label).toList();
    }

    static RpcSessionStats buildStats(AgentSession session) {
        var snapshot = session.watchSession().current();
        ModelId<?> model = session.harness().getModel();
        return new RpcSessionStats(
            model == null ? "" : model.provider() + "/" + model.modelName(),
            snapshot == null ? 0 : snapshot.totalTokens(),
            snapshot == null ? 0 : snapshot.turnCount(),
            (int) session.entryCount(),
            snapshot == null ? "idle" : snapshot.phase());
    }

    static List<RpcSlashCommand> buildCommands(AgentSession session) {
        var commands = new ArrayList<RpcSlashCommand>();
        // 批 23（docs/34 T4，B221⑥＋销 D5）：扩展臂第一——pi rpc-mode.ts:684-690
        // 对位；name 用撞名 {:N} 编号后的 invocationName，sourceInfo 注册期盖章。
        for (var command : session.services().slashCommands().resolvedExtensionCommands()) {
            commands.add(new RpcSlashCommand(
                command.invocationName(), command.command().description(),
                RpcSlashCommand.Source.EXTENSION, command.sourceInfo()));
        }
        // B247（pi rpc-mode.ts:691-697）：模板臂。sourceInfo 由加载期打标
        //（B247 §3.2）；手造实例无 sourceInfo ⇒ 组件 null、线格式省略
        //（pi 恒有，此臂仅测试/手工注册路）。
        // 内置不收（pi 语义＝可经 prompt 调用，rpc-types.ts:73-74）。
        for (var template : session.services().promptTemplates().all()) {
            commands.add(new RpcSlashCommand(
                template.name(), template.description(),
                RpcSlashCommand.Source.PROMPT, template.sourceInfo()));
        }
        // B244/B247（pi rpc-mode.ts:699-707）：skill 臂，不受
        // enableSkillCommands 门（pi get_commands 无门——开关只管交互补全）。
        for (var skill : session.harness().skillManager().all()) {
            commands.add(new RpcSlashCommand(
                "skill:" + skill.name(), skill.description(),
                RpcSlashCommand.Source.SKILL,
                com.pijava.coding.agent.core.source.SourceInfos.fromSkill(skill)));
        }
        return commands;
    }

    // 批 23（T4）：skill 折算上移共享件 core.source.SourceInfos.fromSkill
    //（扩展 ctx.getCommands() 同消费——pi 两处同一规则）。

    /**
     * 线格式的思考级别（{@code "off"|"minimal"|…|"max"}）。
     *
     * <p>包H5：改用 {@link ModelThinkingLevel#label()} —— 它逐字对齐 pi 的字面量
     * （{@code types.ts:84-85}）。此前是手写 switch，且 {@code "max"} 缺分支
     * （java 那时只有 5 级、把 max 并进 xhigh）。</p>
     */
    static String thinkingWire(ModelThinkingLevel level) {
        return level == null ? "off" : level.label();
    }

    static String queueWire(QueueMode mode) {
        return mode instanceof QueueMode.All ? "all" : "one-at-a-time";
    }

    // ── 末批（P6-5d）辅助 ──────────────────────────────────────────────

    /** 线格式 "all" | "one-at-a-time" → QueueMode（对齐 pi QueueMode 判别）。 */
    static QueueMode fromWire(String mode) {
        return "all".equals(mode) ? new QueueMode.All() : new QueueMode.OneAtATime();
    }

    /** 当前主 lane 的 leaf entry id（末条 transcript entry），空会话为 null。 */
    static String leafId(AgentSession session) {
        var entries = harnessTranscript(session);
        return entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
    }

    /** 按 entry id 找索引（get_entries since 过滤）；找不到回 -1。 */
    static int indexOfEntry(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** {@link SessionTreeNode} → RPC 线形浅映射（label 从此有值）。 */
    static List<RpcPayloads.RpcSessionTreeNode> toRpcTree(List<SessionTreeNode> nodes) {
        return nodes.stream()
            .map(n -> new RpcPayloads.RpcSessionTreeNode(
                n.entry(), toRpcTree(n.children()), n.label()))
            .toList();
    }
}
