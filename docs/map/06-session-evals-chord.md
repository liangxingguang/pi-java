# 06 会话后端 / 评测 / chord / 防漂移脚本

> 【背景摘要】本图覆盖四块：会话持久化（sqlite 模块与 agent-core 的 JSONL 面）、评测（evals）、chord（R5 不可达）、防漂移脚本（口径外）。核心结论：pi 主流会话持久化只有 JSONL＋in-memory，SQLite 后端无对齐参照物。

## 基准与范围

- 基准：pi @ `ea448f454`（2026-10-11，`v1.1.0-22`）；pi-java @ `edc8749d`（main）。
- 范围：`pi-java-session-backend-sqlite` ＋ `pi-java-agent-core` 的会话持久化面（主流对照 pi `coding-agent/src/core/session-manager.ts`）；`pi-java-evals` ↔ pi `packages/evals`；pi `packages/chord`（R5 不可达）；pi 仓库根 `scripts/` 与本仓 `docs/map/check-drift.sh`（口径外）。
- 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 `file:line`。
- pi 参照版本：`1.1.0`（`packages/evals/package.json` version 字段）。

---

## 口径：两条铁律

1. **R1「pi 删掉的，pi-java 也删」**——参照物被删除的单元**权重 0、移出分母**。注意不是改判「对齐」：判据是「和 pi 表现一样」，pi 没这个行为了，就没有对齐可言。
2. **R5「只算 `bin pi` 主流可达的」**——判据是从 `bin pi → dist/bundle/cli.js ← src/cli.ts → main()` 这条线可达。不可达者权重 0、不进分母。

### B59（SQLite schema 分叉）裁决：保持「不作缺口」

- 【场景】pi-java 有 SQLite 会话后端，schema 与 pi 曾经的 session-backends 分叉。旧文记「SQLite schema 完全分叉＝全项目单点最大拖累（Σ权重 25，+16.2 pp）」。
- 【决定】不作缺口、移出分母。pi 主流会话持久化**只有 JSONL ＋ in-memory**（`SessionManager`），没有 SQLite 后端；`packages/session-backends` 整包已删，且删除前就**从未被 `bin pi` 主流消费**（R1 ＋ R5）。旧判定**自始即错**。
- 【风险/代价】无影响。[内部，模型不可见] 不主张删除 SQLite 模块；其独立 schema 不进入 pi 对齐分母。

JSONL 主流互读与 [`03-agent-core.md`](03-agent-core.md) **#73** 使用同一 v3 口径；旧基线 #73 为对齐。

---
## 0. 会话持久化各在哪个模块

| 后端 | pi 位置（`ea448f454`） | pi-java 位置（`edc8749d`） |
|---|---|---|
| **JSONL（主流）** | `packages/coding-agent/src/core/session-manager.ts`（`SessionManager`，头 `{type:"session", version:3}`，2,013 行） | 写线＝pi v3 形状：`agent-core/.../session/jsonl/PiV3Wire.java`（头/条目/合成行）；存储 `JsonlSessionStorage.java`；内部头记录仍名 `JsonlV4Header`，旧 `kind:"header"` 文件惰性迁移 |
| **Memory（主流）** | `SessionManager.inMemory()`（同文件 `:1804`） | `agent-core/.../session/memory/MemorySessionStorage.java`（`:31`） |
| **SQLite** | ❌ 无（`session-backends` 已删，从未上 `bin pi`） | `pi-java-session-backend-sqlite/`（2,924 行 main；**参照物不存在**，R1/R5 移出分母） |
| **契约层** | `SessionManager` 的公开方法（无独立 `Storage`/`SessionRepo` 接口） | `agent-core/.../session/SessionStorage.java` ＋ `SessionRepository.java` |

**易混点（保留）**：`pi-java-coding-agent/.../core/session/InMemorySessionRepository.java` **不是**
Memory 后端，它是 coding-agent 的**进程内会话注册表**（`-c/-r/--fork` 用）。真正的 Memory 后端在
`agent-core/.../session/memory/`。

