# docs/44 — 批 32 设计稿：evals 域收口（真 harness／判分／A/B 报告／CLI／Docker／host eval）

## §0 背景与范围

【背景摘要】批 31 闭环后，按 `docs/06` §3 序 25 收口 evals 域。`docs/map/06-session-evals-chord.md` 的评测表 20 行当前 **12.5/43 ≈ 29.1%**，是 9 个进分母域里最弱的一块；`docs/04` 没有独立 evals 台账项，本批同时立项 B258 作为载体，闭环时即销号。

本稿术语：

- **host eval**：在本机用真实模型跑普通用例（pi `*.eval.ts` 中不带 `.docs.` 的那些）。
- **docs-lift eval**：同一用例在 `without_docs` 与 `with_docs` 两个变体下各跑一次，配对算 lift（pi `*.docs.eval.ts`）。
- **arm**：一次 `(case, variant, model, runNumber)` 执行单元。
- **oracle**：eval 产出后用于判定的观测器（例如 TUI 的 footer 渲染结果）。
- **模型可见面**：模型实际收到的系统提示、消息与工具 schema。本批只有 `without_docs` 的 docs 段移除进入模型可见面，其余是评测基础设施。

### 已定范围裁决（设计审核点，见 §11）

1. **Docker（行 18，w3）做编排全链，验收用假进程**。Java 版 `Dockerfile`、镜像 target、发现/计划/单臂执行与 artifact 读取全部落地；CI 不真实构建镜像（需要网络与 Maven 依赖缓存）。判定记**对齐**，边界登记 D182。
2. **TUI eval（行 20，w2）做 Java 等价文档审计**。Java TUI 没有 pi 的 `42.2%/272k` 内置 context 百分比状态行，不借本批扩 TUI 功能。改为对 Java TUI 文档做问答式审计（判分用自己的 Levenshtein/结构化判定），判定记**存疑**，边界登记 D183。
3. **`without_docs` 走 pi 的提示变换语义，不新增生产开关**。用「渲染后整份提示 → 删除 `<docs>` 段 → 写回 harness」的变换（pi `excludePiDocumentation` 对位），并把 `before_agent_start` 事件里的 `systemPrompt` 修为 pi 的「渲染后提示」，不在系统提示装配里加 eval 专用标志。
4. **扩展作者 eval 允许 shell**。Java 扩展是编译产物（JAR），pi 的 docs eval 只给 `read/write/edit/grep/find/ls`，无法编译。该 eval 工具集额外给 `bash`，边界登记 D184。

### 范围内

| 单元 | 本批交付 |
|---|---|
| 行 2 / 7 / 8 / 19 | 真 coding-agent harness：临时 home/workspace/sessionDir、真会话、transcript/usage/timing 回读、`session.jsonl` 快照、`toolCalls` 查询、文档变体任务计划 |
| 行 3 / 4 / 5 / 6 | 判分器（结构化/工具调用/Levenshtein）、A/B 配对、lift/flags、报告落盘、reporter 写 `session.jsonl` |
| 行 9 | `EvalCli`：用例发现、`-t` 过滤、provider/model/runs、artifact 目录与退出码 |
| 行 10 / 11 / 12 / 13 / 15 / 20 | 六类 host eval 与本地假 provider/HTTP 服务 |
| 行 18 | Docker 两 target 编排、发现、单臂执行、JUnit XML 读取 |
| 行 14 / 16 / 17 | 既有 ChatApi 一致性套件、自研框架、框架单测保持对齐，不重做 |

### 范围外

- TUI 的 context 百分比状态行、屏域与其它 TUI 长尾（序 27）。
- examples 六批（序 28）。
- 真实模型、真实 Docker、真实外网的自动化验收。host eval 用例默认跳过，验收用假 provider/本地 HTTP/假进程钉住契约。
- `pi-java-session-backend-sqlite` 的 B59 作废段与 chord/durable（权 0）。

---

## §1 现状与账面

### 20 行逐行实算（map/06 当前口径）

| # | 权重 | 能力 | java 现状 | 判定 |
|---|---:|---|---|---|
| 1 | 1 | 声明式 eval 定义 | `api/EvalSuite.java` 形状不同但存在 | 对齐 |
| 2 | 3 | 真 coding-agent harness | 无 | 缺失 |
| 3 | 2 | LLM-as-judge 评分 | `EvalResult` 只有布尔 | 缺失 |
| 4 | 2 | A/B 对照表＋变体计划 | 无 | 缺失 |
| 5 | 2 | 对照报告汇总/lift | 无 | 缺失 |
| 6 | 2 | reporter 落 `session.jsonl` | `EvalReporter` 不落盘 | 缺失 |
| 7 | 2 | 会话 JSONL 作为 test attachment | 无 | 缺失 |
| 8 | 2 | `toolCalls` 查询 | 无 | 缺失 |
| 9 | 3 | eval 运行器 CLI | `EvalRunner.runAll` 纯内存 | 存疑 |
| 10 | 2 | docs 审计 eval | 无 | 缺失 |
| 11 | 2 | 扩展作者 eval | `ExtensionLifecycleTest` 只验证加载 | 存疑 |
| 12 | 2 | 模型作者 eval | 无 | 缺失 |
| 13 | 2 | provider 集成 eval/mock server | `ProviderSmokeTest` 真 ping 默认跳过 | 存疑 |
| 14 | 3 | ChatApi 一致性 C1–C10 | 有，java 反超 | 对齐 |
| 15 | 2 | 简单 smoke eval | `ProviderSmokeTest` 非 eval 语义 | 存疑 |
| 16 | 3 | eval 框架本体 | 自研 `api/*` | 对齐 |
| 17 | 1 | 框架单测 | 412 行 | 对齐 |
| 18 | 3 | Docker eval 执行 | 无 | 缺失 |
| 19 | 2 | 文档变体任务计划 | 无 | 缺失 |
| 20 | 2 | TUI 文档审计 eval | 无 | 缺失 |

