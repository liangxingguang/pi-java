# 50 批量包：compat 常量标注族（B129–B132）＋ 新锚点漂移小修（B227/B228/B229 文案半）

> 📝 **状态：设计稿，待用户审核（2026-10-11）。未批准前不写生产代码。**
>
> pi 锚点：**`ea448f454`**（2026-10-11 迷你换锚后的新锚点，v1.1.0-22；证据一律
> `git show ea448f454:<path>`）。pi-java 基线 HEAD `5b91d3be`。
>
> 这是「长尾批量化」打法的第一个包：同族小缝一份设计、探针共用。

---

## 1. 范围

| 条目 | 处置 | 生产改动 |
|---|---|---|
| **B227** | 修 | `Estimate.CHARS_PER_TOKEN` 4→3.5（pi `27075fe07`，#10497） |
| **B228** | 修 | `RetryableError` 白名单补 3 个新模式（`server_busy`／`servers are currently busy`／`pending stream has been canceled`） |
| **B229 文案半** | 修 | Mistral 流内 `finish_reason:"error"` 文案补 ` (server error)`；**超时半保留在册**（见 §5.3 裁决） |
| **B129** | 修 | `CatalogCompatRules` 给 xiaomi 族标 `requiresReasoningContentOnAssistantMessages=TRUE`（消费者早已等着） |
| **B131** | 修（建议翻案） | 复刻 pi ant-ling 臂向通用 openai 臂的 else-if 穿透（窄角，手搓 models.json 可达），附夹具 |
| **B130** | 转 **C 类** | 今零行为差（模型集不重叠）；加一条守卫夹具钉「zai/glm-4.7 有效值 false」，触发条件写清 |
| **B132** | 转 **C 类** | together/baseten 内置 provider 不携带（R5）；models.json 路径经显式 compat 已完整可达，零行为差 |

**不在本包**：B230（azure 改名＋Foundry，整 provider 活）、B231（OAuth 刷新持久化，需凭证子系统取证）、
B232（目录数据漂移，整目录活）、B229 超时半（PiHttpClient 传输形状）、A16（六地图重测，盘点活）。
B98 八字段仍是独立的下一包候选。

台账预期：102 → 开册减去 B129/B131（销号 2），B130/B132 从 §3.2 移入 §4（编号保留、不计数变化口径不同——
计数命令只数 A/B，移入 §4 即出数）；B227/B228 销号、B229 保留半条。**净 B 数 108 → 102**
（B227/B228 销 2、B129 销 1、B131 销号或转 C 出数 1、B130/B132 转 C 出数 2；B229 仍在）。

---

## 2. pi 证据（新锚点逐字）

### 2.1 B227 —— 估算常数 4→3.5

`packages/ai/src/utils/estimate.ts:15`（`git show ea448f454`，diff 自旧锚点仅一行）：

```ts
const CHARS_PER_TOKEN = 3.5;
```

三个使用点 `:39/:43/:68` 全是 `Math.ceil(text.length / CHARS_PER_TOKEN)`。
消费者唯一在主流上：`packages/ai/src/api/simple-options.ts:20`：

```ts
const available = model.contextWindow - estimateContextTokens(context).tokens - CONTEXT_SAFETY_TOKENS;
```

pi 的回归夹具（`packages/ai/test/context-estimate.test.ts:45-58`）：

```ts
it("reserves 3.5 characters per token for new text when limiting output", () => {
	const context = normalizeContext({
		messages: [createAssistant(100, 2_000), { role: "user", content: "x".repeat(3_500), timestamp: 200 }],
	});
	expect(estimateContextTokens(context)).toEqual({
		tokens: 3_000, usageTokens: 2_000, trailingTokens: 1_000, lastUsageIndex: 0,
	});
	expect(buildBaseOptions(model, context).maxTokens).toBe(2_904);
});
```

（3500 字符 / 3.5 = 1000；窗口 10000 − 3000 − 4096 = 2904。）

⚠️ **压缩域那份估算器不改**：`packages/coding-agent/src/core/compaction/compaction.ts:300-345`
六个角色分支在新锚点**仍逐字 `/ 4`**（已对 `ea448f454` 复核）；agent-core 的
`ContextUsageEstimator:231` `/4.0` 与之一致。3.5 只影响默认 maxTokens 夹取。

