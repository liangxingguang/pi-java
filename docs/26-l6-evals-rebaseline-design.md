# 26 - L6 evals 新锚点重测与对齐设计（W1–W7）

> 【背景摘要】按 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md) §5 的 L6 范围，对 `pi-java-evals` ↔ pi `packages/evals` 按新锚点 `56cae7823` 全量重测。
>
> 本层与 L1–L5 的形态不同：**`packages/evals/src` 两锚点间净变 +1 行（1,446 → 1,447），只有 `report.ts` 一个文件改动**。差异集中在 `evals/`（+94 行，新增一套文档 eval）与 `vitest.evals.config.ts`（host/audit 拆档）。所以本层的工作量不在「追新功能」，而在**把旧判定重判到新锚点的真实形状**——重测查出旧账面给两行打了过宽的勾。
>
> 文档状态：**已闭环（2026-11-05；W1–W7 全部落地，用户 2026-11-05 批准 §9 三项裁决）**。评测域 **48.0/49＝97.96%**（对齐 23／存疑 1／缺失 0）。全仓 verify 见 §7.2。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。pi-java 代码基线：`22661fc3`（main，2026-11-05，L5 docs/25 关闭后）。旧基线读数：评测域 42.0/43 ≈ 97.7%（旧锚点 `ea448f454`，见 `map/06`）。

## 0. 范围、口径与术语

- **层范围**：`pi-java-evals` 全模块 ↔ pi `packages/evals`（`src/` 运行器 ＋ `evals/` 用例 ＋ `test/` 单测 ＋ `docker/` 装配）。上游行为（harness 依赖的 coding-agent/ai 接口）已由 L1–L5 闭环，本层只做评测侧的 oracle 与 harness 复核。
- **计分口径**：权重＝用户可观察影响 × 频率（3/2/1/0）；完成系数 对齐 1.0／存疑 0.5／缺失 0。R1/R5 与转出行权重 0、不进分母。
- **判定三档**：对齐／存疑／缺失。**旧锚点已对齐的行必须重新取证**；本层实测证明「旧锚点已对齐」不足为凭（见 §1.3 第 5 行）。
- **术语**：
  - **arm（臂）**：一个 case 在一个变体上的一次执行。docs-lift 的最小计分单位。
  - **pair（配对）**：同一 case 同一 run 的 `without_docs` 与 `with_docs` 两臂。
  - **blocked（阻断）**：任一臂 `skipped/pending/unscored/errored/missing/duplicate` ⇒ 该对不参与统计。
  - **lift**：treatment 相对 control 的提升——**本层重测发现 pi 与 Java 的定义不同**，见 §1.3 第 5 行。
  - **paired metric（配对标量）**：对每个 pair 取同一指标，再算两侧均值与差。
  - **operational totals（运行总量）**：按变体累加的绝对量（不是配对差），带 `availableRuns` 覆盖度。
  - **host eval**：不需要两变体配对的普通套件（smoke、文档审计）。**audit**：其中的文档审计，新锚点起单独成档。
- **模型可见面**：评测域没有模型可见契约（评测是开发工具）；但**判分断言**（要求模型必须读过哪些文件、必须调用哪个工具）直接决定评测结论，按模型可见行为对待。内部结构标【内部，模型不可见】。

## 1. 新锚点重测结论

### 1.1 方法

1. `git diff --stat ea448f454..56cae7823 -- packages/evals/` 枚举全部 12 个变化文件（净 +197/−77）。
2. `git log --oneline ea448f454..56cae7823 -- packages/evals/` 共 7 个提交，逐条归类；**其中只有 `9628c8480`（Prompt template documentation eval）与 `936e5f40a`（drop autoevals）是本包的实质差异**，其余 5 个是版本号/依赖/发布提交。
3. 逐行重判 `map/06` 评测域 20 个计分单元；判定依据一律回到新锚点源码，不沿用 `map/06` 现有勾选。
4. 单独复核 docs/08 §5 L6 的四个点名项与 D182–D189 八个窄差。

### 1.2 变化清单与归属