汇总：**Σ 权重 43**；对齐 4 行＝8.0，存疑 4 行＝4.5，缺失 12 行＝0 ⇒ **12.5/43 ≈ 29.1%**。旧汇总「20 行多缺失」与逐行一致，**无纠错**。

### 台账立项

- 新增 **B258**：evals 域缺口无独立台账项（本次立项载体）。范围＝上表 20 行；闭环时随本批销号。
- 既有工单不重开：B59 已作废（R1＋R5）；`docs/04 §7` 的批 31 三条教训不属本域。

---

## §2 总体结构与数据流

### 模块分工

| 模块 | 新增/调整 |
|---|---|
| `pi-java-agent-core` | `AgentHarness.currentSystemPrompt()`（渲染后整份提示，pi `getCurrentSystemPrompt` 对位） |
| `pi-java-coding-agent` | `before_agent_start` 事件传渲染后提示；`ExtensionManager.registerInline(...)`＋`AgentSession.addInlineExtension(...)`（pi `inlineExtensions` 对位）；`/reload` 后保留 inline 扩展 |
| `pi-java-evals` | 六个新分包：`harness`／`judge`／`plan`／`report`／`cli`／`docker`＋`contents`（eval 定义）＋`fixtures`（Acme 服务与观测器） |

`pi-java-evals` 继续只依赖 `agent-core`＋`coding-agent`＋JUnit/AssertJ；**不新增对 `tui` 的依赖**（决定行 20 的等价形态，见 §8.7）。

### 新分包与文件（主源，均 ≤500 行）

```
com.pijava.evals
├─ api/                （既有：EvalSuite/EvalCase/EvalContext/EvalResult/EvalReporter）
├─ harness/
│  ├─ PiCodingAgentHarness.java     真会话执行器
│  ├─ HarnessOptions.java           输入选项（tools/customTools/workspaceFiles/transform/expectedDocs）
│  ├─ HarnessRun.java               输出（response/transcript/usage/timing/artifacts）
│  ├─ IsolatedEnvironment.java      临时 home/workspace/sessionDir 与属性还原
│  ├─ WorkspaceSeeder.java          写 workspaceFiles
│  ├─ TranscriptEvents.java         Message→message/tool_call/tool_result 映射
│  └─ DocumentationVariants.java    excludePiDocumentation/verifySystemPrompt
├─ judge/
│  ├─ Judge.java / JudgeResult.java
│  ├─ StructuredOutputJudge.java
│  ├─ ToolCallJudge.java
│  └─ LevenshteinJudge.java
├─ plan/
│  ├─ DocumentationVariant.java     without_docs/with_docs
│  ├─ EvalCaseDescriptor.java       <eval set> > <case>
│  └─ TaskPlan.java                 每 run 交替变体顺序
├─ report/
│  ├─ CaseStatus.java               passed/failed/skipped/pending/unscored/errored/missing/duplicate
│  ├─ EvalObservation.java          score/usage/duration/artifacts/metrics
│  ├─ ObservationJson.java          归一 JSON 读写
│  ├─ ComparisonReport.java         配对、blocked、lift、flags
│  └─ ComparisonReportText.java     文本报告
├─ cli/
│  ├─ EvalCli.java                  main/参数解析/编排
│  ├─ EvalCliOptions.java
│  └─ ArtifactLayout.java           `.eval/<ISO>_<uuid>/…`
├─ docker/
│  ├─ DockerContext.java            仓库根、镜像前缀、auth 文件
│  ├─ DockerImageSpec.java          两 target 名与 build args
│  ├─ DockerCommandBuilder.java     run 参数逐项
│  ├─ DockerProcess.java            进程缝（真/假）
│  ├─ DockerEvalRunner.java         discover/runTask/读 JUnit XML
│  └─ JUnitXmlReader.java           单臂报告解析
├─ contents/
│  ├─ SmokeEval.java
│  ├─ DocumentationAuditEval.java
│  ├─ ExtensionsDocsEval.java
│  ├─ ModelsDocsEval.java
│  ├─ CustomProviderDocsEval.java
│  ├─ OpenAiProviderDocsEval.java
│  └─ TuiDocsEval.java
└─ fixtures/
   ├─ AcmeServer.java               JDK HttpServer，SSE/openai 两车道
   ├─ ConfiguredRuntimeInspector.java
   ├─ RecordingFooterOracle.java    行 20 的 Java 等价观测器
   └─ EvalFixtures.java             假 provider/session 夹具
```

### 关键数据流