### 2.2 B228 —— retry 白名单三新模式

`packages/ai/src/utils/retry.ts`（新锚点）：

```ts
// :32-35
	"overloaded",
	"server_busy",
	"servers are currently busy",
	"currently experiencing high demand",
```

```ts
// :84-88
	// Node ERR_HTTP2_STREAM_CANCEL: the HTTP/2 session died before the request was
	// sent, e.g. after the Bedrock SDK's 5-minute session timeout (#10379).
	"pending stream has been canceled",
```

提交：`8b5708dbb fix(ai): retry transient server-busy provider errors`、
`5b6c792b4 fix(ai): retry HTTP/2 pending stream cancellation`。
同批 `provider-retry.ts` 的 `noRetryStatuses` 唯一消费者是 `api/classifier-shared.ts:66`
（分类器，R5 面外）⇒ **不移植**。

### 2.3 B229 文案半 —— Mistral `finish_reason:"error"`

`packages/ai/src/api/mistral-conversations.ts:944-950`（`7fb59f995`）：

```ts
	case "error":
		// Mistral reports transient server failures this way; "server error" makes the message retryable.
		return { stopReason: "error", errorMessage: "Provider stopped with: error (server error)" };
	default:
		return { stopReason: "error", errorMessage: `Provider stopped with: ${reason}` };
```

白名单里的 `"server.?error"`（retry.ts:45 一带）就此抓得住它——旧文案不含该串，不重试。

**超时半**（`6b07b4e57`）：pi 把 60s `AbortSignal.timeout` 改成**只盖等响应头**（headers 到达即释放），
长思考流不再被固定截止时间切断；body 停顿交给 HTTP client 的 idle 超时。Java 侧 Mistral 走
`PiHttpClient`（`MistralConversationsApi.java:89-92`），请求级 `.timeout(300s)` 同时盖头和体。
改它要给 SSE 路径加「响应头到达后切换/取消请求超时」的传输能力，不是一行常量活，保留在册。

### 2.4 B129 —— xiaomi 的 rrc 目录标注

新锚点生成目录（`src/providers/data/*.json`，gitignored，`generate-models.ts --strict` 生成）实测：

```
xiaomi.json（mimo-v2.5 / v2.5-pro / v2.5-pro-ultraspeed / v2.6-flash / v2.6-pro / v2.6-pro-ultraspeed）
  全部：reasoning=true, requiresReasoningContentOnAssistantMessages=true, thinkingFormat="deepseek"
xiaomi-token-plan-cn.json / -ams / -sgp：同（4 条/4 条/4 条，rrc 全 true）
```

而 `detectCompat`（`openai-completions.ts:1693` 一带）给的是
`requiresReasoningContentOnAssistantMessages: isDeepSeek`——xiaomi 不满足 `provider === "deepseek"`
也不匹配 deepseek.com ⇒ 探测给 false；**true 只来自目录**。

消费者 `openai-completions.ts` 的助手历史补空串分支（Java 对应物已在
`OpenAICompletionsMessageConverter.java:413-417` 等着）：

```ts
if (/* reasoning_content 尚未写 */ && compat.requiresReasoningContentOnAssistantMessages && model.reasoning) {
	// assistant message 上补空串 reasoning_content
}
```

### 2.5 B130 —— zai 数据驱动，今零差

zai 目录逐模型实测（新锚点）：

```
zai: glm-4.7 sre=false(tf=zai, 无级别表) | glm-5-turbo sre=false
     glm-5.2 / glm-5.2-highspeed / glm-5.3 / glm-5.3-flash / glm-5.3-highspeed
       sre=true, thinkingLevelMap={off,minimal,low,medium,high,xhigh,max}
zai-coding-cn: glm-4.6v false | glm-5.3 / flash / highspeed true（同表）
```

Java 携带的只有 `zai/glm-4.7` 与 `zai-coding-cn/glm-4.7`（`ModelData.java:65-77`），pi 侧两者
sre 都是 **false**；Java 目录常量给 null、探测（isZai）兜底 false ⇒ **有效值逐字节相同**。
pi 漂移已发生（glm-5.x 上线），但模型集仍不重叠。

### 2.6 B131 —— ant-ling 穿透角（新锚点复核，结论与 2026-10-10 台账更正一致）

`openai-completions.ts` 思考臂链条（`:948-977`）：

