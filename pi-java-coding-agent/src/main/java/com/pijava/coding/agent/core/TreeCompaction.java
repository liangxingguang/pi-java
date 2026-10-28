package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryQuery;
import com.pijava.coding.agent.compaction.BranchSummarization;
import com.pijava.coding.agent.core.AgentSession.TreeNavigation;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的压缩/树导航簇 —— compact×3、
 * navigateTree（含分支摘要生成）、abortBranchSummary、textOf×2、树/标签读面
 * （getTree/getLabel/setLabel）、bug 摘要出口，以及 branchSummarization／
 * modelResolver／branchSummaryAbort／bugReportSummarizer 四字段。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class TreeCompaction {

    private final AgentSession owner;

    // B1（map #24/#25）：分支摘要生成器与模型解析器（create 装配）。
    private com.pijava.coding.agent.compaction.BranchSummarization branchSummarization;
    /** 批 15（T3b）：bug 摘要生成器（wireSession 公共路径装配，fork 自动重装）。 */
    private com.pijava.coding.agent.core.bugreport.BugReportSummarizer bugReportSummarizer;
    private com.pijava.ai.model.DefaultModelResolver modelResolver;
    /** pi {@code _branchSummaryAbortController}（agent-session.ts:4018 创建/:4155 清）。 */
    private volatile com.pijava.agent.harness.CompactionAbortToken branchSummaryAbort;

    TreeCompaction(AgentSession owner) {
        this.owner = owner;
    }

    /** 装配期写面（{@link SessionWiring}）。 */
    void branchSummarization(BranchSummarization summarization) {
        this.branchSummarization = summarization;
    }

    /** 装配期写面（{@link SessionWiring}）。 */
    void modelResolver(com.pijava.ai.model.DefaultModelResolver resolver) {
        this.modelResolver = resolver;
    }

    /** 装配期写面（{@link SessionWiring}）。 */
    void bugReportSummarizer(
            com.pijava.coding.agent.core.bugreport.BugReportSummarizer summarizer) {
        this.bugReportSummarizer = summarizer;
    }

    /**
     * 是否有压缩/分支摘要在飞（pi {@code AgentSession.isCompacting}，
     * {@code agent-session.ts:1622-1630}：读三个专用中止控制器，<b>含
     * {@code _branchSummaryAbortController}</b>——摘要在飞即「压缩或树导航进行中」，
     * 双门第二句错文的互斥语义由此承重）。
     *
     * <p>压缩两路窗口由引擎置/清（{@code AgentHarness.isCompacting(String)}）；
     * 分支摘要窗口＝{@link #branchSummaryAbort} 非 null（navigateTree 的 try 期间，
     * volatile 字段）。消费者：RPC {@code get_state}、{@code InteractiveMode} 的
     * 提交门（A5，{@code 原 docs/25}）、{@link #navigateTree} 双门。</p>
     */
    boolean isCompacting() {
        return owner.harness().isCompacting(owner.laneName()) || branchSummaryAbort != null;
    }

    /** isCompacting 的分支摘要臂（批 8 评审 C1 承重互斥语义的读口）。 */
    com.pijava.agent.harness.CompactionAbortToken branchSummaryAbort() {
        return branchSummaryAbort;
    }

    /**
     * pi {@code AgentSession.compact(customInstructions)}（agent-session.ts:2898-2907，
     * B223/{@code 原 docs/47}）：手动压缩的预算按<b>当前模型</b>现解析
     * （modelOverrides 对手动路同样生效）；enabled 不门手动路
     * （pi 手动 compact 不检查 enabled，关自动压缩时 /compact 仍可用）。
     */
    com.pijava.agent.compaction.CompactionResult compact(String customInstructions) {
        owner.prompts().abortAndWaitForIdle();
        var resolved = owner.services().settings().compaction()
            .getCompactionSettings(owner.harness().getModel());
        return owner.harness().compact(owner.laneName(), resolved, customInstructions);
    }

    /** Trigger a manual context compaction without customInstructions ({@code /compact}). */
    com.pijava.agent.compaction.CompactionResult compact(CompactionSettings settings) {
        return compact(settings, null);
    }

    /**
     * Trigger a manual context compaction with customInstructions (B174,
     * {@code docs/23}；pi {@code AgentSession.compact(customInstructions)}).
     */
    com.pijava.agent.compaction.CompactionResult compact(
            CompactionSettings settings, String customInstructions) {
        owner.prompts().abortAndWaitForIdle();
        return owner.harness().compact(owner.laneName(), settings, customInstructions);
    }

    // ── navigateTree（pi agent-session.ts:3964-4156，B1，map #25）──────

    /**
     * pi {@code navigateTree}（agent-session.ts:3964-4156）逐字序（B1 map #25 ＋
     * docs/18 B245 事件面）。
     *
     * <p>双门错文逐字（:3977-3982）；目标 = 当前叶 ⇒ 不动；模型门
     * （:3985-3987）；preparation 载荷无条件先收集（:3995-3999，<b>活列表</b>与默认
     * 生成器共引用）；中止令牌在事件前建（:4018）；{@code session_before_tree}
     * 发射＋cancel 短路＋摘要/三覆盖臂（:4024-4051，扩展摘要仅在用户本来要摘要时
     * 生效）；默认生成器门（:4054）；user/custom_message 目标 ⇒ 新叶 = 父 ＋ 回填
     * editorText；有摘要 ⇒ {@code branchWithSummary}（fromHook＝fromExtension
     * 透传）；label 贴摘要 entry 或 targetId；{@code session_tree} 发射
     * （:4140-4147）；令牌 finally 清零（:4155）。</p>
     */
    TreeNavigation navigateTree(String targetId, boolean summarize,
                                String customInstructions, boolean replaceInstructions,
                                String label) {
        if (owner.isRunning()) {
            throw new IllegalStateException(
                "Wait for the current response to finish before navigating the session tree.");
        }
        if (owner.isCompacting()) {
            throw new IllegalStateException("Wait for the current compaction or tree navigation "
                + "to finish before navigating the session tree.");
        }
        if (owner.session() == null) {
            throw new IllegalStateException("Tree navigation requires a persistent session");
        }
        var tree = owner.session().view(owner.laneName());
        String oldLeafId = tree.getLeafId();
        if (targetId.equals(oldLeafId)) {
            return new TreeNavigation(false, false, null, null); // pi :3989-3991
        }
        // pi :3985-3987 模型门（错文逐字【模型可见】）。
        if (summarize && owner.harness().getModel() == null) {
            throw new IllegalStateException("No model available for summarization");
        }
        Entry target = tree.getEntry(targetId);
        if (target == null) {
            throw new IllegalStateException("Entry " + targetId + " not found");
        }
        // pi :3995-3999：无条件先收集（preparation 载荷＋默认生成器输入共一份
        // 活列表——pi 处理器原地改它会影响摘要输入，:3995/4056 同构）。
        var collected = BranchSummarization.collectEntries(oldLeafId, targetId, owner.session());
        var entriesToSummarize = new java.util.ArrayList<>(collected.entries());
        // pi :4006-4016：preparation 三覆盖先取入参值。
        String effectiveInstructions = customInstructions;
        boolean effectiveReplace = replaceInstructions;
        String effectiveLabel = label;

        branchSummaryAbort = new com.pijava.agent.harness.CompactionAbortToken(); // pi :4018
        try {
            String summaryText = null;
            com.pijava.ai.Usage summaryUsage = null;
            java.util.Map<String, Object> summaryDetails = null;
            boolean fromExtension = false;
            // pi 的 !extensionSummary 生成器门（:4054）看的是 summary 对象有无，
            // 不看文本是否为空（终审②）——退化输入（null/空串文本）同样跳过默认生成器。
            boolean hasExtensionSummary = false;
            // pi :4024-4051：session_before_tree 发射＋cancel 短路＋摘要/三覆盖臂。
            if (owner.extensionFirer() != null) {
                var preparation =
                    new com.pijava.coding.agent.extension.event.ExtensionResults.TreePreparation(
                        targetId, oldLeafId, collected.commonAncestorId(), entriesToSummarize,
                        summarize, customInstructions, replaceInstructions, label);
                var r = owner.extensionFirer().sessionBeforeTree(preparation, branchSummaryAbort);
                if (r != null) {
                    if (Boolean.TRUE.equals(r.cancel())) {
                        return new TreeNavigation(true, false, null, null); // pi :4032
                    }
                    if (r.summary() != null && summarize) { // pi :4036 summarize 门
                        summaryText = r.summary().summary();
                        summaryDetails = mapDetails(r.summary().details());
                        summaryUsage = r.summary().usage();
                        fromExtension = true;
                        hasExtensionSummary = true;
                    }
                    // 三覆盖不受 summarize 门限制（pi :4043-4051）。
                    if (r.customInstructions() != null) {
                        effectiveInstructions = r.customInstructions();
                    }
                    if (r.replaceInstructions() != null) {
                        effectiveReplace = r.replaceInstructions();
                    }
                    if (r.label() != null) {
                        effectiveLabel = r.label();
                    }
                }
            }
            // pi :4054-4085 默认生成器门：summarize && 非空 && 无扩展摘要。
            if (summarize && !entriesToSummarize.isEmpty() && !hasExtensionSummary) {
                // 预检（终审③）：token 已中止 ⇒ 不发 LLM 调用（pi 的
                // generateBranchSummary 对已 abort signal 直接返回 aborted 同效）。
                if (branchSummaryAbort.isAborted()) {
                    return new TreeNavigation(true, true, null, null);
                }
                var model = owner.harness().getModel();
                int contextWindow = modelResolver.contextWindow(model);
                if (contextWindow <= 0) {
                    contextWindow = 128_000; // pi :306 `model.contextWindow || 128000`
                }
                var outcome = branchSummarization.generate(entriesToSummarize,
                    new BranchSummarization.Options(model, contextWindow,
                        modelResolver.maxOutputTokens(model),
                        owner.services().settings().compaction().getBranchSummaryReserveTokens(),
                        effectiveInstructions, effectiveReplace));
                // 中止检查点：生成后、落库前（CompactionAbortToken 同款窄差——LLM
                // 流内不可中断，docs/09 §12.2#2）；pi 的 result.aborted 对位（:4069）。
                if (branchSummaryAbort.isAborted()) {
                    return new TreeNavigation(true, true, null, null);
                }
                switch (outcome) {
                    case BranchSummarization.Outcome.Aborted a -> {
                        return new TreeNavigation(true, true, null, null); // pi :4069
                    }
                    case BranchSummarization.Outcome.Failed f ->
                        throw new IllegalStateException(f.error());
                    case BranchSummarization.Outcome.Summary s -> {
                        summaryText = s.text();
                        summaryUsage = s.usage();
                        summaryDetails = BranchSummarization.detailsOf(
                            s.readFiles(), s.modifiedFiles());
                    }
                }
            } // 扩展摘要臂：text/details/usage 已在上面全取扩展值（pi :4082-4085）。

            // 新叶按目标类型（pi :4094-4103 逐字序）：user 消息/custom_message ⇒
            // 叶=父＋回填 editorText；其他 ⇒ 叶=targetId。
            String newLeafId;
            String editorText = null;
            if (target instanceof Entry.Message m && "user".equals(m.message().role())) {
                newLeafId = m.parentId();
                editorText = textOf(m.message().content());
            } else if (target instanceof Entry.CustomMessage cm) {
                newLeafId = cm.parentId();
                editorText = textOf(cm.content().toBlocks());
            } else {
                newLeafId = targetId;
            }

            // pi truthiness（终审②）：空串文本/标签与 null 同为 falsy
            // （if (summaryText) :4107、if (label) :4118、if (label && !summaryText) :4129）。
            boolean hasSummaryText = summaryText != null && !summaryText.isEmpty();
            boolean hasLabel = effectiveLabel != null && !effectiveLabel.isEmpty();
            Entry.BranchSummary summaryEntry = null;
            if (hasSummaryText) {
                // pi :4107-4115：摘要挂在目标位置（可在根），fromHook＝fromExtension 透传。
                summaryEntry = owner.session().branchWithSummary(owner.laneName(), newLeafId,
                    oldLeafId, summaryText, summaryDetails, fromExtension, summaryUsage);
                if (hasLabel) {
                    owner.session().setLabel(summaryEntry.id(), effectiveLabel);
                }
            } else if (newLeafId == null) {
                owner.session().resetLeaf(owner.laneName());
            } else {
                owner.session().moveLane(owner.laneName(), newLeafId);
            }
            if (hasLabel && !hasSummaryText) {
                owner.session().setLabel(targetId, effectiveLabel);
            }

            // pi :4136-4137（_refreshFinalizedContext/_restoreToolsFromTranscript）对位：
            // 从新叶重建车道上下文；已落盘 id 重标记，防 persistPending 复写。
            String leafId = summaryEntry != null ? summaryEntry.id() : newLeafId;
            List<Entry> entries = owner.session().findEntries(
                new EntryQuery(null, null, com.pijava.agent.session.EntryOrder.OLDEST_FIRST,
                    null, null));
            owner.persistedEntryIds().clear();
            entries.forEach(e -> owner.persistedEntryIds().add(e.id()));
            // replaceTranscript（非 seedTranscript）：导航是整体换上下文，
            // seed 的「首填才生效」门会在已有转录上静默不播。
            owner.harness().replaceTranscript(owner.laneName(),
                leafId == null ? List.of()
                    : ContextEntries.contextEntries(ContextEntries.pathToLeaf(entries, leafId)));
            // pi :4138（#77）：导航后按新叶最新系统消息的 toolsAdded 恢复激活装配。
            owner.harness().restoreToolsFromTranscript(owner.laneName());
            // pi :4140-4147：session_tree（newLeafId＝导航后当前叶；fromExtension
            // 仅在有摘要时有值——pi 同取 truthiness：summaryText ? fromExtension : undefined）。
            if (owner.extensionFirer() != null) {
                owner.extensionFirer().sessionTree(leafId, oldLeafId, summaryEntry,
                    hasSummaryText ? fromExtension : null);
            }
            return new TreeNavigation(false, false, editorText, summaryEntry);
        } finally {
            branchSummaryAbort = null; // pi :4155
        }
    }

    /**
     * 扩展摘要的 {@code details}（pi {@code unknown}）→ 存储形 {@code Map<String,Object>}：
     * Map 形按键字符串化拷贝（保序）；非 Map 形 ⇒ null（java 存储 schema 约束，pi 会原样存）。
     */
    private static java.util.Map<String, Object> mapDetails(Object details) {
        if (!(details instanceof java.util.Map<?, ?> m)) {
            return null;
        }
        var copy = new java.util.LinkedHashMap<String, Object>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    /** pi {@code abortBranchSummary()}（agent-session.ts:2922-2924）。 */
    void abortBranchSummary() {
        var token = branchSummaryAbort;
        if (token != null) {
            token.abort();
        }
    }

    /** 提取消息纯文本（pi {@code contentText} 默认分隔符 {@code "\n"}：text 块拼接）。 */
    static String textOf(List<com.pijava.ai.message.ContentBlock> content) {
        return textOf(content, "\n");
    }

    /**
     * 带分隔符的 {@link #textOf}（docs/21 T1）：pi {@code contentText(content, separator)}
     * ——导航 editorText 臂用默认 {@code "\n"}，fork 面用 {@code ""}（pi 两处分叉
     * 是原生行为：ai/utils/text.ts:6-11 的第二参）。
     */
    static String textOf(List<com.pijava.ai.message.ContentBlock> content,
                         String separator) {
        var parts = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof com.pijava.ai.message.ContentBlock.TextContent t) {
                parts.add(t.text());
            }
        }
        return String.join(separator, parts);
    }

    /**
     * pi {@code sessionManager.getTree()}（{@code session-manager.ts:1529-1567}）对位：
     * 持久层**全分支**条目组 entry 树，label／labelTimestamp 经事实表解析
     * （java 的 label 是事实旁表而非树内条目——docs/19 裁决①，窄差 D43）。
     * 无持久会话时 label 维度为空（harness 转录兜底路径）。
     */
    List<SessionTreeNode> getTree() {
        var entries = owner.accumulatedEntries();
        if (owner.session() == null) {
            return SessionTreeBuilder.build(entries, id -> null, id -> null);
        }
        return SessionTreeBuilder.build(entries, owner.session()::getLabel,
            owner.session()::getLabelTimestamp);
    }

    /** pi {@code sessionManager.getLabel()} 对位（无标签或无持久会话 ⇒ {@code null}）。 */
    String getLabel(String entryId) {
        return owner.session() == null ? null : owner.session().getLabel(entryId);
    }

    /**
     * pi {@code appendLabelChange}（{@code session-manager.ts:1441-1462}）的行为对位：
     * label 变更即持久化；java 落 FactLabel（wire 即 pi 原生 label 行），
     * {@code label == null} ⇒ 清除。
     */
    void setLabel(String entryId, String label) {
        if (owner.session() == null) {
            throw new IllegalStateException("Cannot set labels without a persistent session");
        }
        owner.session().view(owner.laneName()).setLabel(entryId, label);
    }

    /**
     * pi {@code AgentSession.summarizeForBugReport}（agent-session.ts:4322-4336）
     * 对位（批 15 T3b）：model 缺 ⇒ {@code No model selected}；否则由会话模型
     * 代写 bug 摘要（四守卫在 {@code BugReportSummarizer}）。
     */
    String summarizeForBugReport(String hint) {
        var summarizer = bugReportSummarizer;
        if (summarizer == null) {
            throw new IllegalStateException("Bug report summarizer not wired");
        }
        return summarizer.summarize(owner, hint);
    }
}