1. **单臂执行**：`EvalCase` 取 `HarnessOptions` → `IsolatedEnvironment.open()`（临时 home/workspace/sessionDir，保存并覆盖 `user.home`/`user.dir`）→ `AgentSession.create(args, providers, toolContext)` → 安装 inline 扩展（prompt 变换）→ `processPrompt` → 读 `accumulatedMessages`/`SessionStats.of`/`sessionFile` → `HarnessRun` → 关闭会话、还原属性、清理目录。
2. **docs-lift**：`TaskPlan` 把每个 case 扩成 `(variant, run)` 序列，奇数 run 先 `with_docs`、偶数 run 先 `without_docs`；每个 arm 独立进程内串行执行（属性隔离不可并发）。
3. **判定**：`Judge` 输入 `HarnessRun` 与用例期望，产出 0..1 分与理由；`EvalResult` 记 `score/reason`，布尔 `passed` 由阈值派生（`judgeThreshold=null` ⇒ 不判失败）。
4. **报告**：`EvalObservation` 落 `observations.jsonl` → `ComparisonReport` 按 `(case, run)` 配对两变体 → 计算 lift、标记 blocked/flags → `report.json`＋`report.txt`；任一 blocked ⇒ CLI 退出码 1。
5. **Docker**：`buildImages` 两次（target 名 `without-docs`/`with-docs`）→ 两镜像内 `discoverCases` 要求同一 cohort → `TaskPlan` 逐臂 `docker run` → 读 `/artifacts/tasks/<id>/junit.xml` → `JUnitXmlReader`。

### 文件长度与线程纪律

- 任何新文件 ≤500 行（FileLength 已是 `severity=error` 闸门）。
- 属性隔离（`user.home`/`user.dir`）是**进程级**状态：harness 用静态锁串行化，禁止并行 arm；锁内异常也必须还原属性并清理目录。

---

## §3 真 harness（行 2／7／8／19 的载体）

### 3.1 隔离环境

pi `harness.ts:285-287` 建 `root/home`、`home/.pi/agent`；Java 对应 `IsolatedEnvironment`：

- `home = root/home`；`workspace = root/workspace`；`sessionDir = root/sessions`。
- 覆盖 `user.home=home`、`user.dir=workspace`，记录旧值，`close()` 逐项还原（`null` 旧值则 `clearProperty`）。
- `Args` 至少设 `provider/model/thinking/sessionDir/noSession=false/noExtensions` 与 `noMcp=true`（docs eval 不连 MCP；host eval 可另开）。
- `ToolContext(workspace, Map.of(), DefaultShellExecutor, DefaultFileSystem)` 显式传入，避免继承真 `user.dir`。
- 清理：递归删 `root`；失败忽略（pi 同旨）。

**为何不能只靠 `PI_JAVA_PACKAGE_DIR`**：`SystemPrompts.docsSection` 只看路径字符串非空，不检查目录存在；改包路径不能隐藏 docs 段。故 `without_docs` 用 §3.4 的提示变换。

### 3.2 会话装配与 inline 扩展

`AgentSession.create(Args, ProviderRegistry, ToolContext)` 是 public（批 29 壳保留）。本批新增两个 public 缝（pi SDK 对位）：

- `AgentHarness.currentSystemPrompt()`：返回 `ContextAssembler.renderPrompt(lane)`（当前渲染后的整份提示；`customPrompt` 非空时即该文本）。由 `PiLaneEngine` 暴露包内方法后一行委托。
- `ExtensionManager.registerInline(PiExtension ext, String extensionPath)`：以 `inline:<name>` 身份走既有 `load(...)`；`AgentSession.addInlineExtension(PiExtension)` 在会话上记账，`/reload` 重建扩展后按记账重放。

`before_agent_start` 的输入改为**渲染后提示**：

```java
// PromptPipeline：pi runner.ts:1420-1472 的 event.systemPrompt 是渲染后文本
owner.extensionFirer().beforeAgentStart(expandedPrompt,
    owner.harness()::currentSystemPrompt, owner.harness().getPromptSections());
```

处理器返回 `systemPrompt` 时仍写回 `harness.setSystemPrompt(...)`（现状）。语义上这等于 pi 的整份覆盖：返回文本已含 tools/rules/cwd 段，仅缺被删掉的段。**这是对齐修正**：此前 Java 处理器看到的是空串（`lane.systemPrompt`），pi 兼容扩展拿不到渲染提示。

`HarnessOptions` 字段：

| 字段 | 语义 |
|---|---|
| `provider` / `model` | pi `resolveModelSelection` 对位；缺省读 `PI_PROVIDER`/`PI_MODEL` |
| `tools` | 激活工具名集；docs eval 默认 `read/write/edit/grep/find/ls` |
| `customTools` | 以 inline 扩展注册的 `AgentTool` 列表（`submit_documentation_audit` 等） |
| `workspaceFiles` | 相对 workspace 的文件内容表，装配前落盘 |
| `transformSystemPrompt` | `Function<String,String>`；`without_docs` 传 `DocumentationVariants::excludePiDocumentation` |
| `expectedPiDocumentation` | `Boolean`（null＝不校验；true/false 校验 `<docs>` 段存在性） |
| `noSession` | 默认 false（要 artifact） |

执行序：`seedWorkspace` → `create` → `addInlineExtension`（变换扩展 + 自定义工具扩展）→ `getSystemPrompt`/`setSystemPrompt(transformed)` → `verifySystemPrompt` → `processPrompt` → 采集 → `close`。`verifySystemPrompt` 在变换后立即校验：必须先含 `\n<rules>\n`，再按 `expectedPiDocumentation` 比对 `\n<docs>\nPi documentation` 的存在性（Java 文档首行按 pi-java 实际文本，判定只认段标签）。

### 3.3 观测与 artifact

`HarnessRun`：

- `response = session.lastAssistantText()`（不用 `lastMessage`；批 31 教训）。
- `events = TranscriptEvents.from(session.accumulatedMessages())`：`UserMessage/AssistantMessage` → `message`；`AssistantMessage.toolCalls` → `tool_call{name,arguments,status}`；`ToolResultMessage` → `tool_result{name,text,isError}`。
- `usage`：`SessionStats.of(session)` 的 input/output/cacheRead/cacheWrite/total/cost 与 `toolCalls`。
- `timing`：`System.nanoTime` 起止与单轮毫秒。
- `systemPromptSha256`：变换后提示的 SHA-256。
- `sessionArtifact`：`session.sessionFile()` 存在则把内容写到 `<variant>/sessions/<sha256(case+run)>/session.jsonl`，并登记 artifact 键 `piSessionJsonl`（pi `report.ts:9` 对位）；无文件（noSession/内存臂）⇒ artifact 缺席，不伪造空文件。