```ts
} else if (compat.thinkingFormat === "ant-ling" && model.reasoning && options?.reasoningEffort) {
	const effort = model.thinkingLevelMap?.[options.reasoningEffort];
	if (typeof effort === "string") {
		(params).reasoning = { effort };
	}
} else if (compat.thinkingFormat === "together" && model.reasoning) {
	…
} else if (/*string-thinking*/) { … }
else if (options?.reasoningEffort && model.reasoning && compat.supportsReasoningEffort) {
	// OpenAI-style reasoning_effort
	(params as any).reasoning_effort = model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
}
```

窄角三元组：`thinkingFormat="ant-ling"`（探测按 provider 名/URL 就给）＋显式
`compat.supportsReasoningEffort=true`＋`thinkingLevelMap` 缺席——ant-ling 臂算出
`effort === undefined` 不写，链条**穿透到 :969 通用臂**，发原始级别名 `reasoning_effort`。
pi 自己的生成数据不可达（ant-ling 目录 sre 恒 false），但**手搓 models.json 可达**。

新锚点 ant-ling 目录：`Ling-2.6-1T`/`Ling-2.6-flash`（reasoning=false）、
`Ring-2.6-1T`（reasoning=true，sre=false，带级别表）——无一条满足三元组。

### 2.7 B132 —— together/baseten/fireworks

```
together.json：thinkingFormat="together"（个别 "openai"），sre 多数 false 个别 true
baseten.json：思考模型 thinkingFormat="baseten" + chatTemplateArgs={"enable_thinking":{"$var":"thinking.enabled"}}，
              其余 "openai"；detectCompat 对 baseten 无特殊臂（格式纯目录数据）
fireworks.json：仅 "openai" 格式，无特殊常量；provider 支持 anthropic-messages | openai-completions 两 API
```

Java 不携带这三个内置 provider/目录；`ThinkingFormatWriter` 的 TOGETHER/BASETEN 臂、
`ChatTemplateKwargValue` 与 models.json 的 `thinkingFormat`/`chatTemplateArgs` 显式键
（`ModelsJsonCompat.java:95-104`）都在 ⇒ 手搓 models.json 接三类后端的行为与 pi 等效
（探测也认 `api.together.ai/.xyz` 与 provider 名 `together`，`CompatResolver.java:64-65`）。

---

## 3. Java 现状（HEAD `5b91d3be`）

| 点 | 位置 | 现状 |
|---|---|---|
| 估算常数 | `ai/utils/Estimate.java:73` | `static final double CHARS_PER_TOKEN = 4;` |
| retry 白名单 | `ai/utils/RetryableError.java:68-119` | 有 `overloaded`/`server.?error`/`http2 request did not get a response`，无三新模式 |
| Mistral 文案 | `ai/protocol/MistralConversationsApi.java:180` | `"Provider stopped with: error"`；夹具 `MistralConversationsApiTest.java:129` 钉死旧文案 |
| xiaomi rrc | `ai/catalog/CatalogCompatRules.java:122-131`（`completions(...)` 的 `new ModelCompat(...)`） | 第 2 组件恒 `null`（rrc 三态缺席）⇒ 探测 false 生效 |
| ant-ling 写点 | `ai/protocol/ThinkingFormatWriter.java:125-130` | `case ANT_LING` 只发 `reasoning={effort}`（映射字符串），无穿透 |
| zai 常量 | `CatalogCompatRules.java:117-121` 注释自陈「不标，探测兜底」 | 与 pi 今值一致，无生产改动 |
| models.json rrc | `ModelsJsonSchema.java:153`/`ModelsJsonCompat.java:69` | 显式键三态透传，已通（B129 只差内置默认） |

---

## 4. 决策