| 文件 | 改动量 | 归属 | 结论 |
|---|---|---|---|
| `src/report.ts` | +9/−6 | **L6 本包** | `readTaskObservation` 改按 `fullName` **先过滤再计数**；`classifyCaseStatus` 不再导出（测试专用导出回收）。**文件内其余（四组 paired metric、`VariantTotals`、`schemaVersion:3`）两锚点逐字节相同** |
| `evals/prompt-templates.docs.eval.ts` | +79（新） | **L6 本包** | 新增文档 eval：project/user 双 scope 建模板 → reload → 调用 |
| `evals/documentation-audit.eval.ts` | +18/−4 | **L6 本包** | 新增**取证断言**：必须 `read` 过被审页面，且必须 `read` 过至少一个 `packages/coding-agent/src/` 下的文件 |
| `evals/tui.docs.eval.ts` | +41/−25 | **L6 本包**（判定不变） | `autoevals` 的 `Levenshtein` 换成内联 `levenshteinSimilarity`；判分仍是 `valid ? 1 : 0`，`similarities` 只进 `retainedPercent` 元数据 |
| `evals/extensions.docs.eval.ts` | −2 | **L6 本包**（判定不变） | 删 `toolLoaded` oracle 与对应期望键 |
| `evals/configured-runtime.ts` | −10 | **L6 本包**（判定不变） | `loadConfiguredModelRuntime` 从 `src` 移入 `test`（测试专用） |
| `vitest.evals.config.ts` | +12/−3 | **L6 本包** | `host` 项目**排除** `documentation-audit.eval.ts`，新增 `audit` 项目；`sequence.concurrent=false` / 300s 超时两项目共用 |
| `package.json` | +5/−5 | **L6 本包**（判定不变） | `autoevals` devDep 删除（外部框架 npm **×3 → ×2**）；`vitest 4.1.11 → 4.1.12`；内部依赖钉死 `1.4.0`；新增 `eval:audit` 脚本 |
| `README.md` | +6/−6 | **L6 本包** | `eval:host` 只跑 smoke；`eval:audit` 跑审计；「审计要扫每一页文档，故保持显式」 |
| `test/report.test.ts` | +40/−39 | **L6 本包** | 新增「兄弟用例 skipped 时仍选中目标用例」；`classifyCaseStatus` 用例删除；failed 部分运行用例改 `scoredMeta()` |
| `test/comparison.test.ts` | +2 | **L6 本包** | 期望补 `totalMs`／`estimatedCostUsd` 两组配对摘要 |
| `test/configured-runtime.test.ts` | +12/−1 | **L6 本包**（判定不变） | 本地内联 `loadConfiguredModelRuntime` |
| `docker/install-runtime.mjs` | — | 已在旧读数登记 | 装配脚本重构，能力面不变 |

**未变化且构成本层基线的**：`src/harness.ts`（537 行）、`src/cli.ts`（192）、`src/docker.ts`（175）、`src/plan.ts`（59）、`docker/entrypoint.ts`（152）、`docker/Dockerfile`（44）两锚点**逐字节相同**。

### 1.3 逐域重判（关键三行）

**结论先行：评测域 20 行 → 24 行，Σ权重 43 → 49，Σ(w×c) 42.0 → 40.0，加权完成度 97.7% → 81.6%。**

下降的全部原因是重判，不是回归：旧账面把 #5（报告汇总）按「有 lift、有 flags、能出报告」打了满分，但新锚点复核对齐的是**pi 实际发布的报告形状**，而那四项配对摘要与 lift 定义在 Java 侧根本不存在。

#### 第 5 行（对照报告汇总，w2）：对齐 → **缺失**

pi `src/report.ts:43-56` 的每个 eval set 汇总条目携带**四组配对标量**：

```ts
export type EvalSetComparison = {
	evalSet: string;
	totalPairs: number;
	eligiblePairs: number;
	blockedPairs: number;
	controlPassRate: number | null;
	treatmentPassRate: number | null;
	lift: number | null;
	flags: Array<"no-lift" | "negative-delta" | "control-saturated" | "treatment-saturated" | "flaky">;
	totalTokens: PairedMetricSummary;
	toolCalls: PairedMetricSummary;
	totalMs: PairedMetricSummary;
	estimatedCostUsd: PairedMetricSummary;
};
```

`src/report.ts:284-305` 定义配对摘要，`src/report.ts:399-402` 在汇总处逐个调用：

```ts
				totalTokens: summarizeMetric(pairs, ({ totalTokens }) => totalTokens),
				toolCalls: summarizeMetric(pairs, ({ toolCalls }) => toolCalls),
				totalMs: summarizeMetric(pairs, ({ totalMs }) => totalMs),
				estimatedCostUsd: summarizeMetric(pairs, ({ estimatedCostUsd }) => estimatedCostUsd),
```

而 `lift` 在 pi 是**通过率之差**（`src/report.ts:381-397`）：

```ts
			const publishHeadline = blockedPairCount === 0 && pairs.length > 0;
			const controlPassRate = publishHeadline
				? pairs.filter(({ control }) => control.score >= 1).length / pairs.length
				: null;
			const treatmentPassRate = publishHeadline
				? pairs.filter(({ treatment }) => treatment.score >= 1).length / pairs.length
				: null;
			...
				lift:
					controlPassRate === null || treatmentPassRate === null
						? null
						: difference(treatmentPassRate, controlPassRate),
```