`toolCalls(run)` 查询：`HarnessRun.toolCalls()` 返回 `ToolCallRecord(name, arguments, status)`，供 `documentation-audit` 等断言“恰一次且 ok”。

### 3.4 文档变体（`without_docs`）

`DocumentationVariants.excludePiDocumentation` 逐字对位 pi `harness.ts:494-506`：

1. 找 `\n<docs>\n` 起点，找不到 ⇒ 抛 `IllegalStateException`（提示没有 docs 段）。
2. 找 `\n</docs>` 终点，找不到 ⇒ 抛错。
3. 找最后的 `\n<cwd>\n`，若在 docs 段之前 ⇒ 抛错（提示结构异常）。
4. 返回删除 `[start, end+len(endMarker))` 的文本。

`with_docs` 不传变换；`expectedPiDocumentation=true`。`without_docs` 传变换；`expectedPiDocumentation=false`。两个校验都失败即该 arm `errored`，进入报告 blocked 路径，而不是静默算 0。

---

## §4 判分（行 3）

`EvalResult` 从「布尔」扩为：

```java
public record EvalResult(double score, String reason, Map<String,Object> metadata) {
    public boolean passed() { return score >= 1.0; }          // judgeThreshold=null 时仅记录
}
```

`Judge` 接口：

```java
public interface Judge {
    JudgeResult evaluate(HarnessRun run, Object expected);
}
public record JudgeResult(double score, String rationale, Map<String,Object> metadata) {}
```

三个实现，均为**确定性**（不引入 autoevals/LLM 判分依赖）：

- `StructuredOutputJudge`：把 `run.output()`（Object → JSON 树）与 `expected` 做严格深比较；`match="strict"` 逐键逐值，`allowExtras=false` 时禁止多余键；分数 1/0，理由列出首个差异路径。
- `ToolCallJudge`：`expectedTools=[{name, arguments}]` 按序匹配 `run.toolCalls()` 的 `ok` 记录；参数做 JSON 深比较；多出/缺失/失败均 0。
- `LevenshteinJudge`：`expected` 与 `normalized(run.response)` 的字符编辑距离归一为 `1 - d/max(len)`；用于 TUI 文档问答与文本型 oracle；空串按 1.0 视为相同。

`EvalSuite` 增加 `List<Judge> judges()` 与 `Double judgeThreshold()`（默认 `null`）。多 judge 取最小分；`null` 阈值只记录分数，不判失败（pi README:129 语义）。

---

## §5 A/B 计划与报告（行 4／5／6）

### 5.1 计划

`DocumentationVariant` 枚举 `WITHOUT_DOCS("without_docs")`／`WITH_DOCS("with_docs")`。

`EvalCaseDescriptor(String evalSet, String caseName)`；`TaskPlan.expand(cases, runsPerVariant)`：

- 每个 case 每个 run 两个 arm（两变体各一）。
- 每 run 交替顺序：`run` 为奇数 ⇒ `with_docs` 先；偶数 ⇒ `without_docs` 先（pi README:80 减少顺序偏差）。
- 臂 id＝`<evalSet>/<case>#<variant>#<run>`，供报告精确配对。
- 用例集合去重后必须稳定排序（`evalSet` 再 `caseName` 字典序），与 pi `parseDiscoveredCases` 的唯一性要求一致。

### 5.2 观测与配对

`EvalObservation`：`evalSet/case/variant/run/status/score(nullable)/reason/usage/durationMs/sessionArtifact/metrics`。

`CaseStatus` 与 pi `report.ts` 的 `classifyCaseStatus` 对位：`passed`（1 个 score）、`failed`（1 个 score 但低于阈值或显式失败）、`skipped`、`pending`、`unscored`、`errored`、`missing`、`duplicate`。

`ComparisonReport.summarize(observations, expectedArms)`：

- 按 `(evalSet, case, run)` 配对；同一臂出现 0 次 ⇒ `missing`，>1 次 ⇒ `duplicate`。
- 任一臂 `skipped/pending/unscored/errored/missing/duplicate` ⇒ 该对 **blocked**；blocked 存在时 **withhold** 头部通过率与 headline lift（仍逐对输出原因）。
- 配对成功：`control = without_docs.score`，`treatment = with_docs.score`，`delta = treatment - control`；lift ＝两组平均分之差。
- flags：`no-lift`（所有 delta＝0）、`negative-delta`（出现负 delta）、`control-saturated`（control 全 1）、`treatment-saturated`（treatment 全 1）、`flaky`（同变体多 run 分数不一致；单 run 不判定稳定性）。
- `operationalTotals`：按状态计数与总耗时；usage 缺失记 `unavailable`，不折成 0。

### 5.3 落盘

`ArtifactLayout`：

```
.eval/<ISO-8601>_<uuid>/
├─ protocol.json          provider/model/argv/开始时间
├─ expected-runs.json     TaskPlan 的臂表
├─ observations.jsonl     每臂一行
├─ report.json / report.txt
├─ tasks/<arm-id>/junit.xml        （Docker 臂；本机臂可缺）
└─ <variant>/sessions/<sha>/session.jsonl
```

