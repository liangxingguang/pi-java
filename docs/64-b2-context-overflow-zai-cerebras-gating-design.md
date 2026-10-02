# 64 — B2：ContextOverflow 的 z.ai / Cerebras 门控对齐

**状态：✅ 已闭环（2026-10-02，`0d41f94`，R1–R3 全按建议）**
**创建基准：** pi-java `9ffa2df`（B1 收尾）

> 依赖顺序（`docs/48`：B→D）：本包是 B 类最后一项，闭环后即可进 D3。
> 范围＝让 `ContextOverflow.isContextOverflow` 这个**谓词**与 pi 逐字一致；不新增 provider、
> 不改自动压缩/重试的调用策略。

---

## 1. pi 事实清单（file:line 已核）

源：`packages/ai/src/utils/overflow.ts`。

### 1.1 首条溢出正则同时认「prompt too long」与「prompt is too long」

`overflow.ts:38`
```ts
/prompt (?:is )?too long/i, // Anthropic and z.ai token overflow
```
⇒ `(?:is )?` 可选 ⇒ 同时命中 Anthropic 的 `"prompt is too long: X > Y"` 与 **z.ai 的
`{"code":"1261","message":"Prompt too long"}`**（无 `is`）。

### 1.2 ★ Cerebras 的 bodyless 判据是**按 provider 门控**的独立分支

`overflow.ts:64`
```ts
const CEREBRAS_BODYLESS_OVERFLOW_PATTERN = /^4(?:00|13)\s*(?:status code)?\s*\(no body\)/i;
```
`overflow.ts:145-147`（在 `stopReason==="error"` 且**不命中** NON_OVERFLOW 的块内，
通用 OVERFLOW_PATTERNS 之后）
```ts
if (message.provider === "cerebras" &&
    CEREBRAS_BODYLESS_OVERFLOW_PATTERN.test(message.errorMessage)) {
    return true;
}
```
⇒ 关键有两条：① 该模式**不在**通用 `OVERFLOW_PATTERNS` 列表里；② 只有
`message.provider === "cerebras"` 才生效。别的 provider 即便错误文案撞了同一正则也**不**算溢出。

### 1.3 bodyless 文案的出处

`"400 status code (no body)"` 由 **TS SDK 的 Stainless 生成错误类**给出
（`node_modules/@anthropic-ai/sdk/src/core/error.ts:42` `${status} status code (no body)`；
openai-node 同基）。Cerebras 走 openai-completions，bodyless 400/413 时得到该文案。

---

## 2. pi-java 现状差（逐条比对 OVERFLOW_PATTERNS，其余 23 条全一致）

源：`pi-java-ai/.../utils/ContextOverflow.java`。

| # | 点 | 现状 |
|---|---|---|
| 2.1 | ★ **首正则漏了 z.ai 的「无 is」形态** | `ContextOverflow.java:39` `Pattern.compile("prompt is too long", CASE_INSENSITIVE)` —— 写死了 `is`。z.ai 的 `"Prompt too long"` **不命中**。 |
| 2.2 | ★ **Cerebras 模式未做 provider 门控** | `ContextOverflow.java:63` 把 `^4(?:00|13)...(no body)` 直接塞进**通用** `OVERFLOW_PATTERNS` 列表，任何 provider 的错误文案撞了都判溢出 ⇒ 比 pi 宽（潜在误触发自动压缩）。 |
| 2.3 | z.ai provider 在 java 已注册 | `provider/builtin/ZaiProvider.java`（id `"zai"`、OPENAI_COMPLETIONS）。Cerebras **无**内置 provider（仅 models.json 可加 id `"cerebras"`）。 |

### 2.4 可达性取证（关键）

**Gap 2.1（z.ai）生产可达、且现在确实漏检：**
z.ai 超限回 HTTP 400，体为 `{"code":"1261","message":"Prompt too long"}`（无 `error` 外层）。
官方 **openai-java** 的 `ErrorHandler.kt:33`（4.42.0）只在有 `error` 键时按 ErrorObject 解析，
否则回退整节点；`UnexpectedStatusCodeException.kt:24` 的文案为
`"$statusCode: " + (error.isMissing() ? "Unknown" : writeValueAsString(error))`
⇒ 实际 errorMessage ＝ `400: {"code":"1261","message":"Prompt too long"}`。
- pi 正则 `/prompt (?:is )?too long/` 命中其中的 `"Prompt too long"` ⇒ 溢出；
- java 现有 `"prompt is too long"` ⇒ **不命中 ⇒ 漏检**（z.ai 已注册，真实路径）。

**Gap 2.2（Cerebras bodyless）正向例在 Java 不可达：**
openai-java 对 bodyless 400/413 产出的是 `"400: Unknown"`（`UnexpectedStatusCodeException.kt`，
error missing ⇒ `"Unknown"`），**不是** `"400 status code (no body)"`。故 Cerebras 那条
bodyless 判据的正向情形经官方 SDK **进不来**——与 B140 同类（SDK 生成文本差异）。
仍须把门控补齐，理由：① 与 pi 结构逐字一致；② 消除 2.2 的**过宽误判**（现把任意 provider
的 bodyless 文案当溢出）；③ models.json 若加 id `cerebras` 通道即正确。

**结论**：B2 是两处谓词偏差的收口，其中 z.ai 是生产可达的真漏检，Cerebras 是「门控形状
对齐 ＋ 登记 SDK 正向不可达」。

---

## 3. 改动方案

### Step 1 — 首正则补 z.ai 形态（ContextOverflow.java:39）

```java
Pattern.compile("prompt (?:is )?too long", Pattern.CASE_INSENSITIVE), // Anthropic / z.ai
```

