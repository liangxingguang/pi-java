# docs/41 — 批 29 设计稿：结构债清空批（E 类 21 件全拆＋FileLength 闸门修真）

## §0 背景与范围

【背景摘要】2026-10-29 用户两道裁决：① examples/TUI 降为队尾后，**结构债清空插队在所有功能批之前**（批 29＝本批）；② 同日全量实测把 E 类（单文件超 500 行的结构债）从台账 5 件扩到 **21 件**（E8–E23 补登，含 AgentHarness/PiLaneSink 两件「批 agent-core-5 拆后又长回」）。

本批范围：

1. **21 件超限文件全部拆到 ≤450 行**（500 是硬顶，留 50 行缓冲防下批即回长）。
2. **闸门修真**：checkstyle `FileLength` 从 warning 提 **error** 级（它从未 fail 过构建——`checkstyle.xml:8` 全局 `severity=warning`，`checkstyle:check` 默认只认 error——这是 500 行规则形同虚设的机械根因）。
3. 闭环模板补「checkstyle 警告数」（docs/04 §7 教训 2 的落地）。

**硬约束**：全部行为零变化纯移动（先例：批 agent-core-5 拆 `PiLaneSink`/`AgentHarness`）；javadoc/行级注释逐字随迁；不动任何 public 签名（唯一例外见裁决②）；无 pi 对位（内部结构，面标记＝内部）。

## §1 逐件拆分表（盘点实测定案；行数＝拆前 → 拆后主＋新类）

| # | 文件（模块） | 拆前 | 拆分方案 | 拆后 |
|---|---|---:|---|---:|
| E1 | core/AgentSession（coding-agent） | 3,797 | 壳＋9 协作者：AgentSessionFactory／SessionWiring／ExtensionLoading／ModelToolControl／VirtualModelRouting（批 27 既定块）／PromptPipeline（含 M-1 扩展命令簇）／TreeCompaction／SessionDiscoveryFork／SessionMessagingLifecycle | 壳 ~470＋9×300–460 |
| E2 | rpc/RpcDispatcher（coding-agent） | 767 | ＋RpcSessionPayloads（状态/查询载荷，无状态工具类 session 入参）＋RpcExportSupport（导出＋共享 mapper）＋RpcExtensionUiChannel（应答通道） | 448＋228＋96＋92 |
| E5 | catalog/ModelCompat（ai） | 775 | 12 档便捷构造器 → ModelCompatFactories 静态工厂（裁决②）；57 调用点改签、5 处 40 参规范构造保留 `new` | 402＋382 |
| E6 | app/PiTuiApp（tui） | 1,137 | ＋StartupNotices／GlobalKeyRouter／OverlayManager／ExtensionHostWiring；对外签名全留委托桩 | ~480＋4 件 |
| E7 | screen/ChatScreen（tui） | 1,040 | ＋ExtensionInjectionLayer（WidgetEntry 随迁，裁决③）／SessionEventRouter／CacheNoticeRenderer／ChatInputPortal | ~490＋4 件 |
| E8 | extension/DefaultExtensionContext（coding-agent） | 1,118 | ＋PerExtensionContext 顶出＋ExtensionSurfaceRegistries（flag/shortcut/renderer/markdown/MCP 五个同构分桶注册表）＋getCommands＋模型面 | ~430＋~280＋~400 |
| E9 | theme/SystemTheme（coding-agent） | 663 | ＋SystemThemeRecipe（配方数据族 39–317；generate 体 ~25 处加前缀） | ~350＋~300 |
| E10 | protocol/ResponsesMessageConverter（ai） | 631 | ＋ResponsesMessageItems（消息转换链 267 行纯静态）；顺手删四件死代码（toJsonValues/toArgumentsJson/JSON/extractText，活体在 ResponseToolWire/ResponseInputWire） | ~340＋~300 |
| E11 | stream/StreamPartialBuilder（ai） | 605 | ＋TextThinkingBlockAccumulator（text＋thinking 两簇 247 行）；主类 14 个 public 全留委托（**全班最重 public 面**） | ~400＋~300 |
| E12 | harness/PiLaneEngine（agent-core） | 588 | ＋PiLaneConfig（配置/请求钩＋队列簇 209 行） | ~380＋~250 |
| E13 | provider/ModelsJsonCompat（ai） | 565 | ＋ModelsJsonCompatFieldParsers（字段解析器簇 191 行纯静态） | ~374＋~220 |
| E14 | session/jsonl/JsonlCodec（agent-core） | 545 | ＋LegacyMutationParser（mutation 解析臂 ~110 行；**取零波及备选**——校验助手是跨模块 public 面，不动） | ~435＋~150 |
| E15 | harness/CompactionExecutor（agent-core） | 545 | ＋CompactionTranscriptBuilder（转录装配簇 108 行） | ~437＋~150 |
| E16 | harness/AgentHarness（agent-core） | 545 | ＋HarnessPromptAndToolAccessors 中间基类（门面簇 123 行；继承保 public 面逐字不变——HarnessBootstrap 先例） | ~422＋~160 |
| E17 | WebDispatcher（web） | 537 | ＋WebSessionCommands（会话命令族 ~125 行；sessionsListingGeneration 测试口留主类） | ~412＋~160 |
| E18 | hook/HookSystem（agent-core） | 526 | ＋HookChains（链式合并四件 122 行；主类留一行委托） | ~420＋~160 |
| E19 | theme/Themes（coding-agent） | 523 | ＋ThemeCatalog（装载面 103–291；REGISTERED_THEMES 锁经包私有快照口跨过） | ~330＋~210 |
| E20 | protocol/MistralConversationsApi（ai） | 521 | ＋MistralSseProcessor（SSE 帧处理簇 203 行，hook 字段构造器注入） | ~318＋~240 |
| E21 | core/SettingsAccessors（coding-agent） | 515 | ＋SettingsCompactionAccessors（compaction 域 394–515 整块；批 11 兄弟访问器先例；~10 处调用点改道 `compaction()` 口） | ~393＋~135 |
| E22 | protocol/AnthropicMessagesApi（ai） | 512 | ＋AnthropicEventMapper（mapEvent 191 行，oauth 字段注入）；**硬约束：buildParams(StreamRequest) 单参私有方法名/签名不动**（反射夹具） | ~325＋~230 |
| E23 | harness/PiLaneSink（agent-core） | 505 | ＋PiLaneToolRecords（工具遥测簇 ~130 行；volatile/可见性注释随迁） | ~375＋~160 |