`reporter` 落盘职责（行 6）：`EvalReporter.write(observation, sessionFile, artifactDir)`；`sessionFile` 为空只写 observation，不写伪 artifact。行 7 的 test attachment 由 `HarnessRun.artifacts` 暴露：`Map<String,String>`（键 `piSessionJsonl` → 文件路径），JUnit 侧由 eval 用例断言该键存在。

---

## §6 CLI 与 artifacts（行 9）

`EvalCli.main(String[])` 解析：

- 位置参数：eval 标识（`contents` 包的类名或 `evalSet/case` 模式）；缺省＝全部已注册 eval。
- `-t/--testNamePattern`：case 名正则。
- `--provider` / `--model`：缺省读 `PI_PROVIDER`/`PI_MODEL`；两者必须同时给出。
- `--runs-per-variant`：缺省 1，或读 `PI_EVAL_RUNS_PER_VARIANT`。
- `--host`：host eval 模式（不配对，直接判定）；缺省为 docs-lift 模式。

执行序（pi `cli.ts` 对位）：

1. 发现用例（`EvalCatalog` 静态注册表；Java 无 `.eval.ts` 文件，改由类注册，登记 D185）。
2. 校验 cohort：两变体发现结果必须一致，否则报错退出。
3. `TaskPlan` 展开全部臂。
4. 逐臂执行；单臂失败记入 observation，计划继续。
5. 读 observations → 报告；任一 blocked ⇒ `System.exit(1)`；否则 0。
6. 打印 artifact 目录与 `report.txt` 路径。

`EvalCatalog` 提供 `List<EvalSuite>`；每个 suite 声明其 case 集合与 harness 选项。CLI 与 JUnit 共享同一 runner，避免两套判定。

---

## §7 Docker（行 18）

### 7.1 镜像

`docker/Dockerfile` 多阶段：

- `base`：复制仓库、`mvn -DskipTests package`（依赖从远端/本地缓存取），安装 JRE 25。
- `without-docs`：在 base 上删除 `README.md`、`CHANGELOG.md`、`docs/`、`pi-java-examples/`，并设 `PI_JAVA_PACKAGE_DIR` 指向不含 docs 的桩目录（配合提示变换双保险）。
- `with-docs`：保留文档。
- 两 target 均预置 `eval-entrypoint`：`java -cp ... com.pijava.evals.cli.EvalArmMain --suite <id> --case <name> --artifacts /artifacts`。
- `DockerImageSpec` 计算镜像前缀：`pi-java-evals:<sha256(repositoryRoot 内容摘要)[0..12]>-<target>`（pi 按 repositoryRoot sha256 前缀对位）。

### 7.2 单臂命令

`DockerCommandBuilder` 逐项对位 pi `docker.ts:dockerArgs`：

- `--rm`、`--read-only`、`--network=bridge`（provider 流量需要）、`--user 65532:65532`。
- `--tmpfs /tmp:rw,size=...`、`-v <artifactDir>:/artifacts:rw`。
- `-v <authFile>:/run/pi-eval/auth.json:ro`（`requireEvalAuthFile`；缺文件直接报错，不静默匿名）。
- 环境：`PI_EVAL_CONTAINER=1`、`PI_EVAL_VARIANT=<variant>`、`PI_PROVIDER/PI_MODEL`、`PI_JAVA_PACKAGE_DIR`。
- 命令尾：`--suite <id> --case <name> --testNamePattern ^<name>$`。

`DockerEvalRunner`：

- `buildImages()` 两 target；构建失败 ⇒ 整体失败（不进入执行）。
- `discoverCases(image)` 跑 `--list-cases` 并解析 JSON 行；两镜像集合必须相同。
- `runTask(arm)` 执行单臂，读 `/artifacts/tasks/<arm>/junit.xml`；缺文件记 `missing`，解析失败记 `errored`。
- 进程缝 `DockerProcess`：生产走 `ProcessBuilder`，测试传假实现记录 argv 与返回码/XML。

**验收边界（D182）**：CI 只验证命令契约、镜像 target 名、cohort 校验、XML 读取与 blocked 路径；不真实构建镜像。真实构建留给有 Docker 与网络的环境。

---

## §8 host eval 内容（行 10–13、15、20）

六类 eval 都是 JUnit 测试（`@Tag("EVAL")`），默认 assumption 跳过；需要 `PI_PROVIDER`/`PI_MODEL` 与 `-Dpi.eval.run=true` 才执行。默认 `mvn verify` 只见跳过计数，不见网络依赖。

### 8.1 docs 审计（行 10）

`DocumentationAuditEval`：

- 用例集合＝`docs/**/*.md` 递归发现，按路径字典序；每个页面一个 case。
- 工具集 `read/grep/find/ls`＋内联工具 `submit_documentation_audit`（参数 `verdict ∈ match|mismatch|inconclusive`、`explanation` 1..2000 字符）。
- 判定：恰一次 ok 调用；`verdict=match`；`explanation` 非空。提示词逐字对位 pi `documentation-audit.eval.ts:46-57`，仓库根换成 pi-java 根。

### 8.2 扩展作者（行 11，D184）

`ExtensionsDocsEval`：

- 工具集 `read/write/edit/grep/find/ls/bash`（D184：Java 扩展需 `javac`＋`jar`）。
- 提示词改编：在 agentDir 的扩展目录创建一个 hello 工具（`name` 参数 → `Hello, <name>!`），用文档给出的 Java 扩展形态；不创建项目源码。
- 执行 `{prompt}` → `reload` → `{prompt "Use the hello tool to greet Bob..."}`。
- 判定：`toolLoaded=true`、扩展错误为空、最后一条 ok 的 `hello` 结果为 `Hello, Bob!`、最终回复逐字 `Hello, Bob!`（StructuredOutputJudge＋ToolCallJudge）。