**本轮关键变化**：上一轮重测时 java 落 `{kind:"header",version:4}` 头、条目带 `kind/seq` 信封，
与 pi v3 **双向不可读**。本基准下 java 的**写出线已切成 pi v3 形状**（包 12/14，`PiV3Wire`），
合成行（lane/record/fact）借 pi 原生 `custom`/`session_info`/`label` 条目承载，pi 侧
`parseSessionEntries` 只做 `JSON.parse`（`session-manager.ts:353-368`）⇒ **v3 双向互读已通**，
残余差异见 #16/#17。

---

## 规模（本锚点实测）

| 模块 | pi 路径 / 规模 | java（工作树） | 备注 |
|---|---|---:|---|
| 主流会话持久化 | `coding-agent/src/core/session-manager.ts` **2,013** 行 | jsonl 包 **2,539**（14 文件） | java 数含文件系统抽象/仓库层 |
| SQLite 后端（main / test） | **不存在**（参照包已删） | **2,924 / 308** | R1＋R5 作废 |
| 评测（src） | `packages/evals/src` **1,446** | **785** | pi src 两锚点间逐字节未变 |
| 评测（test / eval 用例） | `test/` **815** ＋ `evals/*.eval.ts` **856** | **412** | — |
| **chord（src / test）** | **8,817 / 9,726** | **0** | R5 不可达 ⇒ 权 0 |
| `durable`（R5 面外参照） | **22,473** | **0** | R5 不可达 ⇒ 权 0 |
| 防漂移脚本 | 根 `scripts/` **82 文件 / 10,445 行** | **0** | 口径外（非产品面） |

> pi 包结构（`git ls-tree ea448f454:packages/`）：`agent` · `ai` · `chord` · `client` · `codemode` ·
> `coding-agent` · `durable` · **`env`** · `evals` · `mcp` · `protocol` · `server` · `telemetry` · `tui`
> （**14 个，无 `session-backends`**；`env` 是 SSH 远程面，R5 面外，不在本图任何分母）。
>
> pi 侧本图引用面在 `200387122..ea448f454` 间的全部改动：`evals/package.json`、`chord/package.json`
> （版本号 → 1.1.0、`shx` 降级 devDep）与 `evals/docker/install-runtime.mjs`（制品打包脚本换用
> `local-package-install`/`package-artifacts`）—— **src 零改动**，本图 pi file:line 全部续用。

---

## 存储后端对齐（主流 `SessionManager`）

判定基准（pi `ea448f454`）：`session-manager.ts` 的 `SessionManager` 公开面（本锚点 2,013 行，
与上一锚点逐字节相同）。java 契约面：`SessionStorage.java`（`:19-93`）＋ `SessionRepository.java`
（`:12-34`）。