Java `report/ComparisonReport.java:210-213` 算的是**逐对分数差的均值**：

```java
        var deltas = cases.stream().map(CaseComparison::delta).filter(Objects::nonNull).toList();
        var lift = blocked || deltas.isEmpty()
            ? null : (Double) deltas.stream().mapToDouble(Double::doubleValue).average()
                .orElse(0.0);
```

两者在分数只取 0/1 时相同，在有部分分（`LevenshteinJudge` 的 0.75 阈值、judge 的连续分）时**不同**：pi 报「有多少对从不及格变成及格」，Java 报「平均分涨了多少」。pi 的四组配对标量（token／工具调用／延迟／成本）在 Java 侧**完全没有**（`ComparisonReport` 只有 `totalDurationMs` 与 `usageUnavailable` 两个汇总标量）。

另外 pi 的 withheld 是**按 eval set**（`publishHeadline = blockedPairCount === 0 && pairs.length > 0`），Java 是**全报告级**一枚 `blocked` 旗。

⇒ 行内容（paired metric ＋ correctness lift ＋ 文本渲染 `src/report.ts:437-483`）整体缺失，判**缺失**。

#### 第 6 行（reporter 落产物，w2）：保持 **对齐**，登记形状差

pi `src/report.ts:147-157` 新锚点改为**先过滤再计数**：

```ts
	const reportedFullName = `${task.evalSet} ${task.caseId}`;
	const assertions = rawReport.testResults
		.flatMap(({ assertionResults }) => assertionResults)
		.filter(({ fullName }) => fullName === reportedFullName);
	if (assertions.length !== 1) return { ...identity, outcome: "errored" };
	const assertion = assertions[0];
	const statusOutcome = classifyCaseStatus(assertion.status);
	if (statusOutcome === "skipped" || statusOutcome === "pending") return { ...identity, outcome: statusOutcome };
	const caseResults = workspace.cases.filter(({ fullName }) => fullName === reportedFullName);
	if (caseResults.length !== 1) return { ...identity, outcome: "errored" };
```

这是为「同一份 Vitest 报告里还躺着兄弟用例、其中一个被 skip」的场景修的：旧写法要求整份报告只有一个断言。Java 侧不存在该场景——`cli/EvalArmMain.java:157-189` 每个臂只跑一个 case 并写好**只含一个 `<testcase>`** 的 `junit.xml`，`docker/DockerEvalRunner.java:193-216` 直接按计数分类、不做 fullName 选择。落产物行为（`session.jsonl` 快照）不受影响 ⇒ 维持**对齐**，形状差登记 D229。

#### 第 10 行（docs 审计 eval，w2）：保持 **对齐**，取证断言单列新行

pi 在 `evals/documentation-audit.eval.ts:34` 与 `:60-71` 新增取证要求：

```ts
const sourceRoot = resolve(repositoryRoot, "packages/coding-agent/src");
...
		const calls = toolCalls(result);
		const readPaths = calls.flatMap((call) => {
			const path = call.name === "read" && call.status === "ok" ? call.arguments?.path : undefined;
			return typeof path === "string" ? [resolve(path)] : [];
		});
		expect(readPaths).toContain(documentationPath);
		expect(
			readPaths.some((readPath) => {
				const sourceRelative = relative(sourceRoot, readPath);
				return sourceRelative !== "" && !sourceRelative.startsWith("..") && !isAbsolute(sourceRelative);
			}),
		).toBe(true);
```

审计套件本身（页面发现、提示、提交工具、`verdict` 校验）Java 已有且与 pi 同形 ⇒ #10 维持对齐；新增的「必须读过页面＋必须读过实现源」是一条**独立的判分能力**，Java `contents/DocumentationAuditEval.java:235-265` 的 `AuditJudge` 只看提交调用。按 L5 新增行的既有惯例单列为 **#21（w2，缺失）**，不把 #10 整体降档。

#### 新增 #22：prompt template 文档 eval（w2，缺失）

pi `evals/prompt-templates.docs.eval.ts:1-79` 新增整套：两个 scenario（project／user scope），请求里明确「项目已受信任」，随后 `run([prompt, reload, "/current-time"])`，观测：

