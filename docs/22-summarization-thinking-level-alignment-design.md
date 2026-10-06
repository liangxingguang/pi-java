# 包 22：摘要请求透传会话思考级别（B173 销号）

> **状态：🔍 待审核（2026-10-06）—— 设计稿，未写生产代码。**

## 1. 问题

B173（包 21 登记）：pi 的摘要请求（历史摘要与 turn-prefix 两条路）在
`createSummarizationOptions` 里把**会话当前思考级别**透传进请求选项；pi-java 的
摘要 `StreamOptions` 恒传 `Optional.empty()`，于是即使会话开了思考，摘要请求仍是
关闭形状 —— 模型不产出思考块，摘要质量与 pi 不同；在按思考级别计费/路由的
provider 上请求形状也不一致。

## 2. pi 锚点事实（`200387122`，`git show` 取证）

### 2.1 参数门：`packages/coding-agent/src/core/compaction/compaction.ts:595-610`

```ts
function createSummarizationOptions(
	model: Model<any>,
	maxTokens: number,
	apiKey: string | undefined,
	headers: Record<string, string> | undefined,
	env: Record<string, string> | undefined,
	signal: AbortSignal | undefined,
	thinkingLevel: ThinkingLevel | undefined,
	sessionId: string | undefined,
): SimpleStreamOptions {
	const options: SimpleStreamOptions = { maxTokens, signal, apiKey, headers, env, sessionId };
	if (model.reasoning && thinkingLevel && thinkingLevel !== "off") {
		options.reasoning = thinkingLevel;
	}
	return options;
}
```

要点：三道与条件 —— **模型支持 reasoning**（`Model.reasoning: boolean`，
`ai/src/types.ts:1120`）、级别有值、级别非 `"off"`；任一不成立 ⇒ 不带 `reasoning`。

### 2.2 两条调用路径都经过此门

- 历史摘要：`generateSummaryWithUsage`（`compaction.ts:735` 调
  `createSummarizationOptions(…, thinkingLevel, …)`）；
- turn-prefix：`generateTurnPrefixSummary`（`compaction.ts:1100` 同形调用）。

### 2.3 级别来源：会话当前级别，压缩时现读

`_getSummarizationRequestAuth`（`agent-session.ts:544-580`）：

```ts
	const { model, thinkingLevel } = isVirtualModel(selectedModel)
		? await this._modelRuntime.resolveModel(…)
		: { model: selectedModel, thinkingLevel: this.thinkingLevel };
```

非虚拟模型时 `thinkingLevel = this.thinkingLevel`（会话当前级别，可经
`setThinkingLevel` 改变），取值发生在本次压缩调用点。虚拟模型经 resolveModel
调整级别 —— pi-java 无虚拟模型概念（全仓 grep 零命中），此分支不移植。

## 3. pi-java 现状（逐处核实）

- `LlmSummaryGenerator.request`（`:271-283`）：`StreamOptions` 第三参恒
  `Optional.empty()`；历史/turn-prefix 两路都走这个 `request`，一处补齐两路生效；
- 会话级级别可读：`AgentHarness.getThinkingLevel()`（`AgentHarness.java:449`，
  返回 `lane.thinkingLevel`）；
- `ModelThinkingLevel`（`pi-java-ai …/thinking/ModelThinkingLevel.java`）：
  `Off` / `Enabled(level)`；`Enabled.level()` 即 `ThinkingLevel`。主循环已有同形
  转换（`PiLoopRunner.reasoningOf:387-392`：Off ⇒ empty，Enabled ⇒ level）；
- 模型支持事实源：`ModelInfo.capabilities()` 含 `ModelCapability.THINKING`
  （catalog wire `"thinking"` 经 `CatalogModel.java:129` 映射；models.json 经
  `RemoteModelWire.java:43`；`ModelThinkingLevels.java:41` 的支持判定也读它）——
  这就是 pi `model.reasoning` 的对应面；
- `DefaultModelResolver` 已有按 ModelId 的目录查询（contextWindow/maxOutputTokens，
  未编目 ⇒ 0），加一个同形状的 `supportsThinking`；
- 接线点 `AgentSession.assemble`（`:366-404`）：harness 在 generator 构造之后
  才诞生，按既有「引用晚绑定」形状（sessionRef/compactionObserver 同款）用
  `AtomicReference<AgentHarness>` 承接。

## 4. 方案（R-A：照门补齐 reasoning）

### 4.1 `DefaultModelResolver.supportsThinking`（pi-java-ai）