### 8.3 模型作者（行 12）

`ModelsDocsEval`：

- 提示模型在全局 `models.json` 增加 `openai/fixture-chat`（名称、reasoning、text、0 成本、32768/4096），不替换既有模型。
- `ConfiguredRuntimeInspector.inspectAddedModel`：reload 后从 `session.modelCatalog()` 找该模型，读字段并断言既有模型条数不减少。

### 8.4 provider 集成（行 13）

`CustomProviderDocsEval`＋`OpenAiProviderDocsEval`＋`AcmeServer`：

- `AcmeServer` 用 JDK `com.sun.net.httpserver.HttpServer`：`/stream` 返回 SSE（自定义流式协议，文档 fixture 描述）；`/openai/v1/chat/completions` 返回 OpenAI 兼容 SSE。
- 提示模型按文档配置 provider 到 `models.json`（`api` 分别 `acme-stream`／`openai-completions`，baseUrl 指向本地服务）。
- 凭证：harness 在临时 home 的 `auth.json` 预置 key（pi `InMemoryCredentialStore` 的等价，避免进程环境变量不可写）。
- `ConfiguredRuntimeInspector.inspectProvider`：按配置构造 ChatApi，发一条 32 token 的探针消息，断言服务端收到合法请求、返回文本/停止原因/token 数；judge 用严格结构化比较。

### 8.5 smoke（行 15）

`SmokeEval`：单 case、单轮提示（“What is the capital of France?”），断言回复含 `Paris`（大小写不敏感）。工具集为空；不参与 docs-lift。

### 8.6 失败臂与 blocked

host eval 不进入 A/B 报告；其 observation 记 `passed/failed/errored`，`EvalCli --host` 汇总后退出码 1 当且仅当存在 failed/errored。

### 8.7 TUI 文档审计（行 20，D183）

`TuiDocsEval`：

- 用例问题从 Java TUI 文档抽取（例如“如何通过扩展注入 footer/状态行”“状态行显示哪些字段”），期望答案用 `LevenshteinJudge` 判定（阈值 0.75）。
- 走 docs-lift 两变体：`with_docs` 应显著高于 `without_docs`；报告按 §5 配对。
- `RecordingFooterOracle` 只读取 `ExtensionInjectionLayer` 的 footer 行为文本（扩展状态段/自定义 footer 行集），**不**新增 context 百分比行，也不引入 TUI 依赖；若未来 TUI 补 context 行，再替换为 pi 的 context-footer oracle。

---

## §9 实施任务拆分

| 任务 | 内容 | 主要验证 |
|---|---|---|
| T1 | 框架演进：`EvalResult` 分数/理由、`EvalSuite.judges/threshold`、`EvalReporter` 落盘 | 旧套件兼容、阈值语义、JSON 往返 |
| T2 | agent-core/coding-agent 缝：`currentSystemPrompt`、`before_agent_start` 渲染提示、`registerInline`、reload 保留 | 渲染提示内容、处理器看到全文、inline 工具注册与 reload |
| T3 | `IsolatedEnvironment`＋`WorkspaceSeeder`＋`Args` 装配 | 属性还原（含异常）、目录清理、无真 home 继承 |
| T4 | `PiCodingAgentHarness`＋`HarnessRun`＋`TranscriptEvents`＋artifact | 假 provider 单轮、tool_call/result 映射、usage、session 路径 |
| T5 | `DocumentationVariants`＋`verifySystemPrompt`＋`without_docs` 变换 | 三处结构错误、段存在性、规则段保留 |
| T6 | `Judge` 三实现 | 严格/多余键/工具序/Levenshtein 边界 |
| T7 | `plan`＋`report`（配对/blocked/flags/文本） | 缺失/重复/跳过/错误六臂、lift、saturated/flaky |
| T8 | `EvalCli`＋`EvalCatalog`＋`ArtifactLayout` | 参数默认、cohort 不一致、退出码、artifact 目录 |
| T9 | Docker 编排＋Dockerfile＋假进程 | 命令逐项、auth 缺失、XML 缺失/坏 XML、两镜像 cohort |
| T10 | `AcmeServer`＋`ConfiguredRuntimeInspector`＋两个 provider eval | 本地 SSE/OpenAI 车道、请求合法性、探针结果 |
| T11 | `DocumentationAuditEval`＋inline submit 工具＋`toolCalls` | 恰一次/ok/verdict、页面发现排序 |
| T12 | `ExtensionsDocsEval`／`ModelsDocsEval`／`SmokeEval`／`TuiDocsEval` | 各判定器、shell 臂、模型目录断言、两变体 lift |
| T13 | 全量回归、FileLength、台账回填 | 模块测试→全仓 `mvn clean verify`→地图实算 |

实施顺序 T1→T2/T3→T4/T5→T6/T7→T8/T9→T10/T11/T12→T13。每阶段独立编译、显式路径 stage、单独 commit。

---

## §10 测试设计

### 纯领域与进程缝（默认运行，不联网）