```ts
			return {
				template: template
					? { scope: template.sourceInfo.scope, atExpectedPath: template.filePath === expectedPath }
					: null,
				promptDiagnostics: diagnostics.map(({ message }) => message),
				extensions: extensions.extensions.filter(({ hidden }) => !hidden).map(({ path }) => path),
				extensionErrors: extensions.errors.map(({ error }) => error),
				skills: session.resourceLoader.getSkills().skills.map(({ name }) => name),
				otherScopeTemplateExists: existsSync(otherPath),
				expandedToTimeRequest: expandedText === TEMPLATE_PROMPT,
			};
```

它同时钉住三件事：模板落在**正确 scope 目录**（`<cwd>/.pi/prompts/current-time.md` vs `<agentDir>/prompts/current-time.md`）、另一 scope **不**被写入、以及 **`/current-time` 展开后的用户消息逐字等于模板正文**。

Java 侧零件齐全但**没有这套 eval**：`prompt/PromptTemplates`（YAML frontmatter＋参数替换）、`prompt/PromptTemplateRegistry.replaceAll`（`/reload` 整表替换，`core/SessionReload.java:65-67`）、`prompt/PromptTemplateExpansion.expand`（`/name` → 正文）、`harness/PiCodingAgentHarness.runSteps` 支持 `HarnessStep.reload()`。缺的只是把三者串起来的套件 ＋ 「从 transcript 末条 user 消息回读展开结果」的口径。判**缺失**。

> 落地注意：Java 的项目配置目录是 `.pi-java`（`pkg/SourceResolver.java:32`），用户 agent 目录是 `~/.pi-java/agent`。`IsolatedEnvironment.agentDir()` 现在返回 `home/.pi/agent`（`harness/IsolatedEnvironment.java:83-85`），与本仓实际读取的路径**不一致**——`harness/IsolatedEnvironment.java` 建的是 `.pi/agent`，但 `FileSettingsStorage.defaultAgentDir()` 读 `~/.pi-java/agent`，靠 `PI_JAVA_CODING_AGENT_DIR` 环境变量岔开。本包必须先把这个缝对齐（见 W4）。

#### 新增 #23／#24：运行总量与 audit 拆档

pi `src/report.ts:60-73` 的 `VariantTotals` 与 `src/report.ts:467-474` 的「Operational totals」段是独立报告节，按变体累加绝对量并带覆盖度（`${availableRuns}/${runs} measured`）。Java 的 `operationalTotals` 是 `Map<CaseStatus, Long>`（臂计数，语义不同），没有 token／工具／延迟／成本总量。

`vitest.evals.config.ts:22-43` 把审计从 `host` 拆成独立 `audit` 项目，README 同步：「文档审计要扫每一页文档，故保持显式」。Java `cli/EvalCli.java:174-192` 的 `--host` 跑**全部选中套件**，审计默认在内。

两者各 w1，判**缺失**，单列。

### 1.4 旧窄差与新锚点复核（D182–D189）

八条窄差在当前台账 `docs/04` §6 **完全缺席**（文档重编时丢失，只在 `map/06` 与 `docs/08` 里被引用）。本包按新锚点逐条复核后重新登记。

| 编号 | 旧内容 | 新锚点事实 | 本包结论 |
|---|---|---|---|
| D182 | Docker 镜像真实构建/执行不在 CI 验收链 | `src/docker.ts`、`docker/Dockerfile`、`docker/entrypoint.ts` 两锚点逐字节相同 | **保留**，重新登记 |
| D183 | TUI 文档审计以 Java 文档问答替代 pi 的 context-footer oracle | 判分核心（`contextFooterJudge` 的 `valid` 诸臂）未改；仅 `autoevals` 换内联 `levenshteinSimilarity`（`tui.docs.eval.ts:23-37`），且其返回值仍只进 `retainedPercent` 元数据 | **保留**，重新登记；#20 维持存疑 |
| D184 | 扩展作者 eval 额外允许 shell（Java 扩展需编译） | 工具集未变（只删了 `toolLoaded` oracle） | **保留**，重新登记 |
| D185 | eval 发现用静态注册表、Docker 报告读 JUnit XML | `src/cli.ts`、`src/plan.ts` 未变 | **保留**，重新登记（audit 拆档另计 #24） |
| D186 | provider／model eval 用内联工具代写 `models.json` 并探针 | `evals/configured-runtime.ts` 只删了测试专用导出 | **保留**，重新登记 |
| D187 | 扩展作者 eval 经 `sessionCustomizer`＋内联 `install_extension` 装载 JAR | 装载流程未变 | **保留**，重新登记 |
| D188 | `SmokeEval` 在 CLI 全量 docs-lift 下也会被配对 | 未变；新锚点进一步把 smoke 定为 `eval:host` 唯一套件 | **保留**，重新登记，并由 #24 收窄 |
| D189 | `RecordingFooterOracle` 按每 codepoint 一列近似显示宽度 | 未变 | **保留**，重新登记 |

