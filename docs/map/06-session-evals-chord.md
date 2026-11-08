# 06 会话后端 / 评测 / chord / 防漂移脚本

> 【背景摘要】本图覆盖四块：会话持久化（sqlite 模块与 agent-core 的 JSONL 面）、评测（evals）、chord（R5 不可达）、防漂移脚本（口径外）。核心结论：pi 主流会话持久化只有 JSONL＋in-memory，SQLite 后端无对齐参照物。

## 基准与范围

- **PI_PIN=56cae7823**（2026-10-31，`v1.4.0-265-g56cae7823`）；pi-java @ `51dc0f7c`（main，2026-11-05，L6 实施收口）。
- 本图**存储后端** 20 行已按新锚点重测并随 docs/24 实施闭环；**评测域** 24 行已按新锚点重测并随 docs/26 实施闭环（2026-11-05，L6）。
- 范围：`pi-java-session-backend-sqlite` ＋ `pi-java-agent-core` 的会话持久化面（主流对照 pi `coding-agent/src/core/session-manager.ts`）；`pi-java-evals` ↔ pi `packages/evals`；pi `packages/chord`（R5 不可达）；pi 仓库根 `scripts/` 与本仓 `docs/map/check-drift.sh`（口径外）。
- 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 `file:line`。
- 新锚点评测参照版本：`1.4.0`（`packages/evals/package.json` version 字段；旧锚点 `1.1.0`）。

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

判定基准（pi `56cae7823`）：`session-manager.ts` 的 `SessionManager` 公开面（2,030 行）。
java 契约面：`SessionStorage.java`（`:19-93`）＋ `SessionRepository.java`（`:12-34`）。
本层实施见 `docs/24`（W1–W4）；B271／B272 为 L3 转出行（权重 0），B273／B274 落在既有行，
新增 B278 落 #2／#5／#18 的显式目录语义。