- `IsolatedEnvironmentTest`：旧值 null/非 null 两臂；`close()` 抛错仍还原；目录清理。
- `PiCodingAgentHarnessTest`：注入假 `ChatApi`/`ProviderRegistry`（脚本化 StreamEvent），单轮回复、工具调用、`SessionStats`、`sessionFile`、artifact；不依赖真实模型。
- `DocumentationVariantsTest`：缺 docs 段、缺终点、cwd 在 docs 前、正常删除、`with_docs` 不动。
- `StructuredOutputJudgeTest` / `ToolCallJudgeTest` / `LevenshteinJudgeTest`：严格匹配、多余键、参数序、空串、大小写归一。
- `TaskPlanTest`：变体交替、臂 id 唯一、run 展开。
- `ComparisonReportTest`：paired lift、missing/duplicate/skipped/errored 全 blocked、四 flags、单 run 不判 flaky。
- `ArtifactLayoutTest`：目录命名、ISO/uuid、重复创建。
- `DockerCommandBuilderTest` / `DockerEvalRunnerTest`：假 `DockerProcess` 记录 argv；auth 缺失；cohort 不一致；坏 XML。
- `AcmeServerTest`：SSE 帧与 OpenAI 兼容帧、reset、端口释放。
- `ConfiguredRuntimeInspectorTest`：模型字段、既有模型保留、provider 探针（假服务）。

### eval 用例（默认跳过）

- 六类 eval 各一个 `@Tag("EVAL")` 测试：无 `PI_PROVIDER/PI_MODEL` 或 `pi.eval.run` 未开启 ⇒ assumption skip（与既有 `ProviderSmokeTest` 同形）。
- `DocumentationAuditEval` 的页面发现与提示文本用纯函数测试（不跑模型）。
- `TuiDocsEval` 的 oracle 与期望问答用假 run 测试，确保两变体差值可计算。

### 验证纪律

1. 底层 `agent-core` 改动后，后续模块用 `mvn test -pl <module> -am`，防止拾旧 jar。
2. 不用管道接 `head` 判断 Maven 成败；以落盘日志与 `$LASTEXITCODE` 为准。
3. 不并发跑同一仓 Maven；属性隔离测试串行。
4. 最终 `mvn clean verify` 必须零错误零警告，checkstyle/FileLength 与 spotbugs 全过。
5. 测试 home/settings/auth/models-store 全部显式隔离，不继承真 home。

---

## §11 裁决与窄差

### 裁决①：Docker 编排全链做，真实构建不进 CI

- 【场景】Java 镜像构建需要 Maven 依赖与网络，CI 环境不保证；pi 的 eval 也在专用容器里跑。
- 【决定】实现镜像 target、构建/发现/单臂/读报告全链，验收用假 `DockerProcess` 钉住命令契约。
- 【风险/代价】真实镜像首次构建可能暴露 Dockerfile 问题；登记 D182，不影响逐行判定（能力面落地）。

### 裁决②：TUI eval 以文档问答替代 pi 的 context-footer oracle

- 【场景】pi 的 TUI eval 用 `42.2%/272k (auto)` 内置状态行做 oracle；Java TUI 无该行。
- 【决定】不扩 TUI 功能；用 Java TUI 文档的问答审计＋Levenshtein 判分，走两变体测文档 lift。行 20 记存疑。
- 【风险/代价】与 pi oracle 不等价；登记 D183。若后续 TUI 补 context 行，可替换为真正 oracle 再翻对齐。

### 裁决③：`without_docs` 用提示变换，不新增生产开关

- 【场景】`PiJavaPaths` 的 docs 段只看路径字符串，无法用环境变量隐藏；生产不应有 eval 专用标志。
- 【决定】`before_agent_start` 传渲染后提示＋写回整份覆盖；`excludePiDocumentation` 删除 docs 段。顺带修正 Java 处理器此前只看到 `lane.systemPrompt`（空串）的偏差。
- 【风险/代价】行为变化：扩展 `before_agent_start` 看到的 `systemPrompt` 从空串变为完整提示（pi 语义）。属对齐修正，需回归既有扩展测试。

### 裁决④：扩展作者 eval 允许 shell

- 【场景】Java 扩展是编译产物，pi 的 docs eval 工具集无法编译。
- 【决定】该 eval 额外开 `bash`，其余 docs eval 仍只用六件；登记 D184。
- 【风险/代价】工具集与 pi 不同，安全性边界由评测环境负责；不影响其它行。

### 裁决⑤：eval 用例注册表与报告方言

- 【场景】Java 没有 `.eval.ts` 文件发现与 Vitest JSON。
- 【决定】`EvalCatalog` 静态注册，Docker 臂读 JUnit XML；报告字段/语义对齐 pi，文本不追求字节相同。登记 D185。
- 【风险/代价】外部脚本若依赖文件名发现需改用类名/`evalSet/case`。

### 窄差编号

| 编号 | 内容 |
|---|---|
| D182 | Docker 镜像真实构建/执行不在 CI 验收链；编排以假进程钉住 |
| D183 | TUI 文档审计以 Java 文档问答替代 pi 的 context-footer 定制 oracle |
| D184 | 扩展作者 eval 额外允许 shell（Java 扩展需编译） |
| D185 | eval 发现用静态注册表、Docker 报告读 JUnit XML（非 `.eval.ts`/Vitest JSON） |
| D186 | provider／model eval 用内联 `inspect_provider`／`inspect_added_model` 代写 `models.json` 并探针（pi 由模型直接写配置） |
| D187 | 扩展作者 eval 经 `sessionCustomizer`＋内联 `install_extension` 装载 JAR（pi 由 `/reload` 扫描扩展目录；D90 裁决③） |
| D188 | `SmokeEval` 在 CLI 全量 docs-lift 下也会被配对（CLI 无 per-suite 标记；lift 预期为零；host 模式按设计单跑） |
| D189 | `RecordingFooterOracle` 复刻 footer 行为文本以避开 TUI 依赖，显示宽度按每 codepoint 一列近似（pi 宽字符两列） |