```java
/**
 * 模型是否支持推理/思考（pi {@code Model.reasoning}；事实源是
 * {@link ModelInfo#capabilities()} 的 THINKING）。未编目（自定义 id）⇒ false。
 */
public boolean supportsThinking(ModelId<?> id) {
    return id != null && catalog.listModels().stream()
        .filter(info -> id.equals(info.id()))
        .findFirst()
        .map(info -> info.capabilities().contains(ModelCapability.THINKING))
        .orElse(false);
}
```

### 4.2 `LlmSummaryGenerator` 新参数与门

新增两个构造参数（其余构造器保留、给静默默认值＝当前行为）：

```java
private final Supplier<ModelThinkingLevel> currentThinking;
private final Predicate<ModelId<?>> reasoningSupported;
```

门（pi `compaction.ts:606-608` 的逐字翻译；转换沿用 PiLoopRunner 同形写法）：

```java
/**
 * pi createSummarizationOptions 的门：模型支持、级别有值且非 off ⇒ 透传级别。
 */
private Optional<ThinkingLevel> reasoningOption() {
    if (!reasoningSupported.test(model.get())) {
        return Optional.empty();
    }
    return switch (currentThinking.get()) {
        case ModelThinkingLevel.Off o -> Optional.empty();
        case ModelThinkingLevel.Enabled e -> Optional.of(e.level());
    };
}
```

`request` 的选项第三参由 `Optional.empty()` 改为 `reasoningOption()`；
`reasoningOption()` 在每次 request 调用时现读 supplier（与 pi 压缩点现读同形）。

### 4.3 接线：`AgentSession.assamble`

builder 之前加 `var harnessRef = new AtomicReference<AgentHarness>();`，
`AgentHarness.create(...)` 返回后立刻 `harnessRef.set(harness)`；generator 构造
加两参：

```java
new LlmSummaryGenerator(recordingStreamFn, () -> model,
    retrySettings, retryAborted, retryObserver,
    () -> models.maxOutputTokens(model),
    () -> harnessRef.get().getThinkingLevel(),
    models::supportsThinking)
```

行为面：仅摘要一次出站请求的 reasoning 选项改变；摘要落盘/合并/重试环/maxTokens/
缓存门零改动。

## 5. 实施步骤（严格 RED-first）

| # | 内容 | 预期 |
|---|---|---|
| 1 | 新增 `LlmSummaryReasoningOptionTest`（见下，5 条） | 先红 |
| 2 | 落 §4 生产改动（ai＋agent-core＋coding-agent） | 转绿；存量 compaction 套件连带绿 |
| 3 | 变异探针 §5.1 → 全部还原 | 有牙 |
| 4 | `mvn -o clean verify`（全 14 模块） | 零错误 |

夹具沿用 Captured（捕获 StreamOptions），断言
`captured.options.get(0).reasoning()`：

1. `historySummaryForwardsTheCurrentThinkingLevel`：supplier 给
   `Enabled(HIGH)`、predicate 恒真 ⇒ `contains(HIGH)`；
2. `turnPrefixForwardsTheCurrentThinkingLevel`：同形，走 summarizeTurnPrefix；
3. `modelWithoutReasoningDropsTheLevel`：predicate 恒假 ⇒ `isEmpty()`（钉
   model.reasoning 门）；
4. `offLevelDropsTheLevelEvenWhenSupported`：supplier 给 Off、predicate 真 ⇒
   `isEmpty()`（钉 off 门）；
5. `levelIsReadAtRequestTime`：第一次调用后改 supplier（High→Low），第二次请求
   读到 Low（钉现读、防构造期快照）。

RED 预期：旧代码下 ①②⑤ 红（reasoning 恒空），③④ 本就绿（负向用例）。

### 5.1 变异探针（红数以实测为准）

| 探针 | 变异 | 预期红灯 |
|---|---|---|
| M1 | 门恒空（reasoningOption 直接 empty） | ①②⑤ |
| M2 | 删 model.reasoning 门（不看 predicate） | ③ |
| M3 | 删 Off 分支形状（强转 Enabled 取 level） | ④（error） |

## 6. 明确不做

- **虚拟模型的级别调整**（pi resolveModel 分支）：pi-java 无虚拟模型，不移植；
  将来做虚拟模型时随那包处理；
- **branch summary 的思考级别**：B1（branch summary 功能本体）仍开放，其摘要调用
  同族但不在本包，随 B1 包处理；
- 不动摘要 prompt/maxTokens/缓存门/重试环/落盘合并；
- B174（customInstructions 生产者，RPC compact options 接线）不在本包。

## 7. 收口清单

- docs/22 banner/§8 回填；docs/05：B173 行关闭（未结 −1）；CLAUDE.md 文档表
  加 docs/22；memory 更新；全 reactor 验证后汇报。

## 8. 闭环记录

（闭环时回填。）