### 1.5 两侧同缺项复核（docs/08 §5 L6 第三点）

docs/08 §5 L6 要求「核查流式参数、`before_agent_start`、动态目录等新行为是否需要新 oracle」。**结论：pi 在同一区间没有为这些行为新增任何评测 oracle**——`packages/evals/evals/` 的 +94 行全部来自 prompt-templates 与 audit 取证，`harness.ts` 逐字节未变。按「两侧同缺」不构成相对缺口，**不计分**，只在此留证，避免后续误当遗漏去补。

## 2. W1（#5）：`ComparisonReport` 补齐配对摘要与 lift 语义

### 2.1 pi 事实

见 §1.3 第 5 行三段引用。报告根形状（`src/report.ts:75-83`）：

```ts
export type EvalComparisonReport = {
	schemaVersion: 3;
	protocolDigest: string;
	control: "without_docs";
	treatment: "with_docs";
	comparisons: EvalSetComparison[];
	blockedPairs: BlockedPair[];
	operationalTotals: VariantTotals[];
};
```

即**报告是 per-eval-set 的列表**，不是单份全局汇总。

### 2.2 Java 现状

`report/ComparisonReport.java:37-48` 是**单份全局** record：`cases`／`blocked`／`withheld`／`control`／`treatment`／`lift`／`flags`／`operationalTotals`／`totalDurationMs`／`usageUnavailable`。`lift` 为逐对分数差均值（`:210-213`）；`ScopeSummary.passRate` 在整个报告被 `blocked` 时置 `null`（`:289-292`）。

### 2.3 改动

1. 由 `EvalObservation` 的 `usage`＋`durationMs` 现算四组配对摘要（`usage` 已含 `inputTokens/outputTokens/cacheReadTokens/cacheWriteTokens/totalTokens/toolCalls/estimatedCostUsd`，无需改 `HarnessUsage`）。
2. 新增 per-eval-set 汇总：`EvalSetComparison(evalSet, totalPairs, eligiblePairs, blockedPairs, controlPassRate, treatmentPassRate, lift, flags, totalTokens, toolCalls, totalMs, estimatedCostUsd)`。`lift = treatmentPassRate − controlPassRate`，两边都非 `null` 才算；`publishHeadline = blockedPairs == 0 && eligiblePairs > 0`。
3. `ComparisonReport` 改为**承载 `comparisons` 列表**（保留顶层 `blocked`／`cases`／`blockedPairs` 便于既有一处消费点），withheld 从「全报告级」细化为「按 eval set」。
4. `ComparisonReportText.render` 按 pi `formatEvalComparisonReport`（`src/report.ts:437-483`）的段序重排：`Pairs` / `Pass rate` / `Flags` / `Tokens` / `Tools` / `Latency` / `Est. cost`，再 `Operational totals`，再 `Blocked pairs`。数值格式沿用 pi 的 `+`/`−` 符号与小数位约定。
5. 拆件：`ComparisonReport`（351 行）加分片后必然超 500 行闸门，按 `PairedMetricSummary`／`EvalSetComparison` 各自独立文件处理。

### 2.4 测试（RED-first）

- 四组配对标量：构造两臂 usage，断言 `controlMean`／`treatmentMean`／`meanDelta`／`eligiblePairs`；某指标单侧缺席 ⇒ 该 pair 不进该指标且 `eligiblePairs` 减一。
- lift 语义分叉：control 分数 `{1.0, 1.0}`、treatment `{1.0, 0.5}` 时，**passRate lift = 0.0** 而旧的逐对均值差 = −0.25。此用例是旧实现的 RED。
- per-eval-set withheld：A 集全 eligible、B 集有 blocked ⇒ A 出 headline、B 的 `controlPassRate`／`treatmentPassRate`／`lift` 全 `null`。
- 文本报告段序与格式（golden）。

### 2.5 风险

- 改变 `report.json` 的**结构**。既有消费点（`cli/EvalCli.java:302-333` 的 `reportNode`、`report/ComparisonReportText`）随之改；`.eval/` 历史产物不保证互读，这是新 schema 版本，登记 D228。
- `lift` 定义变化会让同一批观测的**数字变小**（passRate 差比分数差更保守）。这是对齐 pi，不是回归；须在台账写清。

## 3. W2（#23）：按变体运行总量与覆盖度

### 3.1 pi 事实

`src/report.ts:60-73`：

```ts
export type VariantTotals = {
	variant: DocumentationVariant;
	runs: number;
	inputTokens: OperationalMetricTotal;
	...
	totalMs: OperationalMetricTotal;
	estimatedCostUsd: OperationalMetricTotal;
};
```