---

## §12 预计闭环账面

按 20 行逐行实算（实现范围即本稿）：

| 行 | 判定 | 权重×c |
|---|---|---:|
| 2/3/4/5/6/7/8/10/12/18/19 | 缺失→对齐 | 3+2+2+2+2+2+2+2+2+3+2＝24.0 |
| 9/11/13/15 | 存疑→对齐 | 3+2+2+2＝9.0（旧 4.5，净 +4.5） |
| 20 | 缺失→存疑 | 2×0.5＝1.0 |
| 1/14/16/17 | 维持对齐 | 1+3+3+1＝8.0 |

- evals：**8.0 +（9.0−4.5）+ 24.0 + 1.0 = 37.5?** 逐行重算：对齐行总分＝1（#1）+3（#2）+2（#3）+2（#4）+2（#5）+2（#6）+2（#7）+2（#8）+3（#9）+2（#10）+2（#11）+2（#12）+2（#13）+3（#14）+2（#15）+3（#16）+1（#17）+3（#18）+2（#19）＝41.0；#20 存疑 1.0 ⇒ **42.0/43 ≈ 97.7%**。
- 若行 20 最终仍记缺失 ⇒ **41.0/43 ≈ 95.3%**；若 Docker 也降存疑 ⇒ 再减 1.5。
- map/06 模块（session 32.0＋evals）：44.5→**74.0/87 ≈ 85.1%**。
- 全仓：1,175.5＋29.5＝**1,205.0/1,383 ≈ 87.1%**（分母不变）。
- 闭环时必须逐行实算；若裁决②最终按缺失记，则按 41.0 回填，不按预估值翻行。

### 文档回填

1. `docs/04`：新增批 32 段；B258 立项并销号；D182–D189；下一包号 docs/45（批 33）。
2. `docs/05` 与 `docs/map/06`：评测表 20 行逐行翻判定、模块与全仓汇总实算。
3. `docs/06`：§1 批 32 读数、§2 新行、§3 序 25 划销；下一主线为批 33（session 余量）。
4. 本稿追加 §13 闭环记录：实际改动、行为变化、测试数、verify 退出码、评审修真。

---

## §13 闭环记录

**实际改动**（2026-10-31 闭环）：

- `pi-java-evals` 新主源 **49** 文件＋`docker/Dockerfile`（两 target）：`cli` 6／`contents` 8／`docker` 6／`fixtures` 4／`harness` 12／`judge` 5／`plan` 3／`report` 5。新测试 **31** 文件（模块测试共 38 文件）；最大文件 `EvalFixtures.java` **449** 行（FileLength ≤500）。
- 缝：`pi-java-agent-core` 2 文件（`AgentHarness.currentSystemPrompt()`＋`PiLaneEngine.currentPrompt`）；`pi-java-coding-agent` 5 文件（`PromptPipeline` 传渲染后提示、`SessionEventFirer.beforeAgentStart` 入参、`ExtensionManager.registerInline`、`AgentSession.addInlineExtension` 与 reload 保留 inline 扩展、`ExtensionEventSpec/ExtensionResults` 语义面）；evals 既有 API 3 文件演进（`EvalResult` 增 score/reason、`EvalSuite` 增 judges/threshold、`EvalReporter` 落盘）。

**行为变化**（changelog 级）：`before_agent_start` 处理器看到**渲染后整份提示**（此前只有空串 `lane.systemPrompt`，pi 语义）；处理器返回 `systemPrompt` 仍写回 `setSystemPrompt(...)`（返回文本已含 tools/rules/cwd 段，等价整份覆盖，见 §5 裁决③）。既有扩展测试按此改钉。

**测试与验收**：全量 `mvn -o clean verify` 第 5 轮 **退出码 0 / BUILD SUCCESS**——**5,700 测 0 失败 0 错误 29 跳过**（telemetry 53／ai 1,753／mcp 260／agent-core 893〔1〕／sqlite 37／coding-agent 1,765／tui 599〔1〕／protocol 14／client 0（无测试）／server 2／web 59／evals 196〔27，EVAL tag 默认 assumption skip〕／examples 69）。checkstyle **0 违规**（15 模块，FileLength error 级通过）；spotbugs **13 模块零钉**。

**评审修真**（无独立评审轮；verify 驱动的撞钉与修复）：

1. `#1` `SessionLifecycleEventsTest.inputTransformFeedsExpansionAndDrive`：T2 后事件携带渲染后提示，旧钉比对 `getSystemPrompt()` ⇒ 改钉 `currentSystemPrompt()`。
2. `#2` evals spotbugs 5 钉：`Path.getParent()` 空路径三处 ⇒ `Objects.requireNonNull`（`EvalFixtures`×2、`ObservationJson`）；`IsolatedEnvironment` 跨生命周期持锁 `UL_UNRELEASED_LOCK` ⇒ 单许可 `Semaphore`；`TaskPlan` `% 2 == 1` ⇒ `% 2 != 0`。
3. `#3` TUI 既有 flake `PiTuiAppDequeueTest.escWhileStreamingRestoresQueuedThenAborts`（满载 15s 等待窗超时；隔离复跑 3.7s 绿，未改码）。
4. `#4` `PirateExtensionTest.enabledAppendsPirateSectionToSystemPrompt`：`base` 旧取 `getSystemPrompt()`（空串）⇒ 改取 `currentSystemPrompt()`，断言写回后 `getSystemPrompt() == base + 后缀`。
5. `#5` 全绿收口（即上表数字）。