### Step 2 — Cerebras 移出通用列表，改 provider 门控分支

- 删除 `OVERFLOW_PATTERNS` 末项（`:63`）；新增常量：
```java
/** pi CEREBRAS_BODYLESS_OVERFLOW_PATTERN（overflow.ts:64），仅 provider=cerebras。 */
private static final Pattern CEREBRAS_BODYLESS = Pattern.compile(
    "^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", Pattern.CASE_INSENSITIVE);
```
- Case 1 内在通用模式遍历之后（仍处于 `!isNonOverflow`），加：
```java
if ("cerebras".equals(message.provider())
        && CEREBRAS_BODYLESS.matcher(errorMessage).find()) {
    return true;
}
```

### Step 3 — 调用面零改动核对

`PostRunCompactionCheck:155`、`PostRunRetry:48` 已传终局消息；provider 由
`AbstractChatApi` 的身份挂载（`IdentitySubscriber:214-218`）在错误路同样写入，无需改签名。

**不做**：为让 Cerebras 正向可检而自造 `"(no body)"` 文案（pi 文案是 SDK 副产品，
自造＝发明行为）；Cerebras 内置 provider 接入（新 provider，超范围）。

---

## 4. 待裁决点（实施前）

- **R1 改动范围**：建议 (a) 两处都改、谓词严格对齐 pi（推荐）；(b) 只改 Step 1（z.ai 真漏检），
  Cerebras 保留在通用列表——不建议，留下过宽误判且与 pi 不符。
- **R2 Cerebras 正向不可达登记**：建议照 B140 口径，在 `docs/32` 新登一条
  「openai-java bodyless 文案为 `400: Unknown`、非 `status code (no body)` ⇒ Cerebras
  bodyless 正向不可达」，但门控仍照 pi 保留。
- **R3 z.ai 证据强度**：建议除纯谓词单测外，补一条 z.ai 400 JSON 的 wire 夹具
  （RecordingHttpServer 返体 ⇒ 终局消息 isContextOverflow=true），钉生产可达；旧实现先红。

---

## 5. 测试计划（RED 先）

更新 `ContextOverflowTest`：

1. **z.ai 无 is**：参数化溢出列表加入 `"Prompt too long"` 与
   `400: {"code":"1261","message":"Prompt too long"}`（旧首正则 ⇒ 后者先红）。
2. **wire（R3）**：RecordingHttpServer 对请求回 **400**＋z.ai JSON，驱动 OpenAICompletionsApi，
   取终局 AssistantMessage 断言 `isContextOverflow(msg, window)=true` 且无 Bearer 类副作用；
   旧实现 ⇒ 红。
3. **Cerebras 门控**：
   - 从通用参数化列表移除 `"413 status code (no body)"`/`"400 (no body)"`（旧 helper provider=null ⇒ 改后应为 false）；
   - 新用例：provider=`"cerebras"` ⇒ 两文案均 true；provider=`"openai"`/`null` ⇒ false（钉门控）。

Mutation：Step 1 回退为 `"prompt is too long"` ⇒ z.ai 红；去掉 provider 判断 ⇒ 非 cerebras 红。

**回归**：`pi-java-ai` 全模块；`pi-java-agent-core`（PostRunCompactionCheck/PostRunRetry）；全 reactor。

---

## 6. 风险

- 改后通用 bodyless 文案对非 cerebras provider 不再算溢出：本就与 pi 一致，且该文案 java 不产生 ⇒ 行为面零回归。
- 无真实 z.ai/cerebras key 端到端 ⇒ 以「形状照 pi ＋ 本地 wire 夹具」为证据（同各包口径）。

---

## 12. 实施记录

**2026-10-02 闭环，`0d41f94`（单 commit，3 files changed, +109/−6）。R1–R3 全按建议。**

- **RED（6 红，全部为预期原因）**：`errorMessagesAreOverflow` 恰 2 红（`"Prompt too long"`、
  z.ai 400 JSON）、`bodylessOverflowIsRejectedForOtherProviders` 3 红（null provider
  断言先挂）、`ZaiContextOverflowWireTest` 1 红（谓词 false；provider/stopReason/
  errorMessage 包含断言全已过 ⇒  wire 形状取证无误）。
- **GREEN**：Step 1 首正则改 `prompt (?:is )?too long`；Step 2 bodyless 模式移出
  `OVERFLOW_PATTERNS`、新增 `CEREBRAS_BODYLESS_OVERFLOW` 常量与 provider 门控分支
  （在通用遍历之后、仍处 `!isNonOverflow` 块内，照 pi `overflow.ts:145-147`）。
  Step 3 调用面零改动核对成立（身份由 `IdentitySubscriber` 错误路同挂）。
- **Mutation**：① 回退首正则 ⇒ 恰 3 红（wire＋2 参数项），恢复后绿；② 删 provider
  判断 ⇒ 恰 3 红（三条 bodyless 的 null-provider 断言），恢复后绿。
- **回归**：pi-java-ai **1215/1215**；pi-java-agent-core **525/525**（`-am`，
  谓词两调用方随跑）；checkstyle 0 violations。
- **登记**：新登 **B141**（openai-java bodyless 文案为 `400: Unknown`、非
  `status code (no body)` ⇒ Cerebras bodyless 正向经官方 SDK 不可达，门控照 pi
  保留），见 `docs/32`。
- **偏差**：无；z.ai 400 在 Java 的实际 errorMessage 经 wire 夹具确认为
  `400: {"code":"1261","message":"Prompt too long"}`（设计 §2.4 取证逐字兑现）。