## §2 闸门修真（逐字改动点）

`checkstyle.xml`（仅 FileLength 提级，其余维持 warning 不动水面）：

```xml
<module name="FileLength">
    <property name="max" value="500"/>
    <property name="severity" value="error"/>
</module>
```

上线时机：**21 件全拆完之后**单独一个 commit（否则存量超限立红）。

## §3 裁决点（2026-10-29 用户已按建议拍板，存档备查）

### 裁决①：AgentSession 全量 10 文件拆（否掉两步走）

- 【场景】3,797 行 ÷ 500 硬顶 ⇒ 最少 8 文件；两步走（只拆既定两块）本批评审面最小但结构债不清空。
- 【决定】全量 10 文件（§1 E1 行）；两步走与 8 文件紧凑方案（两并簇超 500）记录在案淘汰。
- 【风险/代价】拆分身约 +250–300 行壳/委托成本；簇间互访（emitSessionEvent/laneName/extensionFirer/session）经壳包私有访问器供给。[内部，模型不可见]

### 裁决②：ModelCompat 便捷构造器 → 静态工厂

- 【场景】record 的构造器必须与 record 同文件，12 档便捷构造器（372 行）不能以构造器形态移出。
- 【决定】改 `ModelCompatFactories.of(...)` 按元数重载（12 档元数互不歧义），57 处调用点机械改签；40 参规范构造保留 `new ModelCompat(...)`。否掉「文档外迁」备选（absent-case 表是 B98/B99 系裁决记录载体，台账多次引用）。
- 【风险/代价】public API 形状变化（`new` → 工厂），全仓改签一处不漏经编译器全量验证。[内部，模型不可见]

### 裁决③：ChatScreen 的 WidgetEntry 随 ExtensionInjectionLayer 搬

- 【场景】WidgetEntry 是测试断言面（widgetsForProbe 返回它）；留壳则主类行数下不来。
- 【决定】随层搬，测试引用改类型名（纯测试侧调整，非行为）。
- 【风险/代价】约 3 个测试类引用改签。[内部，模型不可见]

### 裁决④：FileLength 提 error 级＋闭环模板补警告数