| # | 权重 | 能力 | pi 证据（`56cae7823`） | java 证据（`9966a1f5`＋docs/24） | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 2 | create 建会话 | `SessionManager.create:1765`／`newSession:1057`（写 header 行 `:1062-1070`） | `SessionRepository.create:18` | 对齐 | — |
| 2 | 2 | list 列会话（扫描目录 → `SessionInfo[]`） | `static list:1915`／`listAll:1936-1942`；显式目录 cwd 过滤 `:1922,744-745` | `SessionRepository.list:27`；`JsonlSessionRepository.list:72-99`＋`cwdMatches`（B278） | 对齐 | B278 |
| 3 | 1 | list 进度回调 ＋ 部分结果增量发布 ＋ `AbortSignal` ＋ 并发扫描 | `SessionListProgress:884-889`；节流 `:893`；`listSessionsFromDir:941-971`；信号 `:879/:911/:946/:971` | `RichSessionLister.java`（`onProgress`＋节流增量发布＋`AbortSignal`＋并发扫描＋B278 cwd 过滤） | 对齐 | — |
| 4 | 2 | open 打开会话 | `static open:1776` | `SessionRepository.open:24` | 对齐 | — |
| 5 | 2 | fork 分支会话 | `forkFrom:1825`／`createBranchedSession:1642`；单 fd 写 `:1868-1877` | `SessionRepository.fork:33`；`JsonlSessionStorage.fork` 单次写（B274 W4b） | 对齐 | B274 |
| 6 | 2 | 分支创建 / 移动 | `branch:1589`／`resetLeaf:1601`／`branchWithSummary:1610`（树 + leaf 指针） | `SessionStorage.createLane:30`／`moveLane:33`；`TreeCompaction` 走 `navigateTree` 同序 | **对齐（形状不同）**：lane 是 Java 内部扩展，落线 `lane` 键被 pi 忽略 | **D218** |
| 7 | 3 | 追加 entry（message / thinking_level_change / model_change / custom / custom_message / usage / context_edit） | `appendMessage:1214`、`appendThinkingLevelChange:1227`、`appendModelChange:1240`、`appendCustomEntry:1300`、`appendCustomMessageEntry:1349`；`_persist:1172`；失败回滚 `_appendEntry:1191-1206` | `SessionStorage.appendEntry:41`；`Entry` 9 变体；`JsonlSessionStorage.appendEntry` 文件成功后才提交内存（W4a 回归锁） | 对齐 | B274 |
| 8 | 3 | compaction 条目 | `appendCompaction:1271`；`CompactionEntry:91-104`（`summary`/`firstKeptEntryId`/`tokensBefore`/`details?`/`usage?`/`fromHook?`/`systemMessage?`） | `Entry.Compaction` 十二组件齐（含 `systemMessage`）；`retainedTail` 为 pi 忽略键（D190） | 对齐 | — |
| 9 | 3 | 分支摘要条目 | `branchWithSummary:1610`／`BranchSummaryEntry:106-118` | `Entry.BranchSummary:170-179`（含 `fromHook`） | 对齐 | B272 |
| 10 | 3 | compaction-aware 上下文投影（含 provenance） | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `ContextEntries.projectEntries`＋`ProjectedEntry` | 对齐（形状不同） | — |
| 11 | 3 | **`context_edit` 条目（append-only 上下文改写）** | `ContextEditEntry:175-182`；`appendContextEdit:1370`；`projectContextEntry:519` | `Entry.ContextEdit`；`EntryJsonCodec`；`ContextEntries.applyEdit`；生产者＝压缩/遗漏恢复（`RecoveryOmissions`，B273 成对） | 对齐 | B273 |
| 12 | 2 | 会话名 | `appendSessionInfo:1314`／`getSessionName:1328`（`SessionInfoEntry:142-147`） | `SessionStorage.getName/setName`；JSONL 原生 `session_info` | 对齐 | — |
| 13 | 1 | entry 标签 | `getLabel:1442`／`appendLabelChange:1451`（`LabelEntry:135-141`） | `SessionStorage.getLabel/setLabel`；JSONL 原生 `label` | 对齐 | — |
| 14 | 2 | usage 条目 ＋ 会话用量汇总 | `UsageEntry:80-90`；`appendUsage:1254` | `Entry.Usage` 九键＋审计扩展键；`SessionStats`／`UsageCostBreakdown` | 对齐 | — |
| 15 | 3 | **JSONL 头部线格式** | `SessionHeader:43-56`；写点 `:1062-1070`；`CURRENT_SESSION_VERSION:41`；找头 `:1086` | `PiV3Wire.encodeHeader:75-94`；读 `:97-121` | 对齐 | — |
| 16 | 3 | **JSONL 条目线格式** | 基字段 `SessionEntryBase:57-63`（无 `seq`）；`SessionEntry:183-197` | `PiV3Wire.encodeEntryLine`；`lane`／`retainedTail` 为 pi 忽略键（D190） | 对齐 | map/03 **#73** |
| 17 | 2 | **JSONL 旧版本读取（v1→v2→v3）** | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`；`_rewriteFile:1124-1133` | `LegacyPiVersionMigrator`＋`JsonlSessionStorage.load`；`PiV3Wire.parseHeader` 接受 1/2/3（D192–D194） | 对齐 | — |
| 18 | 2 | JSONL 文件名 / 目录名 | `fileTimestamp:1079`；`getDefaultSessionDirPath:589-594` | `JsonlSessionRepository.sessionFileName:300-303`；`sessionDirectoryName:295-298`；显式目录平铺（B278） | 对齐 | B278 |
| 19 | 2 | **会话文件惰性创建** | `_hasConversation:1166`；`_persist:1172-1190` | `JsonlSessionStorage.create`＋`pending`＋`flushIfConversationStarted`（D191） | 对齐 | — |
| 20 | 1 | 内存后端 | `SessionManager.inMemory:1814` | `MemorySessionStorage:31`＋coding-agent `InMemorySessionRepository` | 对齐 | — |

**行数**：20 行 —— 对齐 **20** / 缺失 **0** / 存疑 **0**。
**权重**：**Σ = 44**（20 行全部进分母）。
**Σ(w×c) = 44.0** ⇒ **加权完成度 = 44.0/44 = 100%**。

**转出行（不重新计入分母）**：map/03 **#77**（恢复会话 active tools，w2）→ B271；
map/03 **#24**（branch summary 生成，w1）→ B272。两者按 docs/24 W1／W2 实施并验证。

---

## ~~SQLite schema 逐表对齐~~ —— **整段作废（R1＋R5，移出分母），B59 销号**

pi 主流没有 SQLite 后端，`packages/session-backends` 已删且删除前从未上 `bin pi`。
原文 18 行（Σ权重 40）不进任何分母；**不主张删 pi-java 模块**。
本基准下该模块的实质变化（002 迁移给合成行补 `parent_id`/`timestamp`、载荷走 `SessionWireShape.toPiTree`、
usage 提为一等 entry）都是**为 pi v3 互读服务**的，其判定效应已计入上表 #11–#16，不再单列为缺口。

---

## 评测对齐

pi 侧（`ea448f454..56cae7823`）：`packages/evals/src` **1,446 → 1,447 行（净 +1）**，只有 `report.ts` 改动 9 行；`evals/` **856 → 950 行**（+94，新增一套 prompt-template eval 与审计取证断言）；`test/` **815 → 830 行**。
`src/{harness,cli,docker,plan}.ts` 与 `docker/{entrypoint.ts,Dockerfile}` **逐字节未变**。

| 文件 | 改动 | 影响 |
|---|---|---|
| `src/report.ts` | `readTaskObservation` 改为先按 `fullName` 过滤再计数；`classifyCaseStatus` 不再导出 | 兄弟用例 skipped 不再误伤；四组配对摘要与 pass-rate lift **本来就已存在**（旧读数漏判） |
| `evals/prompt-templates.docs.eval.ts` | +79（新增） | 评测 #22 |
| `evals/documentation-audit.eval.ts` | +18/−4 | 评测 #21（取证断言） |
| `evals/tui.docs.eval.ts` | +41/−25 | `autoevals` 的 `Levenshtein` 换内联实现；判定不变 |
| `evals/extensions.docs.eval.ts` | −2 | 删 `toolLoaded` oracle；判定不变 |
| `vitest.evals.config.ts` | +12/−3 | `host` 排除审计、新增 `audit` 项目（评测 #24） |
| `package.json` | +5/−5 | `autoevals` devDep 删除（外部框架 npm **×3 → ×2**）；`vitest 4.1.11 → 4.1.12`；新增 `eval:audit` |
| `README.md` | +6/−6 | 三档运行说明 |

pi-java 侧：自研框架 `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件。
L6 落地的对应面见 §上表 #5/#21–#24。