| # | 权重 | 能力 | pi 证据（`ea448f454`） | java 证据（`edc8749d`） | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 2 | create 建会话 | `SessionManager.create:1755`／`newSession:1057`（写 header 行 `:1064-1071`） | `SessionRepository.create:18` | 对齐 | — |
| 2 | 2 | list 列会话（扫描目录 → `SessionInfo[]`） | `static list:1900`／`listAll:1921` | `SessionRepository.list:27`；`JsonlSessionRepository.list:69` | 对齐 | — |
| 3 | 1 | list 进度回调 ＋ 部分结果增量发布 ＋ `AbortSignal` ＋ 并发扫描 | `SessionListProgress:884-889`；节流 `CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10`（`:893`）；`listSessionsFromDir:941-971`；`signal?.throwIfAborted()` `:879/:911/:946/:971` | `RichSessionLister.java`（`onProgress`＋节流增量发布＋`AbortSignal`＋并发扫描；B55 批 4 已闭环，本批仅纠账面） | 对齐 | — |
| 4 | 2 | open 打开会话 | `static open:1766` | `SessionRepository.open:24` | 对齐 | — |
| 5 | 2 | fork 分支会话 | `forkFrom:1815`／`createBranchedSession:1632` | `SessionRepository.fork:33` | 对齐 | — |
| 6 | 2 | 分支创建 / 移动 | `branch:1579`／`resetLeaf:1591`／`branchWithSummary:1600`（树 + leaf 指针） | `SessionStorage.createLane:30`／`moveLane:33`（lane 概念；落线为 pi `custom` 行，见 #16） | **存疑**（粒度不同：pi 单 leaf 树，java lane） | — |
| 7 | 3 | 追加 entry（message / thinking_level_change / model_change / custom / custom_message / usage / context_edit） | `appendMessage:1204`、`appendThinkingLevelChange:1217`、`appendModelChange:1230`、`appendCustomEntry:1290`、`appendCustomMessageEntry:1339`；落盘 `_persist:1172`（`appendFileSync :1187`） | `SessionStorage.appendEntry:41`；`Entry` 9 变体（`Entry.java:35-44`） | 对齐 | — |
| 8 | 3 | compaction 条目 | `appendCompaction:1260`；`CompactionEntry:91-104`（`summary`/`firstKeptEntryId`/`tokensBefore`/`details?`/`usage?`/`fromHook?`/`systemMessage?`） | `Entry.Compaction`（`summary`/`firstKeptEntryId`/`tokensBefore`/`details`/`usage`/`fromHook`/`systemMessage` 齐；多 `retainedTail` 扩展键，pi 忽略未知键） | 对齐 | — |
| 9 | 3 | 分支摘要条目 | `branchWithSummary:1600`（entry `:1612`）／`BranchSummaryEntry:106-118`（`fromId`/`summary`/`details?`/`usage?`/`fromHook?`） | `Entry.BranchSummary:170-179`（含 `fromHook`；账面滞后已纠） | 对齐 | — |
| 10 | 3 | compaction-aware 上下文投影（含 provenance） | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `ContextEntries.projectEntries:185-214`；`ProjectedEntry`（`:167`）携带 source 条目即 provenance；compaction 裁剪/孤儿 edit 规则同 pi（`:175-214` 注释逐行对 pi） | **对齐（形状不同）** | — |
| 11 | 3 | **`context_edit` 条目（append-only 上下文改写）** | `ContextEditEntry:175-182`（`targetId`/`replacement`）；`appendContextEdit:1360`；投影 `projectContextEntry:519-538` | `Entry.ContextEdit:283-294`；编解码 `EntryJsonCodec:75-109`；投影 `ContextEntries.applyEdit:231-250`；写线补显式 null `PiV3Wire:155-157`；生产者＝压缩/遗漏恢复（`CompactionService`、`RecoveryOmissions`） | **对齐**（**翻转**） | — |
| 12 | 2 | 会话名 | `appendSessionInfo:1304`／`getSessionName:1318`（`SessionInfoEntry:142-147`，`type:"session_info"`） | `SessionStorage.getName:72`／`setName:75`；**JSONL 线已原生落 `session_info`**（`PiV3Wire:209-214`，读回 `:237-238`）；SQLite 仍走 `facts` 表（参照已删） | **对齐**（**翻转**；内部仍 Fact 模型） | — |
| 13 | 1 | entry 标签 | `getLabel:1432`／`appendLabelChange:1441`（`LabelEntry:135-141`，`type:"label"`） | `SessionStorage.getLabel:78`／`setLabel:81`；**JSONL 线原生落 `label`**（`PiV3Wire:217-223`，读回 `:239-241`） | **对齐**（**翻转**） | — |
| 14 | 2 | usage 条目 ＋ 会话用量汇总 | `UsageEntry:80-90`（`type:"usage"`）；`appendUsage:1244`；`core/usage-totals.ts` | `Entry.Usage`（pi 九键逐字 ＋ runId/entryId 等审计扩展键）；D6 后账随一等 entry 走（`SessionState:325-328`、SQLite `SqliteMutationReplay` D6 注释）；用户可见汇总在 `coding-agent`：`SessionStats.UsageTokens(input,output,cacheRead,cacheWrite,total)`＋`UsageCostBreakdown` | 对齐 | — |
| 15 | 3 | **JSONL 头部线格式** | `SessionHeader:43-56`＝`{type:"session",version?:3,id,timestamp(ISO),cwd,parentSession?}`；写点 `:1064-1071`；`CURRENT_SESSION_VERSION = 3`（`:41`）；pi 靠 `e.type==="session"` 找头（`:1086`） | `PiV3Wire.encodeHeader:75-94`（同六键、ISO 毫秒）；读 `:97-121`：pi v3 头 ＋ 本仓旧 `kind:"header"` 头（`JsonlCodec.parseHeader:113-145`） | **对齐**（**翻转**） | — |
| 16 | 3 | **JSONL 条目线格式（type 词表 ＋ 基字段）** | 基字段 `SessionEntryBase:57-63`＝`{type,id,parentId,timestamp(ISO)}`（**无 `seq`**）；`SessionEntry:183-197` 11 个 type | `Entry` 9 变体（`Entry.java:53-61`）＋ label/session_info 走 Fact 族读写；`PiV3Wire.encodeEntryLine:139-158`（去 seq、ISO 时间戳，`lane` 保留为 pi 容忍的扩展键）；compaction 的 `retainedTail` 同为 pi 忽略键（D190）；合成行＝pi 原生 `custom`＋`customType`；seq 由行号重建 | 对齐 | map/03 **#73** |
| 17 | 2 | **JSONL 旧版本读取（pi v1→v2→v3 迁移）** | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`（读后 `_rewriteFile()`） | `LegacyPiVersionMigrator`（v1→v2→v3 节点级迁移＋原子重写；`load` 严格解析前调用）；`PiV3Wire.parseHeader` 接受 1/2/3，>3 报 `unsupported session version`（D192–D194） | 对齐 | — |
| 18 | 2 | JSONL 文件名 / 目录名 | `:1078-1080`＝`${ISO.replace(/[:.]/g,"-")}_${id}.jsonl`（**不 encode**）；`getDefaultSessionDirPath:589-594` | `JsonlSessionRepository.sessionFileName:250-253` 同形；`sessionDirectoryName:245-247` 同形 | 对齐 | — |
| 19 | 2 | **会话文件惰性创建（首个 user/assistant 消息才落盘）** | `_hasConversation:1166-1170`；`_persist:1172-1191`（setup 条目只留内存） | `JsonlSessionStorage.create` 不落盘＋`pending` 缓冲；`appendEntry` 后 `flushIfConversationStarted` 一次写 header＋全部缓冲；fork 走 `createImmediate` 立即物化（D191） | 对齐 | — |
| 20 | 1 | 内存后端 | `SessionManager.inMemory:1804` | `MemorySessionStorage:31` ＋ coding-agent 注册表 `core/session/InMemorySessionRepository.java`（pi 单类与 Java「存储＋注册表」是分层，不是缺失） | 对齐 | — |

**行数**：20 行 —— 对齐 **19** / 缺失 **0** / 存疑 **1**。
**权重**：**Σ = 44**（20 行全部进分母）。
**Σ(w×c) = 42.0（对齐 19 行）＋ 1.0（存疑 #6，w2×0.5）＝ 43.0** ⇒ **加权完成度 = 43.0/44 = 97.7%**。

---

## ~~SQLite schema 逐表对齐~~ —— **整段作废（R1＋R5，移出分母），B59 销号**

pi 主流没有 SQLite 后端，`packages/session-backends` 已删且删除前从未上 `bin pi`。
原文 18 行（Σ权重 40）不进任何分母；**不主张删 pi-java 模块**。
本基准下该模块的实质变化（002 迁移给合成行补 `parent_id`/`timestamp`、载荷走 `SessionWireShape.toPiTree`、
usage 提为一等 entry）都是**为 pi v3 互读服务**的，其判定效应已计入上表 #11–#16，不再单列为缺口。

---

## 评测对齐

pi 侧（`ea448f454`）：`packages/evals/src` **1,446 行，两锚点间逐字节未变**；`evals/*.eval.ts` 同样未变。
本窗口仅有的非 src 改动：

| 文件 | 改动 | 影响 |
|---|---|---|
| `package.json` | version `1.0.2 → 1.1.0`；内部依赖 `^1.1.0`；`shx` devDep `0.4.0 → 0.3.4` | 版本号，**框架集合不变**（仍 `vitest-evals`＋`@vitest-evals/core`＋`autoevals`） |
| `docker/install-runtime.mjs` | 制品打包换用 `scripts/local-package-install.mjs`＋`scripts/package-artifacts.mjs`（原 `coding-agent-consumer`/`release-packages` 已删） | Docker 装配脚本重构，能力面不变 |

pi-java 侧：自研框架 `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件。
本窗口 2 个主文件改动，均**不翻判定**：
`ChatApiConformanceSuite.java:156` 跟 B107 删 `StreamRequest` 多余参数；
`ProviderCatalogConformance.java:18/31-35` 内置 provider 计数 18 → **19**。

| # | 权重 | 能力 | pi 证据（`ea448f454`） | java 证据（`edc8749d`） | 判定 |
|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval`） | `evals/documentation-audit.eval.ts:6/43`、`tui.docs.eval.ts:13` | `api/EvalSuite.java:8` | 对齐（形状不同） |
| 2 | 3 | **真 coding-agent harness**（临时目录真会话 + transcript/usage 回读） | `src/harness.ts:471-475`（`createPiCodingAgentHarness`）、`:517-523`（`createPiDocumentationEvalHarness`） | `harness/PiCodingAgentHarness.java`（临时 home/workspace/sessionDir 真会话）、`IsolatedEnvironment.java`（属性隔离）、`HarnessRun.java`（transcript/usage/artifacts 回读） | **对齐** |
| 3 | 2 | **LLM-as-judge 评分** | `tui.docs.eval.ts:12-13`（`Levenshtein`+`createJudge`）、`extensions.docs.eval.ts:2`（`StructuredOutputJudge`/`ToolCallJudge`） | `judge/{StructuredOutputJudge,ToolCallJudge,LevenshteinJudge}.java`＋`api/EvalResult.java`（score/reason） | **对齐** |
| 4 | 2 | baseline vs candidate A/B 对照表 + 变体计划 | `src/report.ts:36-75`；`src/plan.ts:1/39` | `plan/TaskPlan.java`（交替变体顺序）＋`plan/DocumentationVariant.java`＋`plan/EvalCaseDescriptor.java` | **对齐** |
| 5 | 2 | 对照报告汇总（paired metric / correctness lift） | `src/report.ts:359/436` | `report/ComparisonReport.java`（配对/blocked/lift/flags）＋`ComparisonReportText.java` | **对齐** |
| 6 | 2 | reporter 落产物（`session.jsonl`） | `src/report.ts:123`（`persistSession`）、`:101`（`classifyCaseStatus`） | `report/EvalObservation.java`／`CaseStatus.java`＋`cli/ArtifactLayout.java`＋`HarnessRun.sessionFile` | **对齐** |
| 7 | 2 | 会话 JSONL 快照作为 test attachment | `src/report.ts:9`（`PI_SESSION_SNAPSHOT_ARTIFACT`） | `PiCodingAgentHarness.PI_SESSION_SNAPSHOT_ARTIFACT`＋`HarnessRun.sessionFile` | **对齐** |
| 8 | 2 | 从 run 里查工具调用（`toolCalls`） | `evals/documentation-audit.eval.ts:6/59` | `harness/TranscriptEvents.toolCalls`＋`HarnessRun.toolCalls/successfulToolCalls` | **对齐** |
| 9 | 3 | eval 运行器 CLI | `src/cli.ts`（`parseEvalCli`/`compareDiscovery`）；`package.json` 双轨入口 | `cli/EvalCli.java`＋`EvalCatalog.java`＋`ArtifactLayout.java`（`.eval/<ISO>_<uuid>/`） | **对齐** |
| 10 | 2 | docs 审计 eval | `evals/documentation-audit.eval.ts:10-59`（verdict 含 `inconclusive`） | `contents/DocumentationAuditEval.java`（页面级 case＋内联 `submit_documentation_audit`） | **对齐** |
| 11 | 2 | 扩展作者 eval | `evals/extensions.docs.eval.ts:1-40`（走 judge） | `contents/ExtensionsDocsEval.java`（`bash` 臂＋内联 `install_extension`＋reload＋judge） | **对齐** |
| 12 | 2 | 模型作者 eval | `evals/models.docs.eval.ts` + `configured-runtime.ts` | `contents/ModelsDocsEval.java`＋`fixtures/ConfiguredRuntimeInspector.inspectAddedModel` | **对齐** |
| 13 | 2 | provider 集成 eval（本地 HTTP mock server） | `evals/acme-server.ts:1-154` + `custom-provider.docs.eval.ts` + `openai-provider.docs.eval.ts` | `fixtures/AcmeServer.java`＋`contents/{CustomProviderDocsEval,OpenAiProviderDocsEval}.java`＋`ConfiguredRuntimeInspector.inspectProvider` | **对齐** |
| 14 | 3 | **ChatApi 协议一致性套件（C1–C10）** | **无对应物** | `conformance/ChatApiConformanceSuite.java:24/:33-45` | 对齐（java 反超） |
| 15 | 2 | 简单 smoke eval | `evals/smoke.eval.ts:10-16` | `contents/SmokeEval.java`（单 case「capital of France」、空工具） | **对齐** |
| 16 | 3 | eval 框架本体 | **外部 npm ×3**（`vitest-evals`/`@vitest-evals/core`/`autoevals`） | 自研（`evals/api/*.java`） | 对齐（自研替代） |
| 17 | 1 | 框架单测 | `test/*.test.ts`（815 行） | `src/test/...`（harness/judge/plan/report/docker/contents/fixtures 七组） | 对齐 |
| 18 | 3 | **Docker eval 执行** | `src/docker.ts:38`＋`docker/Dockerfile`＋`docker/entrypoint.ts` | `docker/{DockerContext,DockerImageSpec,DockerCommandBuilder,DockerProcess,DockerEvalRunner,JUnitXmlReader}.java`＋`docker/Dockerfile`（D182 边界：真实构建不进 CI） | **对齐** |
| 19 | 2 | **文档变体任务计划** | `src/plan.ts:1`（`DOCUMENTATION_VARIANTS`）、`:39`（`createTaskPlan`） | `plan/TaskPlan.java`＋`plan/DocumentationVariant.java`＋`harness/DocumentationVariants.java`（提示变换两变体） | **对齐** |
| 20 | 2 | **TUI 文档审计 eval** | `evals/tui.docs.eval.ts:12-13/192/229` | `contents/TuiDocsEval.java`（Java 文档问答＋Levenshtein 0.75）＋`fixtures/RecordingFooterOracle.java`（D183 边界：非 pi context-footer oracle） | **存疑** |

**行数**：20 行 —— 对齐 19 / 缺失 0 / 存疑 1。
**权重**：**Σ = 43** ⇒ **Σ(w×c) = 41.0（对齐）＋ 1.0（存疑 #20）＝ 42.0** ⇒ **加权完成度 = 42.0/43 ≈ 97.7%**（批 32，docs/44：15 行缺失/存疑→对齐，`#20` 缺失→存疑＝D183）。
评测缺口的汇总见 `docs/05-module-alignment-map.md` §2.6（台账 `docs/04` 的 B258 已随批 32 销号）。

---

## chord（**R5 不可达 ⇒ 权 0，不进分母**）

`ea448f454` 下 `packages/chord`：src **8,817** / test **9,726**，版本 **1.1.0**。
能力面在 `delta/`（`tracker.ts` 2,205 行、`diff.ts` 523、`index.ts` 694 等）与 `services/*`。

**pi-java 有无对应物：零。** 无 chord/facet/replicated state 的业务命中。

**消费者**：`packages/{client,coding-agent,protocol,server,durable}/package.json` 五个包
（＋ `coding-agent/examples/plugins/pi-example-plugin`）；`packages/agent` 不依赖 chord。
这些消费者全在 `bin pi` 主流口径之外（`durable` 不可达；`protocol`/`client`/`server` 按 R5 不进本图分母）
⇒ **整段权 0**。

> 旧图 21 行能力清单不重列（权重 0）。**保留更正**：`/share` 与 chord 无关
> （`modes/interactive/session-share.ts` 零 chord import，本锚点复核仍为 0），它属 coding-agent 命令面；
> pi-java 侧对应 `pi-java-coding-agent/.../core/SessionShare.java`。

---

## 多进程 / 多端子系统（`coding-agent/src/experimental/**`，**R5 不可达 ⇒ 权 0**）

`experimental/` 本锚点仍在：`{durable,vacation,services,plugins}/`、`session-catalog.ts`、
`session-worker.ts`（import `pi-durable`，2 处）、`coordinator.ts`/`server.ts`/`client*.ts` 等。
全部在 `experimental/` ⇒ R5 不可达，旧 C-1~C-4（Σ11）权 0。

pi-java 近似物仍是 `pi-java-server`（单进程、JVM 内、Unix socket 控制面 + 独占租约 + 快照订阅），
无子进程 worker / coordinator / facet-service。

---

## 防漂移脚本（**口径外 —— 非 `bin pi` 产品面**）

pi `scripts/` 是仓库自身构建/发布/校验工具，按 R5 **不进产品加权分母**，作开发流程观察保留。
本锚点 **82 文件 / 10,445 行**（上一锚点 48 / 9,060）。增量两类：
① `scripts/issues/` 工单分诊工具包（约 20 文件，categories + github/triage/register）；
② 制品打包重构：新增 `package-artifacts.mjs`(+test)、`local-package-install.mjs`(+test)、
`coding-agent-smoke.mjs`(+test)、`check-package-install.mjs`、`use-local-packages.mjs`，
删除 `coding-agent-consumer.mjs`(+test)（`evals/docker/install-runtime.mjs` 随迁）。

`check` 链（根 `package.json`，本锚点 8 步）：`biome check --write --error-on-warnings` ＋
`check:pinned-deps` ＋ `check:runtime-deps` ＋ `check:ts-imports` ＋ `check:entry-graphs` ＋
`check:install-lock:coding-agent` ＋ `tsc --noEmit` ＋ `check:browser-smoke`（无 shrinkwrap 门）。

**pi-java 实际存在的漂移预防机制（file:line，本仓工作树）**：

0. **锚点漂移检测**（本仓独有）—— `docs/map/check-drift.sh` ＋ `docs/map/ANCHOR.md`：
   `PI_PIN=ea448f454`；脚本比 **pi 本地 HEAD vs PI_PIN** 的提交距离（落后即退出 1），再从 7 份地图抽
   `` `foo.ts:行号` `` 引用的 **basename**，按 pi 内同名文件数分两栏：**【确定】**（basename 唯一）／
   **【可能】**（有同名文件，上界，如 `types.ts` 有 17 个同名）。本锚点实测 HEAD＝PI_PIN，报「✓ 无漂移」。
   限制：只圈范围，行号是否仍命中要人工核。
1. **Checkstyle**（Maven `validate`，`failsOnError=true`）—— 根 `pom.xml:142-171`；
   规则 `LineLength max=120`（`checkstyle.xml:12-13`）、`FileLength max=500`（`:18-19`）。
2. **SpotBugs**（`verify`，`effort=Max`/`threshold=Low`）—— `pom.xml:174-190` ＋ `spotbugs-exclude.xml`。
3. **maven-enforcer** —— `pom.xml:118-133`：`requireJavaVersion [25,26)` ＋ `dependencyConvergence`
   ＋ `banDuplicatePomDependencyVersions`。
4. **L5 跨语言差分一致性框架**（pi-java 独有）—— `pi-java-agent-core/src/test/.../harness/conformance/`
   （`ConformanceTest`/`ConformanceRunner`/`ConformanceScript`/`ConformanceDiff`）：逐剧本与 pi 录制真相比帧。
5. **同族后端分组矩阵** —— `session/ConformanceGroup{1,2,3}Test`（× Memory/Jsonl/Sqlite 后端）。
6. **CI 门** —— `.github/workflows/ci.yml:35` 单步 `./mvnw clean verify --batch-mode`（三平台矩阵）。

---

## 汇总（未加权）

参与对齐判定的只有存储后端（20 行）＋ 评测（20 行）＝ **40 行**：

| 档 | 存储后端 | 评测 | 合计 |
|---|---|---|---|
| **对齐** | 19 | 19 | **38** |
| **缺失** | 0 | 0 | **0** |
| **存疑** | 1 | 1 | **2** |
| 合计 | 20 | 20 | **40** |

**未加权完成度（按行数）= 38 / 40 = 95.0%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）
= (38 + 2×0.5) / 40 = 39 / 40 = 97.5%**（权重口径 97.7%，见下节）。

### 移出分母的整块（R1 / R5 / 口径外）

| 子系统 | 参照规模（`ea448f454`） | 旧行数 | 旧 Σw | 处置 |
|---|---|---:|---:|---|
| SQLite schema | 参照包不存在（已删、从未主流） | 18 | 40 | **R1＋R5（B59 销号）** |
| chord | 8,817 src（R5 不可达） | 21 | 55 | **R5** |
| 多进程子系统 | `experimental/**`（R5 不可达） | 4 | 11 | **R5** |
| harness-only 存储单元 | 旧 harness `session/types.ts`（R1 已删） | 15 | — | **R1**（并入现行 20 行表重判） |
| `durable` / pico3 / micro | `durable` 22,473 行 R5 不可达；pico3/micro R1 已删 | 44 | 44 | **R1 / R5** |
| 防漂移脚本 | `scripts/` 10,445（非产品面） | — | 12 | **口径外** |

---

## 加权汇总

**权重规则**：权重 = 用户可观察影响 × 频率（**3** = 每轮对话都走；**2** = 每次会话走；**1** = 低频/边缘；
**0** = 非目标，排除出分母）。**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权（按系数） |
|---|---:|---:|---:|---:|
| 存储后端 | **44（20 行）** | **43.0** | **97.7%** | 95.0%（19/20） |
| ~~SQLite schema~~ | **0（作废）** | 0 | — | — |
| 评测 | **43（20 行）** | **42.0** | **97.7%** | 95.0%（19/20） |
| **本范围（R5 可达）** | **87（40 行）** | **85.0** | **97.7%** | **95.0%（38/40）** |


### 权重 0（排除出分母）的行

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | 写者租约 / 心跳续租 | pi 主流从未有租约 ⇒ 非目标（java 自研，A17） |
| SQLite schema | 全部 18 行 | 参照物不存在（R1）＋从未主流（R5） |
| chord | 全部 | R5 不可达 |
| 多进程 | 全部 | R5 不可达 |
| harness-only / `durable` / pico3 / micro | 全部 59 行 | R1（删除）/ R5（不可达） |
| 防漂移脚本 | 全部 | 非 `bin pi` 产品面 |

## 存疑清单（2 个判定单元，2 行）

| # | 能力 | 存疑点 |
|---|---|---|
| S1 | 分支创建/移动（#6） | pi 单 leaf 树（`branch`/`resetLeaf`），java lane（`createLane`/`moveLane`）⇒ 语义粒度不同（落线借 `custom` 行） |
| S7 | TUI 文档审计（评测 #20） | pi 用 context-footer oracle（`42.2%/272k` 状态行）；java TUI 无该行，以 Java 文档问答＋Levenshtein 0.75 替代（D183） |
