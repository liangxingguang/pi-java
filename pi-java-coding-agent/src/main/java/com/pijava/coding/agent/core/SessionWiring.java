package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.pijava.agent.compaction.CompactionObserver;
import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.DefaultStreamFn;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.agent.prompt.ContextFileDiscovery;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.tool.ImagePolicy;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolSetFactory;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.compaction.BranchSummarization;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的「会话＋harness」装配簇 ——
 * wireSession（含匿名 CompactionObserver/RetryObserver/UsageAppender）＋
 * compactionReasonOf＋warmStream＋cacheContextIsCurrent。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class SessionWiring {

    private SessionWiring() {
    }

    /**
     * docs/16（T1）：assemble 与 fork 共用的「会话＋harness」装配 —— pi createRuntime
     * （agent-session-runtime.ts:29-42 工厂型）对位：每次替换都重建注册表与会话绑定
     * 闭包（sessionRef 五组：压缩/重试事件、重试中止旗、图片策略、PI_* 会话身份），
     * 只共享真正的进程级件（settings/providers/两侧 repository）。**不加载扩展**
     * （loadExtensions 由调用方按 pi 的序另发 —— 初始装配立即、fork 在旧会话
     * teardown 后，§3.4）。
     *
     * @param carryModel    非 null ⇒ 携带父会话当前模型（{@code "provider/id"}，fork 臂，
     *                      裁决①）；null ⇒ 按 args/settings/scope 解析（初始装配臂）
     * @param carryThinking 同上，携带思考级别
     */
    static AgentSession wireSession(Args args, SettingsManager settings,
            ProviderRegistry providers, ToolContext toolContext,
            SessionRepository<?, ?, ?> harnessRepository,
            boolean existingSession, String name,
            String carryModel, ModelThinkingLevel carryThinking) {
        var effective = settings.effective();
        var catalog = com.pijava.ai.provider.ModelsJsonConfig.allModels();
        var models = new DefaultModelResolver(catalog);
        // B137：--models ?? settings.enabledModels → scope（诊断此处打 stderr）。
        var scope = ModelScoping.resolve(args, settings, catalog);
        var tools = new ToolRegistry(null);
        var commandPrefix = effective.shellCommandPrefix == null
            ? "" : effective.shellCommandPrefix;
        // B236（docs/11 §3.6）：注册面 = pi createAllTools 八件（tools/index.ts:213-224，
        // 含 powershell——批 4 只落了本体，注册面在本批）经 pi _isAllowedTool 门过滤
        //（agent-session.ts:1537-1541 → _refreshToolRegistry :3514）：allowlist 不匹配或
        // excluded 的内置工具**不注册**；空名单（--no-tools / --tools ""）注册面空
        //（pi getAllTools()==[]，default-tools-setting.test.ts:155）。
        var selection = SessionSetup.resolveToolSelection(args, settings);
        var toolList = ToolSetFactory.createAllTools(commandPrefix).stream()
            .filter(t -> selection.isAllowed(t.name()))
            .toList();
        tools.registerAll(toolList);

        // 批 18（评审 M4）：一次装配一次 resolve——temporary git 源每遍真 pull，
        // 重复 resolve 有副作用与会话内不一致窗口；pi resource-loader 单遍对位。
        var resolveAssembly = SessionSetup.resolveAll(args, settings);
        var promptTemplates = new PromptTemplateRegistry();
        // 批 18（T6 裁决⑨）：模板由 PackageManager.resolve() 统辖（settings 数组/
        // 包/自动发现五档优先级＋CLI 显式），批 16 前的默认两目录直扫退役。
        promptTemplates.registerAll(SessionSetup.loadPromptTemplates(args, resolveAssembly));
        var services = new SessionServices(
            settings,
            // 批 23（T3，裁决②）：pi ProjectTrustStore 对位——trust.json 落
            // agentDir（存储面可取则随其根，否则默认 agentDir）。
            ProjectTrustStore.forSettings(settings),
            providers,
            models,
            tools,
            CommandRegistry.withBuiltins(),
            harnessRepository,
            promptTemplates);

        var providerName = DefaultProviders.resolveProviderName(
            args, effective.defaultProvider);
        var modelPattern = args.model() != null
            ? args.model() : effective.defaultModel;
        if (modelPattern == null || modelPattern.isBlank()) {
            modelPattern = providerName;
        }
        // pi buildSessionOptions:499-516：未显式 --model 且非 resume 存量会话时，
        // saved 默认在 scope 内则用之，否则 scoped[0]；scope 空回退既有解析。
        com.pijava.ai.model.ScopedModel scopedInitial = null;
        if ((args.model() == null || args.model().isBlank()) && !existingSession) {
            scopedInitial = ModelScoping.initial(
                scope, effective.defaultProvider, effective.defaultModel, catalog)
                .orElse(null);
        }
        String scopeLevel = scopedInitial != null
            ? scopedInitial.thinkingLevel().orElse(null) : null;
        // docs/17（B248，裁决③）：provider 请求面桥随 StreamFn 建（pi extensionRunnerRef
        // 先建后填对位）——bus 在 loadExtensions 才诞生，install 回填前全钩空跳。
        var providerBridge = new com.pijava.coding.agent.extension.bridge.ProviderEventBridge();
        var streamFn = DefaultProviders.streamFnFor(
            args, effective.defaultProvider, providers, effective, args.sessionId(),
            providerBridge);
        // pi sdk.ts:43-46 的模块顶层副作用对位（setDefaultStreamFn(streamSimple)）：
        // 进程级缺省兜底，给省略 streamFn 的 HarnessConfig.build() 回落（#69）。
        // 窄差：pi 装的 streamSimple 每次调用现解析环境凭证，这里装的是本次会话
        // 装配的解析结果（后续会话覆盖安装）——单会话场景行为等价（docs/09 §3.3）。
        DefaultStreamFn.set(streamFn);
        // Trace exporter: same instance powers harness telemetry (spans/counters)
        // and payload recording (wrapper), so events bind to the llm.request span.
        var telemetry = PayloadRecordingStreamFn.exporter(args.sessionId(), args.tracePayloads());
        var recordingStreamFn = new PayloadRecordingStreamFn(streamFn, telemetry);
        // docs/16 裁决①：fork 臂携带父会话当前模型（java 的 setModel 无 persist 面，
        // 携带＝pi persist→settings 重解析臂的实际效果）；初始装配臂走既有解析链。
        var model = carryModel != null
            ? models.resolve(carryModel, null)
            : scopedInitial != null
                ? scopedInitial.model().id()
                : models.resolve(modelPattern, providerName);
        // 3c（原 docs/31 §8.21，裁决③）：宿主层的自动压缩事件走轻量 observer，不占
        // HookSystem。harness 先于会话构造 ⇒ 用引用晚绑定：observer 在压缩真正
        // 发生时（必晚于 create() 返回）才读得到会话。pi 的 compaction_start/end
        // 就发在会话层，这里经 emitSessionEvent 进同一条会话事件流。
        var sessionRef = new java.util.concurrent.atomic.AtomicReference<AgentSession>();
        // B85（批 9，docs/18 §4.3/§4.5）：图片策略晚绑定——read 与工具结果收口点在
        // 执行期现读，设置改动 / setModel 后下次工具调用即生效（pi _limitsModel 语义）。
        var policyToolContext = toolContext.withImagePolicy(() -> {
            var s = sessionRef.get();
            return s == null ? ImagePolicy.defaults() : s.currentImagePolicy();
        });
        // B242（docs/09 §8.3）：PI_* 会话身份晚绑定（pi bash.ts:199-208 的 ctx 三面）。
        // 执行期现读 ⇒ setModel/setThinkingLevel 后下次工具调用即拿到新值。
        policyToolContext = policyToolContext.withSessionEnvironment(() -> {
            var s = sessionRef.get();
            if (s == null) {
                return null;
            }
            var m = s.harness().getModel();
            return new com.pijava.agent.tool.SessionEnvironment(
                s.sessionId(), s.sessionFile(),
                m == null ? null : m.provider(),
                m == null ? null : m.modelName(),
                s.harness().getThinkingLevel() == null ? null
                    : s.harness().getThinkingLevel().label());
        });
        var compactionObserver = new CompactionObserver() {
            @Override
            public void onStart(String reason) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(
                        new AgentSessionEvent.CompactionStart(compactionReasonOf(reason)));
                }
            }

            @Override
            public void onEnd(String reason, CompactionResult result, boolean aborted,
                              boolean willRetry, String errorMessage,
                              com.pijava.agent.entry.Entry.Compaction compactionEntry) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.CompactionEnd(
                        compactionReasonOf(reason), result, aborted, willRetry, errorMessage,
                        compactionEntry));
                }
            }
        };
        // 3d（原 docs/31 §8.22）：两环的运行时设置与事件。设置 = 每决策现读
        // settings.accessors().getRetrySettings()（pi 的 getRetrySettings() 不缓存）；
        // 中止旗与事件仍经 sessionRef 晚绑定（harness 先于会话构造）。
        // 环 A 的 auto_retry_* 由引擎 ①（PostRunRetry）发，环 B 的
        // summarization_retry_* 由摘要生成器内的 retryAssistantCall 环发 ——
        // 同一份 observer 接两路。窗口簿记（begin/endRetrySleep）≙ pi 的
        // _retryAbortController 建/弃。
        Supplier<RetrySettings> retrySettings = settings.accessors()::getRetrySettings;
        BooleanSupplier retryAborted = () -> {
            var session = sessionRef.get();
            return session != null && session.retryAborted();
        };
        var retryObserver = new RetryObserver() {
            @Override
            public void onAutoRetryStart(int attempt, int maxAttempts, long delayMs,
                                         String errorMessage) {
                var session = sessionRef.get();
                if (session != null) {
                    session.beginRetrySleep();
                    session.emitSessionEvent(new AgentSessionEvent.AutoRetryStart(
                        attempt, maxAttempts, delayMs, errorMessage));
                }
            }

            @Override
            public void onAutoRetryEnd(boolean success, int attempt, String finalError) {
                var session = sessionRef.get();
                if (session != null) {
                    session.endRetrySleep();
                    session.emitSessionEvent(new AgentSessionEvent.AutoRetryEnd(
                        success, attempt, finalError));
                }
            }

            @Override
            public void onSummarizationRetryScheduled(int attempt, int maxAttempts,
                                                      long delayMs, String errorMessage) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryScheduled(
                        attempt, maxAttempts, delayMs, errorMessage));
                }
            }

            @Override
            public void onSummarizationRetryAttemptStart(String source, String reason) {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryAttemptStart(
                        source, reason));
                }
            }

            @Override
            public void onSummarizationRetryFinished() {
                var session = sessionRef.get();
                if (session != null) {
                    session.emitSessionEvent(new AgentSessionEvent.SummarizationRetryFinished());
                }
            }
        };
        // B173（docs/22）：摘要请求要读会话当前思考级别，而 harness 在 generator
        // 构造之后才诞生 —— 按 sessionRef/compactionObserver 同款引用晚绑定；
        // supplier 只在摘要请求时（必晚于 create 返回）才读。
        var harnessRef = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        // 批 13（docs/24 T6，pi sdk.ts:334-339 对位）：cache warmer 先于 harness 构造
        // （isCurrent/sessionAccess 经 harnessRef/sessionRef 晚绑定——首请求必晚于
        // create 返回）。decide 经 CacheWarmingBridge 晚绑定（pi extensionRunnerRef
        // 对位），loadExtensions 处 install（providerBridge.install 同点）。
        var cacheWarmingBridge =
            new com.pijava.coding.agent.extension.bridge.CacheWarmingBridge();
        var cacheWarmer = new CacheWarmer(
            warmStream(DefaultProviders.warmStreamFnFor(
                args, effective.defaultProvider, providers, effective, args.sessionId(),
                providerBridge)),
            new CacheWarmer.SessionAccess() {
                @Override
                public Entry.Usage appendUsage(String kind, String provider, String model,
                                               com.pijava.ai.Usage usage, String note) {
                    var s = sessionRef.get();
                    return s == null ? null : s.appendUsage(kind, provider, model, usage, note);
                }

                @Override
                public List<Entry> getBranch() {
                    var s = sessionRef.get();
                    return s == null ? List.of()
                        : ContextEntries.pathToLeaf(s.accumulatedEntries(), s.currentLeafId());
                }
            },
            () -> settings.network().getCacheWarmingMode(),
            cacheWarmingBridge::emitOrDefault,
            WarmScheduler.virtualThreadDaemon(), System::currentTimeMillis, catalog::find);
        // 裁决①（替代 pi sdk.ts:427 的 sessionId 比较——java 的 StreamOptions.sessionId
        // 在车道请求恒空、亲和 id 由宿主层 requestExtra 统一注入，门无法逐字抄）：
        // warming wrapper 只包 harness 注入点；LlmSummaryGenerator（:573）保持
        // recordingStreamFn ⇒ 压缩/摘要天然不触发 start（pi「仅会话请求」门的 java 形：
        // 「compaction and summaries use their own routing ids; only session requests
        // replace the cache entry」）。
        var warmingStreamFn = (com.pijava.agent.harness.StreamFn) (requestModel,
                requestContext, requestOptions) -> {
            cacheWarmer.start(
                new CacheWarming.WarmRequest(requestModel, requestContext, requestOptions),
                cacheContextIsCurrent(harnessRef, requestModel));
            return recordingStreamFn.stream(requestModel, requestContext, requestOptions);
        };
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(warmingStreamFn)
            .model(model)
            // 3b（原 docs/31 §8.20）：阈值压缩的门读**当前模型**的上下文窗口
            // （pi model.contextWindow，agent-session.ts:548）；目录字段
            // maxInputTokens 的释义即窗口大小。setModel 后随之动态变。
            .contextWindow(models::contextWindow)
            // 3c（原 docs/31 §8.21，裁决④）：isRecoverableLength 的操作数 =
            // pi model.maxTokens = 目录 maxOutputTokens；未编目 ⇒ 0 ⇒ 判据恒 false。
            .maxOutputTokens(models::maxOutputTokens)
            // 3d 环 B：摘要调用包在 retryAssistantCall 同形环里 —— 与环 A
            // 同一份 retry 预算、同一个中止旗观察口、同一个 observer（§8.22.3）。
            // 批 27（B162/T10，pi :583-589 对位）：虚拟选型 ⇒ 摘要请求先路由
            // （reason "direct"，不写态）；sessionRef 摘要期必非空（压缩发生在 run 内）。
            .summaryGenerator(new LlmSummaryGenerator(recordingStreamFn,
                () -> VirtualModelRouting.summaryRoutedModel(sessionRef.get(), model),
                // B171（docs/18）：pi model.maxTokens 从目录取值 —— turn-prefix
                // 摘要的输出上限被当前模型的 maxOutputTokens 封顶（cap 在调用点
                // 读当前 model，故经零参 lambda 传入，不直传一参方法引用）。
                retrySettings, retryAborted, retryObserver,
                () -> models.maxOutputTokens(VirtualModelRouting.summaryRoutedModel(
                    sessionRef.get(), model)),
                () -> harnessRef.get().getThinkingLevel(),
                models::supportsThinking))
            .thinkingLevel(carryThinking != null ? carryThinking
                : SessionSetup.thinkingLevelFor(
                    args, settings.effective().defaultThinkingLevel, scopeLevel,
                    // 批 11 T2（pi sdk.ts:262-270）：初始模型命中 per-model 表 ⇒ 应用。
                    model == null ? null : settings.accessors()
                        .getModelThinkingLevel(model.provider(), model.modelName())))
            .systemPrompt(SessionSetup.customPromptFor(args, settings,
                java.nio.file.Path.of(System.getProperty("user.dir"))))
            .promptGuidelines(SessionSetup.DEFAULT_PROMPT_GUIDELINES)
            .appendSystemPrompt(SessionSetup.appendSystemPromptFor(args, settings,
                java.nio.file.Path.of(System.getProperty("user.dir"))))
            .activeTools(SessionSetup.activeTools(selection, toolList))
            .toolRegistry(tools)
            .toolContext(policyToolContext)
            .steeringMode(SessionSetup.queueMode(effective.steeringMode))
            .followUpMode(SessionSetup.queueMode(effective.followUpMode))
            .toolExecution(ToolExecution.defaultMode())
            .skills(SessionSetup.discoverSkills(args, resolveAssembly))
            .telemetry(telemetry)
            .compactionObserver(compactionObserver)
            // 3d 环 A/B 三件套（原 docs/31 §8.22.3）：settings 晚读口（每次 run 现取，
            // 覆盖 setAutoRetryEnabled 之后的路径）、中止观察口（退避睡眠窗口）、
            // 事件观察器（auto_retry_* / summarization_retry_* 映射到会话事件面）。
            // 缺了这三行不会编译报错 —— 槽位有静默默认值，但设置与事件全断。
            .retrySettings(retrySettings)
            .retryAborted(retryAborted)
            .retryObserver(retryObserver)
            // B223（原 docs/47）：压缩设置晚读口 —— 引擎每次决策按 lane 当前模型
            // 回调本 resolver（pi getCompactionSettings(this.model) 四调用点）；
            // setModel/modelOverrides/设置文件改动下次决策即生效，fork 不串模型。
            // 不接线 ⇒ 自动压缩恒关（B223 原状）；接了线 ⇒ pi 的「默认开」
            // 由 settings 的 ?? true 语义在 resolver 内实现。
            .compactionResolver(m -> settings.compaction().getCompactionSettings(m))
            // B128：settings.thinkingBudgets（pi sdk.ts:444 new Agent({thinkingBudgets})）；
            // 缺席为 null，ai 侧按默认表合并。
            .thinkingBudgets(settings.accessors().getThinkingBudgets())
            // B64（原 docs/24）：AGENTS.md/CLAUDE.md 发现；-nc 关闭。
            // 晚读口：每次 run 重新遍历（与 pi 同），不缓存。
            .contextFiles(() -> args.noContextFiles()
                ? List.of()
                : ContextFileDiscovery.discover(
                    Path.of(System.getProperty("user.dir")),
                    FileSettingsStorage.defaultAgentDir()))
            .build());
        harnessRef.set(harness);
        var agentSession = new AgentSession(harness, services, args, name);
        agentSession.extensions().providerBridge(providerBridge);
        // 批 13（T6/T8）：warmer 与 decide 桥随会话走（fork 经 wireSession 公共路径
        // 自动重装——pi 每会话新建 CacheWarmer 同），旧 warmer 随 closeWith cancel。
        agentSession.messaging().cacheWarmer(cacheWarmer);
        agentSession.messaging().cacheWarmingBridge(cacheWarmingBridge);
        sessionRef.set(agentSession);
        // 批 16（T5a）：选择策略随会话存（fork 经 wireSession 公共路径自动继承——
        // pi 的 fork 重建带同配置）。
        agentSession.modelTools().toolSelection(selection);
        agentSession.setScopedModels(scope.models());
        agentSession.modelTools().modelCatalog(catalog);
        // B1（map #24/#25）：分支摘要生成器 —— 与压缩同源的 StreamFn/retry 环，
        // reason 传 "branchSummary"（重试事件线已预留该源，docs/09 §7.2#6）。
        agentSession.tree().modelResolver(models);
        agentSession.tree().branchSummarization(new BranchSummarization(
            new LlmSummaryGenerator(recordingStreamFn,
					() -> VirtualModelRouting.summaryRoutedModel(
					    agentSession, agentSession.harness().getModel()),
				retrySettings, retryAborted, retryObserver,
                () -> models.maxOutputTokens(
                    VirtualModelRouting.summaryRoutedModel(
                        agentSession, agentSession.harness().getModel())),
				agentSession.harness()::getThinkingLevel,
                models::supportsThinking)));
        // 批 15（T3b）：bug 摘要生成器——与分支摘要同源的 StreamFn/retry 环
        //（pi agent-session.ts:4322 的 _getSummarizationRequestAuth 对位：
        // auth 参在 java 由 streamFn 车道自取）。
        agentSession.tree().bugReportSummarizer(new com.pijava.coding.agent.core.bugreport
            .BugReportSummarizer(new LlmSummaryGenerator(recordingStreamFn,
					() -> VirtualModelRouting.summaryRoutedModel(
					    agentSession, agentSession.harness().getModel()),
				retrySettings, retryAborted, retryObserver,
                () -> models.maxOutputTokens(
                    VirtualModelRouting.summaryRoutedModel(
                        agentSession, agentSession.harness().getModel())),
				agentSession.harness()::getThinkingLevel,
                models::supportsThinking)));
        agentSession.discovery().baseToolContext(toolContext);
        agentSession.extensions().resolveAssembly(resolveAssembly);
        return agentSession;
    }

    /**
     * observer 的 reason 字符串（pi 的字面量 {@code "manual"}/{@code "threshold"}/
     * {@code "overflow"}）→ 会话事件的枚举。未知值归 OVERFLOW 是防御：observer 的
     * 生产点只有这三个字面量（agent-core 的压缩三路）。
     */
    static AgentSessionEvent.CompactionReason compactionReasonOf(String reason) {
        return switch (reason) {
            case "manual" -> AgentSessionEvent.CompactionReason.MANUAL;
            case "threshold" -> AgentSessionEvent.CompactionReason.THRESHOLD;
            default -> AgentSessionEvent.CompactionReason.OVERFLOW;
        };
    }

    /**
     * 批 13（T7 尾巴，pi cache-warmer.ts:332-338 的 {@code streamSimple(...).result()}
     * 对位）：暖 {@link com.pijava.agent.harness.StreamFn} ⇒ 阻塞消费到终局。
     * try-with-resources 包迭代器（abort 句柄持有它、close 即断连，裁决②/D65）；
     * {@code StreamError} ⇒ ERROR 终局消息（warmer 侧不记账，pi catch 全吞对位）。
     */
    static CacheWarmer.WarmStreamFn warmStream(
            com.pijava.agent.harness.StreamFn warmStreamFn) {
        return (model, context, options, abort) -> {
            com.pijava.ai.message.Message.AssistantMessage finalMessage = null;
            try (var iterator = warmStreamFn.stream(model, context, options)) {
                abort.bind(iterator);
                while (iterator.hasNext()) {
                    switch (iterator.next()) {
                        case StreamEvent.StreamDone done -> finalMessage =
                            com.pijava.ai.message.Message.AssistantMessage.fromPartial(
                                done.partial());
                        case StreamEvent.StreamError error -> finalMessage =
                            new com.pijava.ai.message.Message.AssistantMessage(List.of(),
                                com.pijava.ai.message.StopReason.ERROR, null, null, null, null,
                                null, Instant.now(),
                                error.error() == null ? null : error.error().getMessage(), null);
                        default -> { /* 中间帧不消费（maxTokens=1 重放只有增量帧）。 */ }
                    }
                }
            }
            return finalMessage;
        };
    }

    /**
     * 批 13（T6，pi sdk.ts:369-381 逐字对位）：provider/id 相同＋捕获消息数组是当前
     * 数组的前缀（逐元素恒等）。pi 注释：agent 可能浅拷贝 messages 数组，顶层对象
     * 身份不是有效缓存键。
     *
     * <p><b>捕获必须是快照（评审 C1）</b>：java 的 {@code lane.messages} 是 final
     * 字段、压缩/树导航/resume/reset 全走<b>就地</b> {@code clear()+addAll()}
     * （{@code HarnessUtils.rebuildLaneMessages}）——捕获活视图会让前后两个视图
     * 包装同一存储，前缀恒等退化为恒真，{@code "conversation context changed"} 门
     * 整体失效。{@code List.copyOf} 拍下捕获点元素身份：run 中引擎只追加（含
     * {@code set(last)} 替换在飞 assistant——索引超出快照长度）⇒ 前缀稳定；
     * 就地重写从 0 索引放新实例（或变短）⇒ 比对失败 ⇒ 与 pi 同点停暖。</p>
     */
    static BooleanSupplier cacheContextIsCurrent(
            java.util.concurrent.atomic.AtomicReference<AgentHarness> harnessRef,
            ModelId<?> requestModel) {
        var harness = harnessRef.get();
        var messages = harness == null
            ? List.<com.pijava.ai.message.Message>of() : List.copyOf(harness.getMessages());
        return () -> {
            var current = harnessRef.get();
            if (current == null) {
                return false;
            }
            var currentModel = current.getModel();
            var currentMessages = current.getMessages();
            if (currentModel == null
                    || !currentModel.provider().equals(requestModel.provider())
                    || !currentModel.modelName().equals(requestModel.modelName())) {
                return false;
            }
            if (messages.size() > currentMessages.size()) {
                return false;
            }
            for (int i = 0; i < messages.size(); i++) {
                if (currentMessages.get(i) != messages.get(i)) {
                    return false;
                }
            }
            return true;
        };
    }
}