| # | 权重 | 能力 | pi 证据（`56cae7823`） | java 证据（`51dc0f7c`＋docs/26） | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval`） | `evals/documentation-audit.eval.ts:44`、`tui.docs.eval.ts:262` | `api/EvalSuite.java`（`harnessCases`/`judges`/`judgeThreshold`） | 对齐（形状不同） | D185 |
| 2 | 3 | **真 coding-agent harness**（临时目录真会话 + transcript/usage 回读） | `src/harness.ts:471-475`（`createPiCodingAgentHarness`）、`:517-523`（`createPiDocumentationEvalHarness`） | `harness/PiCodingAgentHarness.java`（临时 home/workspace/sessionDir 真会话）、`IsolatedEnvironment.java`（属性隔离）、`HarnessRun.java`（transcript/usage/artifacts 回读） | **对齐** | — |
| 3 | 2 | **LLM-as-judge 评分** | `tui.docs.eval.ts:23-37`（内联 `levenshteinSimilarity`）、`extensions.docs.eval.ts:2`（`StructuredOutputJudge`/`ToolCallJudge`） | `judge/{StructuredOutputJudge,ToolCallJudge,LevenshteinJudge}.java`＋`api/EvalResult.java` | **对齐** | — |
| 4 | 2 | baseline vs candidate A/B 对照表 + 变体计划 | `src/report.ts:36-83`；`src/plan.ts:39`（`createTaskPlan`） | `plan/TaskPlan.java`（交替变体顺序）＋`plan/DocumentationVariant.java`＋`plan/EvalCaseDescriptor.java` | **对齐** | — |
| 5 | 2 | **对照报告汇总（paired metric / pass-rate lift）** | `src/report.ts:43-56`（`EvalSetComparison` 四组配对摘要）、`:380-402`（per-set headline ＋ pass-rate lift） | `report/ComparisonReport.java`（per-set `comparisons`）、`report/{PairedMetricSummary,EvalSetComparison}.java`＋`ComparisonReportText.java`（pi 段序） | **对齐**（L6 W1） | D228 |
| 6 | 2 | reporter 落产物（`session.jsonl`） | `src/report.ts:122-134`（`persistSession`）、`:101`（`classifyCaseStatus`）、`:147-157`（兄弟用例过滤） | `report/EvalObservation.java`／`CaseStatus.java`＋`cli/ArtifactLayout.java`＋`HarnessRun.sessionFile`；单臂模型下无需 fullName 选择 | **对齐**（形状不同） | D229 |
| 7 | 2 | 会话 JSONL 快照作为 test attachment | `src/report.ts:9`（`PI_SESSION_SNAPSHOT_ARTIFACT`） | `PiCodingAgentHarness.PI_SESSION_SNAPSHOT_ARTIFACT`＋`HarnessRun.sessionFile` | **对齐** | — |
| 8 | 2 | 从 run 里查工具调用（`toolCalls`） | `evals/documentation-audit.eval.ts:6/60` | `harness/TranscriptEvents.toolCalls`＋`HarnessRun.toolCalls/successfulToolCalls` | **对齐** | — |
| 9 | 3 | eval 运行器 CLI | `src/cli.ts:27-86`（`parseEvalCli`）、`:106-112`（`compareDiscovery`）；`package.json:8-12` 三档脚本 | `cli/EvalCli.java`＋`EvalCatalog.java`＋`ArtifactLayout.java`（`.eval/<ISO>_<uuid>/`）＋`--host`/`--audit` 档位 | **对齐** | D185 |
| 10 | 2 | docs 审计 eval（本体） | `evals/documentation-audit.eval.ts:10-30/44-58`（verdict 含 `inconclusive`） | `contents/DocumentationAuditEval.java`（页面级 case＋内联 `submit_documentation_audit`） | **对齐** | — |
| 11 | 2 | 扩展作者 eval | `evals/extensions.docs.eval.ts:1-57`（走 judge） | `contents/ExtensionsDocsEval.java`（`bash` 臂＋内联 `install_extension`＋reload＋judge） | **对齐** | D184/D187 |
| 12 | 2 | 模型作者 eval | `evals/models.docs.eval.ts` + `configured-runtime.ts` | `contents/ModelsDocsEval.java`＋`fixtures/ConfiguredRuntimeInspector.inspectAddedModel` | **对齐** | D186 |
| 13 | 2 | provider 集成 eval（本地 HTTP mock server） | `evals/acme-server.ts:1-154` + `custom-provider.docs.eval.ts` + `openai-provider.docs.eval.ts` | `fixtures/AcmeServer.java`＋`contents/{CustomProviderDocsEval,OpenAiProviderDocsEval}.java`＋`ConfiguredRuntimeInspector.inspectProvider` | **对齐** | D186 |
| 14 | 3 | **ChatApi 协议一致性套件（C1–C10）** | **无对应物** | `conformance/ChatApiConformanceSuite.java` | 对齐（java 反超） | — |
| 15 | 2 | 简单 smoke eval | `evals/smoke.eval.ts:5-18` | `contents/SmokeEval.java`（单 case「capital of France」、空工具） | **对齐** | D188 |
| 16 | 3 | eval 框架本体 | **外部 npm ×2**（`vitest-evals`/`@vitest-evals/core`；`autoevals` 本锚点已删） | 自研（`evals/api/*.java`） | 对齐（自研替代） | D185 |
| 17 | 1 | 框架单测 | `test/*.test.ts`（830 行，本锚点 +15） | `src/test/...`（harness/judge/plan/report/docker/contents/fixtures 七组） | 对齐 | — |
| 18 | 3 | **Docker eval 执行** | `src/docker.ts:38`＋`docker/Dockerfile`＋`docker/entrypoint.ts`（三件两锚点逐字节相同） | `docker/{DockerContext,DockerImageSpec,DockerCommandBuilder,DockerProcess,DockerEvalRunner,JUnitXmlReader}.java`＋`docker/Dockerfile` | **对齐** | D182 |
| 19 | 2 | **文档变体任务计划** | `src/plan.ts:1`（`DOCUMENTATION_VARIANTS`）、`:39`（`createTaskPlan`） | `plan/TaskPlan.java`＋`plan/DocumentationVariant.java`＋`harness/DocumentationVariants.java` | **对齐** | — |
| 20 | 2 | **TUI 文档审计 eval** | `evals/tui.docs.eval.ts:23-37/207-260`（context-footer oracle） | `contents/TuiDocsEval.java`（Java 文档问答＋Levenshtein 0.75）＋`fixtures/RecordingFooterOracle.java` | **存疑** | D183/D189 |
| 21 | 2 | **审计取证断言（读过页面＋读过实现源）** | `evals/documentation-audit.eval.ts:34/60-71` | `contents/{DocumentationAuditEval,AuditSourceIndex}.java`（`AuditJudge` 前置断言＋按页源选集） | **对齐**（L6 W3） | D230 |
| 22 | 2 | **prompt template 文档 eval** | `evals/prompt-templates.docs.eval.ts:1-79`（project/user 双 scope、reload、展开逐字比对） | `contents/PromptTemplatesDocsEval.java`（两 case＋`HarnessOptions.output` 观测面） | **对齐**（L6 W4） | D231 |
| 23 | 1 | **按变体的运行总量与覆盖度** | `src/report.ts:62-73`（`VariantTotals`）、`:431-435`（覆盖度渲染） | `report/VariantTotals.java`＋`report/OperationalMetricTotal.java`（`availableRuns`，不折零） | **对齐**（L6 W2） | — |
| 24 | 1 | **audit 独立执行档** | `vitest.evals.config.ts:22-43`（`host` 排除审计、新增 `audit` 项目）；`package.json:11` `eval:audit` | `EvalSuite.expensive()`＋`EvalCliOptions --audit`＋`EvalCli.byAuditGate` | **对齐**（L6 W5） | D188 |