- 【场景】500 行规则有检查无闸门（severity=warning 从不 fail）；闭环口径只报 error 数。
- 【决定】FileLength 单独提 error（§2）；docs/04 §7 加维护规则：闭环验收须写明 checkstyle 错误、警告各几个。
- 【风险/代价】今后任何文件超 500 即构建红——这是目的本身；vendored TamboUI（dev.tamboui/**）既有排除不变。[内部，模型不可见]

## §4 实施任务（依赖序＝commit 序）

| T | 内容 | 模块验证 |
|---|---|---|
| T1 | E5 ModelCompat（已完成：402＋382，1,737 测绿，commit c7c22443） | ai |
| T2 | E10/E11/E13/E20/E22（ai 五件，并行代理实施） | `mvn test -pl pi-java-ai -am` |
| T3 | E12/E14/E15/E16/E18/E23（agent-core 六件，并行代理） | `mvn test -pl pi-java-agent-core -am` |
| T4 | E8/E9/E19/E21（coding-agent 四件）＋E17（web，并行代理） | `mvn test -pl pi-java-coding-agent,pi-java-web -am` |
| T5 | E6/E7（tui 两件，并行代理） | `mvn test -pl pi-java-tui -am` |
| T6 | E2 RpcDispatcher（主会话已实施：448＋228＋96＋92） | 随 T4 一并 |
| T7 | E1 AgentSession（专项代理实施） | `mvn test -pl pi-java-coding-agent -am` |
| T8 | 闸门修真（FileLength→error）＋docs/04 §7 规则补 | 全量 `mvn clean verify` |

每个 commit 前对应模块测试全绿；并发代理只改文件不跑 mvn（防 target 互踩），构建主会话串行。

## §5 窄差预案

无新窄差预期（纯内部移动）。E10 死代码删除是行为不变的前提性取证（四件无任何文件外引用）。

## §6 测试计划

- 零新增测试（行为零变化 ⇒ 既有 5,400+ 用例即回归网）。
- 每模块测试全绿才许 commit；T8 后全量 `mvn clean verify`（exit 码落盘，防管道吞退出码坑）。
- 反射夹具点（AnthropicMessagesApi.buildParams、批 20 I-1 虚拟线程包装）逐字保留。

## §7 账面预算

账面不动（结构债不进地图行）；E1–E23 台账清零；docs/05 LOC 审计列随批回填。

## §8 风险与排障预案

1. **volatile/跨线程字段随迁**：PiTuiApp overlay/running/session、ChatScreen indicator/assistantStreamed/thinkingRendered、PiLaneSink 五张表、AgentSession emittingAgentSettled/agentRunAbortRequested——可见性注释逐字随迁，spotbugs 复核。
2. **批 20 I-1 死锁不得复活**：executeExtensionCommand 的独立虚拟线程包装随 PromptPipeline 逐字搬。
3. **loadExtensions 四搭车钩次序**（3592 虚拟路由/3637 MCP/3708 嵌套/3738 changeListener）逐字保持。
4. **并发编辑撞车**：SettingsAccessors 代理会改 RpcSessionPayloads 的 getCompactionEnabled 调用点——串行构建时发现即收。
5. CRLF 行尾：perl/sed 锚定 `$` 须带 `\r?`（本批已踩）。

## §9 闭环回填（2026-10-29 闭环）

- **实施序**：T1 E5（c7c22443）→ T2 ai 五件 → T3 agent-core 六件 → T4 coding-agent 四件＋E17 → T5 tui 两件 → T6 E2（随 T4 commit）→ T7 E1 → T8 闸门修真。并行代理只改文件，构建主会话串行（模块序：ai→agent-core→coding-agent/web→tui）。
- **实际行数**：全 21 件拆后 ≤500（最大 DefaultExtensionContext 499、AgentSession 壳 496、SessionDiscoveryFork 496、SessionWiring 492——四件贴线，后续净增须先腾挪）。
- **偏离设计的实落点**：PiTuiApp 5→6 件（追加 SlashFlowRouter）、ChatScreen 5→7 件（追加 TranscriptProjector＋ChatScreenLayout）、AgentSession 10→11 件（追加 SessionDataViews）——三起同一根因：委托桩自重把留存顶过 500，500 闸门倒逼加拆；ThemeCatalog 判 public（跨模块调用方）；JsonlCodec 取零波及备选；E10 删死代码四件（全仓零引用取证）。
- **verify 实测**：`mvn clean verify` exit 0，13:44 min，**5,441 测 0 失败 0 错 21 跳过**；checkstyle **0 错误 0 警告**（FileLength 已在 error 级，全仓无超 500）；spotbugs 13 模块零钉。
- **排障钉**（回填 docs/04 E 类闭环段与 §7）：① 构造器链式委托迁工厂后须改 `of(...)` 链（中间档不再存在于 record）；② CRLF 行尾使 perl `$` 锚失效（import 插入第一次全落空）；③ blank final 字段不可在另一字段的初始化器里引用（DU 分析，移构造器赋值）；④ 嵌套 record 搬出实现类后须显式 import（实现接口的类才天然可见成员类型）；⑤ 两起套件 flake（RichSessionListTest、InteractiveModeRestoreTest）隔离复跑即绿——§7 教训 3 同族；⑥ 无 `-am` 拾旧 jar 再撞（tui 编译打 coding-agent 旧构件缺 ThemeCatalog）。
- **台账回填三处**：docs/04 E 类 21 件清零（含根因记录与闸门修真注记）＋§7 教训四条化；docs/06 §1/§2/§3（批 29 行＋序 22 行移除＋总批次估计 29＋4＋4–6＋6）；docs/05 LOC 审计列不动（拆分不改信号值）。