| # | 决策 |
|---|---|
| D1 | B227 常数照改 **3.5**（double，三处除法自动跟随）；类 javadoc 的 pi 行号注释更新；**压缩估算器不碰**（§2.1 ⚠️） |
| D2 | B228 三模式按 pi 顺序插在同表（`server_busy` 两模式紧跟 `overloaded`；`pending stream…` 紧跟 http2 那条），注释带 pi 行号与 issue 号 |
| D3 | B229 只改文案（含 javadoc 与夹具期望）；超时半**保留 B229 在册**，台账行写明剩余半是传输形状 |
| D4 | B129 生产者放 `CatalogCompatRules.completions`：provider 为 `xiaomi` 或前缀 `xiaomi-token-plan` ⇒ rrc=TRUE（pi 四家目录全 true，Java 携带两家，前缀覆盖后续） |
| D5 | **B131 建议翻案为「修」**：在 `ANT_LING` 臂内复刻穿透——无映射字符串但「有级别 ∧ 解析后 sre=true」时按 pi `:971` 发原始级别名 `reasoning_effort`。理由：① 窄角在两侧都是手搓 models.json 可达，判据是行为相同；② 内置 ant-ling 数据 sre 恒 false ⇒ 内置路径零影响；③ 改动只在一臂内，exhaustive switch 结构不动。⚠️ 若你仍维持 2026-10-10「不改 switch」的旧裁决，则降级为「只加夹具钉 Java 现状＋移 C 类」，二选一在审核时定 |
| D6 | B130 转 §4 C 类，触发条件＝「`ModelData` 引入任何带 thinkingLevelMap 的 glm-5.x（或任何 sre 应为 true 的 zai 模型）时，`CatalogCompatRules` 的 effort 位必须改成按级别表有无数据驱动（`thinkingLevelMap != null`，对应 pi 生成器 `generate-models.ts` 的 `reasoning_options` 推导）」；加一条守卫夹具 |
| D7 | B132 转 §4 C 类，触发条件＝「携带 together/baseten/fireworks 内置 provider/目录时，常量按 `providers/data/*.json` 生成值随目录一起搬」；fireworks 无常量、只有 provider 注册 |
| D8 | 本包不碰 models.json schema（D5 穿透角所需的 `supportsReasoningEffort` 显式键早已支持） |

---

## 5. 计划的生产代码

### 5.1 Estimate.java（B227）

```java
    /** pi {@code estimate.ts:15}（锚点 ea448f454；27075fe07 #10497 起 4 → 3.5）。 */
    static final double CHARS_PER_TOKEN = 3.5;
```

### 5.2 RetryableError.java（B228，两处插入）

```java
            "overloaded",
            "server_busy", // retry.ts:33（8b5708dbb：server-busy 瞬断）
            "servers are currently busy", // retry.ts:34
            "currently experiencing high demand",
```
```java
            "http2 request did not get a response",
            // Node ERR_HTTP2_STREAM_CANCEL：HTTP/2 会话先死（Bedrock 5 分钟会话超时，#10379）。
            "pending stream has been canceled", // retry.ts:88（5b6c792b4）
```

### 5.3 MistralConversationsApi.java:180（B229 文案半）

```java
            // pi mistral-conversations.ts:948（7fb59f995）：补 "server error" 让 retry
            // 白名单的 server.?error 抓手生效，瞬态服务错误可重试。
            case "error" -> new MappedStopReason("error",
                "Provider stopped with: error (server error)");
```

### 5.4 CatalogCompatRules.java（B129）

`completions(...)` 内：

```java
        // B129：xiaomi 全族目录都标 requiresReasoningContentOnAssistantMessages=true
        // （新锚点 providers/data/xiaomi*.json 六个文件实测；探测给不出——isDeepSeek
        // 不认 xiaomi）。消费者 OpenAICompletionsMessageConverter:413-417 早等着。
        var requiresReasoningContent =
            "xiaomi".equals(provider) || provider.startsWith("xiaomi-token-plan")
                ? Boolean.TRUE : null;
```

构造器第 2 组件由 `null` 改 `requiresReasoningContent`。

### 5.5 ThinkingFormatWriter.java ANT_LING 臂（B131，仅当 D5 取「修」）

```java
            case ANT_LING -> {
                // pi :948-952：只认映射后的字符串值。
                var mapped = level.map(ModelThinkingLevel::of).flatMap(map::mapped);
                if (mapped.isPresent()) {
                    put(builder, "reasoning", Map.of("effort", mapped.orElseThrow()));
                } else if (level.isPresent() && supportsEffort) {
                    // B131：pi 链条在 ant-ling 臂未写时会穿透到 :969-971 通用 openai 臂
                    // （窄角：ant-ling 格式 + 显式 sre=true + 无级别表）——发原始级别名。
                    orLevel(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
```

（原表达式臂改块；无级别 ∧ sre=true ∧ off 缺席一格两边都不发，无需代码。）

---