`src/report.ts:431-435` 的覆盖度渲染：

```ts
function operationalMetric(metric: OperationalMetricTotal, runs: number, format: (total: number) => string): string {
	if (metric.total === null) return `unavailable (0/${runs} measured)`;
	const coverage = metric.availableRuns === runs ? "" : ` (${metric.availableRuns}/${runs} measured)`;
	return `${format(metric.total)}${coverage}`;
}
```

### 3.2 Java 现状

`operationalTotals` 是 `Map<CaseStatus, Long>`，语义是**臂状态计数**，不是按变体的指标总量；无 `availableRuns` 概念。

### 3.3 改动

新增 `VariantTotals`（record：`variant`／`runs`／八组 `OperationalMetricTotal(availableRuns, total)`），由 `EvalObservation` 按变体累加；`ComparisonReport.operationalTotals` 改为 `List<VariantTotals>`，原 `Map<CaseStatus, Long>` 改名 `statusCounts` 保留（文本报告仍要印，便于排障）。文本段按 §2.3 第 4 点插入。

### 3.4 测试

- 覆盖度：两条 `with_docs` 臂其中一条 `usage == null` ⇒ `availableRuns=1`、`runs=2`，文本出现 `(1/2 measured)`。
- 全缺席 ⇒ `total == null` 且渲染 `unavailable (0/2 measured)`（**不得折成 0**，与 `ObservationJson` 既有的「不可用不折零」同旨）。

### 3.5 风险

低。纯新增字段；`statusCounts` 保留保证既有排障输出不丢。

## 4. W3（#21）：审计取证断言

### 4.1 pi 事实

`evals/documentation-audit.eval.ts:60-71`（§1.3 第 10 行已引全）。要点：`read` 的 `status === "ok"` 才算取证；页面路径**精确命中**；实现源判定用 `relative(sourceRoot, readPath)` 既不空也不越界。

### 4.2 Java 现状

`contents/DocumentationAuditEval.java:235-265` 的 `AuditJudge` 只校验提交调用（数量／成功／`verdict=match`／`explanation` 长度）。`harness/TranscriptEvents.toolCalls` 已提供 `ToolCallRecord(arguments)` 与 `status`，`HarnessRun.events()` 亦有 `tool_result`，取证数据齐备。

### 4.3 改动

1. `AuditJudge` 增加两条前置断言：读过的路径集合必须包含被审页面；必须至少有一个读过的路径位于实现源根之下。
2. 路径归一：pi 用 `resolve(path)` 相对**进程 cwd**。Java 的 `read` 工具走 `PathUtils.resolveToolPath`（相对 `ToolContext.cwd`，且**越界抛 `SecurityException`**），所以模型传绝对路径时必须落在 workspace 内。审计 eval 的 workspace 已种入全部页面副本（`harnessOptions(...).workspaceFiles(pages)`），因此取证路径的比对基准要取 **workspace 根的文档副本**，不能照抄 pi 的 `docsRoot` 绝对路径。实现源根同理改用「workspace 内的源码副本」——这要求审计 eval 额外种入实现源码（见 §4.4）。
3. 失败理由分档：`未读页面` / `未读实现` / `未提交` / `提交非 match`，便于定位。

### 4.4 需要新增的夹具

pi 的审计 eval 让模型读**仓库里的真实页面与真实源码**（容器内 `/repo` 只读挂载）。Java 的工具受 `PathUtils` 约束只能在 workspace 内读写，因此必须把「页面 ＋ 相关实现源」一并种入隔离 workspace。页面规模 34 篇／16,341 行可接受；实现源需按页面限定子集（至少覆盖 `pi-java-coding-agent` 与 `pi-java-ai` 的公开面），否则一次种入 5 万行会造成不必要的 I/O 与 token 压力。

这是**与 pi 的形状差**（pi 读全仓、Java 读种入子集），登记 D230，并在提示里明确「可读范围是本 workspace」。

### 4.5 测试

- 只读页面未读实现 ⇒ 0 分且理由含「未读实现」。
- 只读实现未读页面 ⇒ 0 分。
- 页面／实现都读且提交 `match` ⇒ 1.0。
- `read` 状态为 `error` 的调用不计入取证。

## 5. W4（#22）：prompt template 文档 eval

### 5.1 pi 事实

`evals/prompt-templates.docs.eval.ts:5-30`（scenario 定义）与 `:31-49`（观测）：

```ts
		request: `This project is already trusted. Add a ${COMMAND} prompt template for this project containing exactly: ${TEMPLATE_PROMPT}`,
...
		request: `Make a ${COMMAND} prompt template available in all my Pi sessions containing exactly: ${TEMPLATE_PROMPT}`,
```