**行数**：24 行 —— 对齐 **23** / 缺失 **0** / 存疑 **1**。
**权重**：**Σ = 49** ⇒ **Σ(w×c) = 47.0（对齐）＋ 1.0（存疑 #20）＝ 48.0** ⇒ **加权完成度 = 48.0/49 ≈ 97.96%**。

> **重判说明**：旧读数（20 行 / Σ43 / 42.0 = 97.7%）把 #5 记成「对齐」过宽——pi 的四组
> 配对标量、按变体运行总量与 **pass-rate 定义的 lift** 在 Java 侧当时都不存在，按新锚点
> 证据应为缺失。L6 W1/W2 补齐后 #5 恢复对齐，并新增 #21–#24 四行（Σ+6）。
> 唯一存疑 #20（TUI 审计 oracle 形态差）在 docs/26 §1.4 复核后维持，理由未变。
> 5 × 0.5 = 2.5 之外的缺口不存在——**唯一 −1.0 来自 #20 的存疑系数**。

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

参与对齐判定的只有存储后端（20 行）＋ 评测（24 行）＝ **44 行**：

| 档 | 存储后端（新锚点） | 评测（新锚点） | 合计 |
|---|---|---|---|
| **对齐** | 20 | 23 | **43** |
| **缺失** | 0 | 0 | **0** |
| **存疑** | 0 | 1 | **1** |
| 合计 | 20 | 24 | **44** |