## 6. 红先测试与变异探针

### 6.1 先红（生产未动时落夹具，录红集）

| 夹具 | 断言 | 预期红 |
|---|---|---|
| `EstimateTest` 新增 #10497 回归（同 pi 形状：usage 2000 锚 ＋ 3500 字符尾消息，窗口 10000） | trailing=1000、total=3000；`resolveMaxTokens`=**2904** | 新测红（2904 旧常数算得更小）；既有 11 字符⇒3、20 字符⇒5 两条期望改 **4／6** 后红 |
| `RetryableErrorTest` 三条 | `"server_busy"`／`"servers are currently busy: x"`／`"pending stream has been canceled"` ⇒ 可重试（且不命中配额表） | 3 红 |
| Mistral：`MistralConversationsApiTest:129` 期望改新文案；新增「该 errorMessage 经 `RetryableError` 判可重试」 | 新文案 + true | 2 红 |
| B129：`CatalogCompatRulesTest` 加 xiaomi/xiaomi-token-plan-cn ⇒ rrc=TRUE（其余 provider 仍 null）；新增/扩展线格夹具——xiaomi THINKING 模型、历史中无内容的纯 thinking assistant 消息 ⇒ 请求体含 `"reasoning_content":""`（经 RecordingHttpServer 抓体） | TRUE；体含空串键 | 2 红 |
| B131（D5 取修）：models.json 临时文件 provider `ant-ling`（或指向 api.ant-ling.com 的自定义 id）＋模型 compat `supportsReasoningEffort:true`、THINKING、无级别表；带级别请求 ⇒ 体含 `reasoning_effort`、不含 `reasoning` | 体含 effort | 1 红 |
| B130 守卫（不加生产）：解析后 compat 对 `zai/glm-4.7` 的有效 sre=false（null 由探测兜底） | 今即绿（守卫夹具备忘录性质） | 0（基线绿） |

### 6.2 变异探针（实现后，grep 确认落地→录红集→还原→复绿）

| 探针 | 变异 | 预期红 |
|---|---|---|
| M1 | `CHARS_PER_TOKEN` 改回 4 | #10497 回归 + 改动过的两条估算期望（恰 3） |
| M2 | 删 `"server_busy"` 一条（其余两模式同理各跑一次） | 恰对应 1 条 |
| M3 | Mistral 文案退回旧串 | 文案断言 + 可重试断言（2） |
| M4 | B129 生产者改回恒 null | xiaomi 单测 + 线格夹具（2）；非 xiaomi 用例全绿 |
| M5（仅 D5 取修） | 删掉 ANT_LING 臂的 `else if` 穿透 | 恰 B131 夹具 1 条；内置 ant-ling 用例全绿 |

探针落点一律先 `grep -n` 复核变异体真实落地（CRLF 教训：跨行探针用 Edit 不用 perl）。

### 6.3 计数

ai 模块新增/改动测试数以 Maven 模块汇总行为准（当前基线 **1445**）。

---

## 7. 台账与提交次序

1. `test(ai)`：红夹具（§6.1 全部），录红集。
2. `fix(ai)`：B227 常数 + EstimateTest 期望。
3. `fix(ai)`：B228 白名单三模式。
4. `fix(ai)`：B229 Mistral 文案。
5. `fix(ai)`：B129 CatalogCompatRules xiaomi 族 rrc。
6. `fix(ai)`：B131 ANT_LING 穿透（**仅 D5 取修时**）。
7. `docs(04,41,map)`：B129/B131 销号、B227/B228 销号；B130/B132 移 §4（带触发条件）；
   B229 行改写为「文案半已闭，超时半在册」；docs/41 §7 加批次行；map/02 行 102 更新。
8. M1–M5 探针记录回填本文件 §8（闭环时 banner 翻 ✅）。

每步 reactor 必须绿；stage 一律显式路径。

## 8. 验证（闭环时回填）

- ai 模块测试：`mvn -pl pi-java-ai -am test`，汇总行 Failures 0 / Errors 0，计数录实际值。
- checkstyle 0；改动文件无 `System.out.println`；文件 ≤500 行（本包最大改动在既有 737 行的
  ModelCompat **之外**，不增重 E5）。
- evals/tui `-am` 反应堆 SUCCESS（Mistral/Estimate 被 evals 夹具消费的可能性在实现时确认）。