步骤固定为 `prompt → reload → /current-time`（`:70-76`）。

### 5.2 Java 现状与前置缝

零件齐全（见 §1.3 #22）。**但有一处必须先修**：`harness/IsolatedEnvironment.java:56-57` 建的是 `home/.pi/agent`，而 Java 实际读取的用户 agent 目录是 `~/.pi-java/agent`（`core/FileSettingsStorage.java:52-59`，可被 `PI_JAVA_CODING_AGENT_DIR` 覆盖）。项目目录则经 `pkg/SourceResolver.CONFIG_DIR_NAME = ".pi-java"` 解析。

所以本 work item 的两步是：(a) 让 `IsolatedEnvironment` 暴露并设置 `PI_JAVA_CODING_AGENT_DIR`（等价于 pi 的 `PI_CODING_AGENT_DIR`），把 `.pi` 改成 `.pi-java`；(b) 在此之上建 eval。

> 注意：`PI_JAVA_CODING_AGENT_DIR` 是**进程级环境变量**，`System.getenv` 不可写。因此 (a) 需要用与 `IsolatedEnvironment` 现存 `user.home`/`user.dir` 同级的隔离手段——把该环境变量纳入「进程级串行锁」覆盖范围，或把 `defaultAgentDir()` 改为读一个可注入的 System property。**倾向后者**（可注入属性，零进程级副作用），实现前需你确认，见 §7 第 1 项。

### 5.3 改动

新建 `contents/PromptTemplatesDocsEval`（suite 名 `prompt-templates-docs`），两个 case（`project-scope`／`user-scope`）：

- 提示逐字对位 pi 的两句请求（把 `/current-time`、模板正文替换成同名同文）。
- 步骤 `HarnessStep.prompt(请求)` → `HarnessStep.reload()` → `HarnessStep.prompt("/current-time")`。
- 判分器回读：模板 scope 与落点路径是否**恰好**正确、另一 scope **不**存在同名文件、`/current-time` 展开后的末条 user 消息**逐字**等于模板正文、扩展与技能表为空、无诊断。
- 需要新增一个只读面：从 `AgentSession` 取「模板注册表条目」与「末条 user 消息原文」。`services().promptTemplates().all()` 与 `accumulatedMessages()` 都已公开，无需改生产接口。

### 5.4 测试

- 判分器：正确 scope＋正确路径＋逐字展开 ⇒ 1.0。
- 落错 scope（把项目模板写到用户目录）⇒ 0 分且理由点明 scope。
- 展开结果多了前导/尾随空白 ⇒ 0 分（pi 用 `contentText(...).trim()` 后**逐字**比对）。
- 注册表装配：fake run 下 `template == null`（同名模板出现两次）⇒ 0 分。

### 5.5 风险

- 项目 trust：pi 的请求里写「项目已受信任」。Java 的 `PackageAutoDiscovery` 对项目 prompts **有 trust 门**（`core/PackageAutoDiscovery.java:66-71` 在 `if (trusted)` 内），所以 harness 必须先把隔离 workspace 标为 trusted，否则项目 scope 用例必然失败。这依赖 `ProjectTrustStore` 在隔离 agentDir 下可写。
- 该 eval 依赖模型真的会去读文档再写文件，属 docs-lift 语义，与既有套件同风险等级。

## 6. W5（#24）：audit 独立执行档

### 6.1 pi 事实

`vitest.evals.config.ts:22-43` 三个项目：`docs` / `host`（排除 `*.docs.eval.ts` **与** `documentation-audit.eval.ts`）/ `audit`（只含审计）。`package.json:10-11` 对应 `eval:host` 与 `eval:audit` 两条脚本。README 说明理由：审计扫每一页文档，开销大，故保持显式。

### 6.2 Java 现状

`cli/EvalCli.java:136-192`：`--host` 跑全部选中套件；`ContentsCatalog.install()` 把审计与其余六套一并注册。可用 `EvalCatalog` 的选择器手动排除，但没有「默认不含审计」的执行档。

### 6.3 改动

`cli/EvalCliOptions` 增加 `--audit`（只跑审计套件）与默认 `--host` **排除**审计（与 pi 的 `exclude` 一致）。`documentation-audit` 成为单独的档位标识；`ContentsCatalog` 注册时给审计套件打一个 `expensive` 标记，由 `EvalCatalog` 按档位过滤。参数错误文案沿用现有一元化风格。

### 6.4 测试