**未加权完成度（按行数）= 43 / 44 = 97.73%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）
= (43 + 0.5) / 44 = 98.86%**（存储后端单独 100%，见下节；评测单独 97.96%，见上表）。

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
| 存储后端（新锚点，docs/24） | **44（20 行）** | **44.0** | **100%** | 100%（20/20） |
| ~~SQLite schema~~ | **0（作废）** | 0 | — | — |
| 评测（新锚点，docs/26） | **49（24 行）** | **48.0** | **97.96%** | 95.83%（23/24） |
| 合并快照（两域均新锚点） | **93（44 行）** | **92.0** | **98.92%** | **97.73%（43/44）** |


### 权重 0（排除出分母）的行

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | 写者租约 / 心跳续租 | pi 主流从未有租约 ⇒ 非目标（java 自研，A17） |
| SQLite schema | 全部 18 行 | 参照物不存在（R1）＋从未主流（R5） |
| chord | 全部 | R5 不可达 |
| 多进程 | 全部 | R5 不可达 |
| harness-only / `durable` / pico3 / micro | 全部 59 行 | R1（删除）/ R5（不可达） |
| 防漂移脚本 | 全部 | 非 `bin pi` 产品面 |

## 存疑清单（1 个判定单元，1 行）

| # | 能力 | 存疑点 |
|---|---|---|
| S7 | TUI 文档审计（评测 #20） | pi 用 context-footer oracle（`42.2%/272k` 状态行）；java TUI 无该行，以 Java 文档问答＋Levenshtein 0.75 替代（D183／D189；L6 §1.4 已按新锚点复核，判分核心未变，维持存疑） |

> #6 已按 docs/24 §1.5 改判「对齐（形状不同）」并登记 D218；S1 销号。
> 评测域唯一存疑是 #20；其余 23 行全部对齐，**没有第二个缺口**。