- `--host` 默认不含审计；`--host --audit` 冲突报错。
- `--audit` 只选审计；与显式选择器并存时的优先级（显式选择器仍可单独点审计）。
- 环境变量缺省与 `--provider/--model` 配对校验不受影响。

### 6.5 风险

低。纯 CLI 面，默认行为变化对「一直用 `--host` 跑全部」的使用者是**变严格**（审计不再顺带跑）——这正是 pi 的取向，且与 D188 的收窄同向。

## 7. W6（台账）／W7（验证）：文档与验收

### 7.1 W6

1. `docs/04` §6 补回 **D182–D189**（按 §1.4 复核后逐条重写），新增 **D228**（报告 schema 变更：`comparisons` 列表＋新 `lift` 定义＋`operationalTotals` 改按变体）、**D229**（`readTaskObservation` 的兄弟用例过滤在 Java 单臂模型下不适用）、**D230**（审计取证范围＝种入 workspace 的子集，非 pi 的全仓只读挂载）。
2. `docs/map/06` 头部换锚、评测表 24 行重写、汇总与存疑清单更新；存储后端行不动（L4 已闭环）。
3. `docs/06` L6 行 → 已闭环；`docs/08` §9 进度表与 §11 下一步（L7 TUI/web）。
4. `docs/25` 的「下一步 L6」指针改为已完成。

### 7.2 W7（实测）

全仓 `mvn clean verify` 通过：**6,424 测 0 失败 0 错误 / 33 跳过**（L5 基线 6,397／33 跳过，本层 +27），Checkstyle 零错误、SpotBugs 零告警。

新增主源 6 文件：`report/{PairedMetricSummary,EvalSetComparison,VariantTotals,OperationalMetricTotal}.java`、`contents/{PromptTemplatesDocsEval,AuditSourceIndex}.java`（`EvalAuditGate` 未单列，落在 `EvalCli.byAuditGate`＋`EvalSuite.expensive()`）。
新增测试 3 文件：`report/ComparisonReportMetricsTest`、`contents/{DocumentationAuditEvidenceTest,PromptTemplatesDocsEvalTest}`。

**落地顺序与提交**：W1/W2 → W3 → W4 前置（隔离缝）→ W4/W5 → W4 观测面重构 → 文档回填。

## 8. 账面变动表

| 域 | 行数/Σ权重 | 旧读数 | 变化 | 新读数 |
|---|---|---|---|---|
| 评测（map/06） | 20 / 43 | 42.0/43 ≈ **97.7%** | #5 对齐→缺失（−2.0）；新增 #21（2）、#22（2）、#23（1）、#24（1）全缺失 | 24 / 49 |
| **实施后（实测）** | 24 / 49 | — | W1–W5 全部落地；#5、#21–#24 全部转对齐 | **48.0/49 = 97.96%** |

> 实测 **48.0/49**，比设计稿的 49.0 目标少 1.0：唯一未转对齐的是 **#20 TUI 文档审计**（存疑，
> D183／D189）。该行本包不动——它等的是 Java TUI 补 context 百分比状态行，属 L7 面。这是预期
> 状态而非遗漏。
>
> 设计稿 §8 原写「目标 49.0/49＝100%」时把 #20 当作会随 W3 取证断言一并转对齐；实测表明
> 取证断言（#21）与 oracle 形态（#20）是两件事，前者落地不改变后者。

## 9. 需你裁决的三项（2026-11-05 已批准，全部按建议执行）

| # | 事项 | 选项 | 裁决 |
|---|---|---|---|
| 1 | `agentDir` 隔离缝（§5.2） | (a) 把 `PI_JAVA_CODING_AGENT_DIR` 纳入进程级串行隔离；(b) 加可注入 System property | **(b)**：`FileSettingsStorage.AGENT_DIR_PROPERTY = "pi.java.agentDir"`，登记 D231 |
| 2 | 审计取证范围（§4.4） | (a) 种入全仓实现源；(b) 按页限定的相关子集 | **(b)**：`AuditSourceIndex`，登记 D230 |
| 3 | 报告 schema 变更（§2.5） | (a) 直接换新结构；(b) 保留旧字段做兼容层 | **(a)**：`schemaVersion=3`，登记 D228 |

## 10. 不做清单

- 不为「两侧同缺」的流式参数／`before_agent_start`／动态目录补评测 oracle（§1.5）。
- 不把 Docker 真实构建拉进 CI（D182 维持）。
- 不把 `autoevals` 引回 Java 侧：pi 已删该依赖，Java 的 `LevenshteinJudge` 本就自研。
- 不复活 pi 已删除的测试专用导出（`classifyCaseStatus`），也不为 Java 的 `CaseStatus` 补导出面。
