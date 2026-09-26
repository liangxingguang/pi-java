# 54 - 包 A-01：Anthropic `cache_control`（缓存断点、选项通道与压缩路径）

**状态：设计待审核（2026-09-27）—— 未写任何生产代码。**
> ⚠️ 本行是**活状态**，裁决后改为「已裁决并闭环」，须与 §12 实施记录一致（`docs/32` **B91** 的教训）。

**对应任务：** `docs/48 §5` 批次 B 第 1 行（Anthropic `cache_control`，P0，依赖 A1 与设计门 4）。`docs/48 §2` 的 **A-01**。
**参照台账：** `docs/41:55`（权重 3）、`docs/32` **B89**（顶层 `system` 字符串 vs 块数组，与本包一并裁决）。
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（两侧工作树均干净，实测期间核过）。

---

## 1. 范围

### 1.1 用户可观察后果

Anthropic 车道的 prompt caching **从不生效**：pi 默认在每次请求上打 1–3 枚缓存断点（顶层 `system`、工具表末项、消息表末条），java 一枚都不发 ⇒ 每轮全价、首 token 延迟更高。`docs/41:55` 记的正是这一条。

### 1.2 建议的包边界

**在范围内：**

| # | 内容 | pi 参照 |
|---|---|---|
| 1 | `CacheRetention` 三态类型 ＋ `getCacheControl` 的解析（选项 ?? `PI_CACHE_RETENTION` ?? `short`；`none` ⇒ 无断点；`long` ＋ 门 ⇒ `ttl:"1h"`） | `anthropic-messages.ts:60-83` |
| 2 | **三处落点**：顶层 `system` 块、工具表末项、消息表末条末块（含白名单与字符串→块数组的就地转换） | `:1077-1097`、`:1114`、`:1398-1424` |
| 3 | 两个 compat 字段的生产消费：`supportsLongCacheRetention`（anthropic 面）、`supportsCacheControlOnTools` | `:212-213` |
| 4 | **选项通道**：`cacheRetention` 从宿主经 `ApiOptions.extra` 到车道 | `types.ts:211` |
| 5 | **压缩路径的生产者**：摘要请求发 `cacheRetention:"none"` | `coding-agent/src/core/compaction/compaction.ts:603` |

**不在范围内（逐条登记，见 §10）：**

| 内容 | 为什么不在 | 归属 |
|---|---|---|
| Anthropic 会话亲和头（`x-session-affinity` 等） | 挂在**同一个** `cacheRetention` 上（`:563-564`），但产物是 **HTTP 头**而不是请求体断点；且 java 连 `sessionId` 通道都没有 | **B103**（新包） |
| Responses 车道的 `prompt_cache_key` / `prompt_cache_retention` / `prompt_cache_options` ＋ 两个门 | Java **已有半截**实现（`ResponsesMessageConverter:505-521`）且缺门、缺 `prompt_cache_options` ⇒ 是「修正既有偏差」，不是「新增」，需要自己的裁决 | **B104** |
| completions 的 `cacheControlFormat`（openrouter ＋ `anthropic/*`） | 判据是 `provider === "openrouter"`（`:1632`）⇒ **A-02（OpenRouter chat provider）之前不可达** | **B105** → 并入 A-02 |
| 宿主 `CacheWarmer`（主动预热：`sdk.ts:307`、`settingsManager.getCacheWarmingMode()`、`onWarmed → entry_appended`） | 一整条宿主特性（含 settings 开关与事件），不是车道线格式 | **B106**（宿主层包） |
| Bedrock `cachePoint` / Codex `prompt_cache_key` / Mistral 会话亲和 | java 无对应车道（Bedrock/Codex）或不可达 | E 批次 / 已登记 |
| OAuth 身份块（`"You are Claude Code…"`，pi `:1077-1090` 的**两块**形状） | 是 **A-15** 的活；本包只把 java 现有的那一块改成块形态 | A-15 |

### 1.3 与 B89 的关系（一并裁决）

`docs/32` **B89** 记的是「Anthropic 顶层 `system` 是字符串形态，pi 是块数组」。**§7 的 P18 实测把它的口径钉死了：pi 的 `system` 在 `cacheRetention:"none"` 时也仍是块数组**（只是块上没有 `cache_control`）⇒ 这不是「缓存带来的形状变化」，而是 pi 的**无条件线形状**。故 B89 的修法与缓存无关，只是恰好由本包落地。**建议随本包结案。**

---

## 2. 命题表（pi 侧；均已实读源码并逐条实测）

> 行号一律指锚点 `3390bd936` 的 `packages/ai/src/api/anthropic-messages.ts`（除注明外）。`实测` 列指 §7 的探针编号。

| # | 命题 | 出处 | 实测 |
|---|---|---|---|
| P1 | `CacheRetention = "none" \| "short" \| "long"`，是 **`StreamOptions`** 上的字段（不是某条车道私有的） | `types.ts:109`、`:211`；`sessionId` 在 `:217` | — |
| P2 | 解析是**三态回落**：`cacheRetention ?? (PI_CACHE_RETENTION === "long" ? "long" : "short")`。⚠️ 环境变量只在**字面等于 `"long"`** 时生效 | `:60-67` | P4 / P4b |
| P3 | `none` ⇒ **没有 `cacheControl` 对象**，三处落点全部消失；`supportsLongCacheRetention` 只控 **`ttl`**，不控断点本身 | `:75-82` | P2 / P18 / P6 |
| P4 | 顶层 `system` **恒为块数组**（`params.system = [{type:"text", text, …}]`），与缓存无关 | `:1077-1097` | P18 |
| P5 | OAuth 令牌时顶层 `system` 是**两块**，**两块都挂断点** | `:1077-1090` | P17 |
| P6 | 工具：断点挂**末项**；`supportsCacheControlOnTools` 为假 ⇒ **整表不挂**（`system`／消息仍挂） | `:1114`、`:1489` | P1 / P5 |
| P7 | nativeToolChanges 支：断点挂 **`initialTools` 的末项**，迟到工具与占位符都不挂 | `:1119-1131` | P10 |
| P8 | 消息：请求体**组装完成后**，看**最后一条**消息；只在 `role` 是 `user` 或 `system` 时挂，且挂在它的**末块**上 | `:1398-1405` | P8 / P9 / P13 |
| P9 | 末块类型必须在白名单 `text \| image \| tool_result \| tool_addition \| tool_removal` 内，否则**静默不挂** | `:1406-1413` | P10 / P12 / P13 / P16 |
| P10 | 末条消息的 `content` 若是**字符串**，会被**就地转成块数组**好挂断点（`none` 时不转） | `:1417-1422` | P14 vs P2 |
| P11 | 断点判定发生在 `flushPendingSystemMessages()` **之后** ⇒ 末尾刷出的 held 系统消息会成为「最后一条」并吃下断点 | `:1395-1399` | P10 |
| P12 | 压缩摘要路径主动关缓存：`cacheRetention:"none"` ＋ `sessionId: options.sessionId ?? uuidv7()` | `coding-agent/src/core/compaction/compaction.ts:600-605` | — |
| P13 | 环境变量经 `getProviderEnvValue` 读（`utils/provider-env.ts`）；java 的对等物是 `System::getenv`（本仓既有方言） | `:64` | — |
| P14 | pi 一次请求最多发 **3 枚**断点（`system` ＋ 末工具 ＋ 末消息）；OAuth 时是 **4 枚**（两块 `system`） | 三处落点计数（P1/P10/P17 实测可见） | P1/P10/P17 |

---

## 3. 顺带发现（Java 侧；今天可核实的状态）

### F1 —— **两条选项通道在生产上恒空**（结构性）

`DefaultProviders.apiOptions(...)` 的第五个实参是 `Map.of()`，`streamBlocking(...)` 造 `StreamRequest` 时第七个实参也是 `Map.of()`。⇒ `ResponsesOptions` 的**五个键**（`reasoningEffort` / `reasoningSummary` / `serviceTier` / `cacheRetention` / `sessionId`）**全部生产不可达**；`ResponsesMessageConverter.applyCacheRetention` 在生产上**恒走 `SHORT` 分支**。登记为 **B107**。

> 这不是「通道不存在」——`StreamApi.stream(StreamRequest, ApiOptions)` 每次都收 `ApiOptions`，且 `provider.createApi(...)` 是**每请求新建**车道（`DefaultProviders:127`）⇒ 「构造期读 options」的时点是对的。**只是从来没有人往里写值。**

### F2 —— `StreamOptions`（agent-core）缺三个组件

pi 的 `SimpleStreamOptions` 有 `cacheRetention` / `sessionId` / `env`（`types.ts:324-336` 继承 `:179-217`）；java 的 `StreamOptions` 只有 `maxTokens` / `temperature` / `reasoning`。本包只补 `cacheRetention`：`sessionId` 归 **B103**（会话亲和头族），`env`（`ProviderEnv` 覆盖袋）java 历来用 `System::getenv` 直读、属凭证线的既有口径差异（A-15/A0），**本包不引入**新的 env 通道。

### F3 —— 既有 Responses cache 实现是**手搓近似**

`ResponsesMessageConverter:505-521`：缺 `supportsLongCacheRetention` 与 `supportsExplicitPromptCacheMode` 两个门（pi `openai-responses.ts:83-100`），缺 `prompt_cache_options`。⇒ `supportsExplicitPromptCacheMode:true` 的模型上 java 会**多发** `prompt_cache_retention:"24h"` 且从不发 `prompt_cache_options`。归 **B104**。

### F4 —— SDK 能力已逐项实测（非读 javap 猜测）

A3 的教训（`docs/51 §12.4`：一度据 `javap` 工厂清单判「SDK 表达不了」，实为误判）⇒ 本包把六项能力**跑出来**给结论，逐字输出见 §7.2：

| 需要的能力 | SDK 侧 | 实测 |
|---|---|---|
| `system` 的块形态 | `MessageCreateParams.Builder.systemOfTextBlockParams(List<TextBlockParam>)` | J1 |
| `{type:"ephemeral"}` | `CacheControlEphemeral.builder().build()` | J2 |
| `{…,ttl:"1h"}` | `.ttl(CacheControlEphemeral.Ttl.TTL_1H)` | J3 |
| 工具上挂 | `Tool.Builder.cacheControl(...)` | J4 |
| text / image / tool_result 块上挂 | 三者的 `Builder.cacheControl(...)` ＋ `ContentBlockParam.isX()/asX().toBuilder()` 重建 | J5–J7 |
| `tool_addition` / `tool_removal`（原始 JSON 直通）上挂 | `_json()` → 加键 → `treeToValue` 回读 | J8–J9 |

### F5 —— 直通块加 `cache_control` 后**键序与 pi 逐字节相同**

J9 的输出 `{"type":"tool_addition","tool":{…},"cache_control":{"type":"ephemeral"}}` 与 pi 探针 P10 的末块**逐字节一致** —— 因为两者都走原始 JSON。**但**类型化路径（`system` / 工具 / 文本块）的键序与 pi 不同（§4.7）。

### F6 —— `tool_addition`/`tool_removal` 由 java 从 `LinkedHashMap` 现造

`AnthropicRequestBuilder.toolReferenceBlock`（`:286-299`）自己拼 Map 再 `treeToValue` ⇒ 理论上可以在**造块时**就把 `cache_control` 放进去。但**末块判定发生在消息组装之后**（pi 同），而这两个块既可能是末块也可能不是 ⇒ 统一走 §4.3 的重建助手，避免两条构造路径。两条都已实测可用（J8/J9 与 P10 对照）。

### F7 —— A-15 未落 OAuth 身份块

java 的 OAuth 分支只设了两枚头（`AnthropicMessagesApi:74-80`），**不发** `"You are Claude Code…"` 块 ⇒ P17 的 oracle 对 java 只有后半块可比。本包不补 A-15，但**要保证补 A-15 时不必再动缓存代码**（形状见 §4.5）。

### F8 —— 压缩路径的确切落点

`LlmSummaryGenerator:221` 造 `StreamOptions(maxTokens, temperature, reasoning)`，随即 `:226` 调 `streamFn.stream(...)`。**这就是 pi 那个 `cacheRetention:"none"` 的落点。**

### F9 —— `StreamOptions` 只有 **4 个构造点**

`StreamOptions.defaults()` · `StreamSimple:76` · `PiLoopRunner:224` · `LlmSummaryGenerator:221` ⇒ 加组件用**便捷构造器**可做到**零改签**（A-07 已验证同法有效，`docs/53 §12.3`）。

### F10 —— 既有的 `system` 断言**同时接受两种形态**（B89 因此零连带红、也零守门）

`AnthropicSurrogateSanitizeTest.payloads()`（`:77-85`）是**唯一**读 `params.system()` 的 Anthropic 夹具，且它按 `s.isString()` / `s.asTextBlockParams()` **两分支**取值 —— 当初就是为两种形态写的。⇒ 改块形态**不会让它变红**（好消息：零连带改动），但也**没有既有夹具守 B89 的形状**（坏消息：先红只能来自新夹具）。

⚠️ 连带后果：A-01 落地后 `s.isString()` 分支在该夹具里**变成死分支**（Anthropic 车道再也产不出字符串 `system`）⇒ 实施时**顺手简化**为直接取块。这是本包**唯一**预见到的既有夹具改动。

---

## 4. 设计

### 4.1 新类型：`CacheRetention`（`com.pijava.ai.api`）

```java
/** pi {@code types.ts:109}：{@code "none" | "short" | "long"}。 */
public enum CacheRetention {
    NONE, SHORT, LONG;
    public static Optional<CacheRetention> parse(String raw) { … }   // 未知/空 ⇒ empty（交回落）
}
```

放在 `api` 包而不是 `protocol`：它是**跨车道**的请求选项（pi 亦然），且 `agent-core` 的 `StreamOptions` 要引用它。

### 4.2 解析：`CompatResolver` 增设缓存解析（返回**纯值**，不带 SDK 类型）

⚠️ `CompatResolver` 在 `catalog` 包，**不得**泄漏 `com.anthropic.*` 类型到那里。故解析层返回一个纯记录：

```java
/** 一条缓存断点的规格；{@code oneHourTtl} 只由 {@code long} ＋ 门决定。 */
public record CacheBreakpointSpec(boolean oneHourTtl) { }

/** pi {@code anthropic-messages.ts:60-83} 的 {@code getCacheControl}。 */
public static Optional<CacheBreakpointSpec> anthropicCacheControl(
        ModelInfo model, Optional<CacheRetention> requested, String envRetention) { … }
```

三个分支与 pi 一一对应：

| 条件 | 返回 |
|---|---|
| 解析后 `NONE` | `Optional.empty()` |
| `LONG` 且 `supportsLongCacheRetention`（缺省 `true`） | `Optional.of(spec(oneHourTtl=true))` |
| 其余（`SHORT`；或 `LONG` 但门为假） | `Optional.of(spec(oneHourTtl=false))` |

解析规则逐字照抄 P2：`requested.orElseGet(() -> "long".equals(envRetention) ? LONG : SHORT)`。⚠️ **未知字符串一律回落到 `SHORT`**（P2：只有字面 `"long"` 算 long；`"none"` 认，其他不认）。

车道的翻译（`protocol` 包内）：

```java
private static CacheControlEphemeral toSdk(CacheBreakpointSpec spec) {
    var b = CacheControlEphemeral.builder();
    if (spec.oneHourTtl()) { b.ttl(CacheControlEphemeral.Ttl.TTL_1H); }
    return b.build();
}
```

### 4.3 三处落点（`AnthropicRequestBuilder`）

**(a) 顶层 `system`** —— 改 `systemOfTextBlockParams`，块上挂断点。**无条件**改块形态（P4/P18）；断点仅在 spec 在场时挂。两块的 OAuth 形状留给 A-15（§4.5）。

**(b) 工具表** —— `addTools` 的末项挂；nativeToolChanges 支挂的是 **`initialTools` 的末项**（P7）。门：`supportsCacheControlOnTools`（P6）。`deferredToolPlaceholder()` 与迟到工具**不挂**。

**(c) 消息表** —— 这是唯一有结构难度的落点。pi 是「构造完 `params[]` 之后改最后一个元素」；java 现在**边遍历边 `builder.addMessage(...)`**，改成同形：

```
addMessages(...):
    List<Row> rows            // Row = (role, List<ContentBlockParam> blocks)
    遍历转录 → rows.add(...)   // 系统消息攒进 pending，其余直接成 row
    末尾 flush pending → rows
    // pi :1398-1424 的落点判定，逐条照抄
    if (spec 在场 && !rows.isEmpty()) {
        var last = rows.get(rows.size() - 1);
        if (last.role == USER || last.role == SYSTEM) {
            var blocks = last.blocks;
            if (!blocks.isEmpty()) {
                var patched = withCacheControl(blocks.get(n-1), sdkSpec);
                if (patched != null) { blocks.set(n-1, patched); }
            }
        }
    }
    for (row : rows) builder.addMessage(row.toMessageParam());
```

`withCacheControl(ContentBlockParam, CacheControlEphemeral)`：按 P9 的白名单分派 ——

```
isText()       → ContentBlockParam.ofText(asText().toBuilder().cacheControl(cc).build())
isImage()      → ContentBlockParam.ofImage(asImage().toBuilder().cacheControl(cc).build())
isToolResult() → ContentBlockParam.ofToolResult(asToolResult().toBuilder().cacheControl(cc).build())
_json() 在场且 type ∈ {tool_addition, tool_removal}
               → 取 _json() 的 Map、put("cache_control", {"type":"ephemeral"[,"ttl":"1h"]})、treeToValue 回读
其余            → 返回 null（静默不挂，照抄 P9 的 else）
```

⚠️ **SDK 是否持有 `blocks` 的引用（而非复制）本设计期未实测** —— Kotlin `data class` 通常不复制，但那只是推测。**设计不依赖它**：一律「先改 `blocks`，再构造 `MessageParam`」，这正是上面「先攒 Row、最后统一 wrap」的原因。§6.2 的 **M4**（把 patch 移到 wrap 之后）正好把这个事实**测出来**：若 M4 零红 ⇒ 说明 SDK 持有引用（patch 放哪都一样）；若 M4 有红 ⇒ 顺序是必需的。**两种结果都要如实记进 §12**（对照 A-07 的 M2：零红也是有价值的信息）。

**(d) 字符串 `content` 的就地转换**（P10）：java 侧等价物是「user 消息只产出一个 `text` 块」。pi 的转换只发生在**末条**且仅在 spec 在场时 ⇒ java 的自然写法（块数组里已是 text 块）**已经等价**，无需额外分支；但要有夹具钉「`none` 时 user 消息保持 pi 的原形状」。

### 4.4 选项通道（一条最小链路）

```
LlmSummaryGenerator ──┐
                      ├─→ StreamOptions.cacheRetention (Optional<CacheRetention>)
PiLoopRunner ─────────┘            │
                                   ↓  DefaultProviders.streamBlocking
                        ApiOptions.extra.put("cacheRetention", name())
                                   ↓  AnthropicMessagesApi 构造期读取
                        Optional<CacheRetention>  ──→ buildParams(request, that)
                                   ↓
                        CompatResolver.anthropicCacheControl(model, that, System.getenv("PI_CACHE_RETENTION"))
```

- `StreamOptions` 加 `Optional<CacheRetention> cacheRetention` ＋ **便捷构造器**（三参形态保留 ⇒ 4 个构造点零改签）。
- `ApiOptions.extra` 用字符串键 `"cacheRetention"`：**沿用既有的 java 方言**（`ResponsesOptions.from` 同形）。
- `AnthropicMessagesApi` 构造期读一次（车道每请求新建，时点正确，见 F1）；`buildParams` 因此要**加一个形参** ⇒ `AnthropicCompatWireTest` 等**反射夹具**会运行期炸（`docs/50 §12.2-2` 的教训）⇒ 同包改成**直调**。
- `PI_CACHE_RETENTION` 由 `System::getenv` 读（F13 的 java 方言），**不**引入 pi 的 `ProviderEnv` 参数（那是 A-15/凭证线的形状）。

### 4.5 与 A-15 的关系（形状预留）

A-15 落地时 `system` 会是**两块**（身份块 ＋ 正文块），两块都挂断点（P5）。本包把「构造 system 块列表」写成**一个小 helper**（`systemBlocks(text, spec)` 返回 `List<TextBlockParam>`），A-15 只需在该列表**前面**插一块并复用同一个 spec ⇒ 不必再动缓存代码。

### 4.6 白名单兜底分支：照抄，但如实登记「在可达输入上不可观察」

P9 的 `else`（末块不在白名单 ⇒ 静默不挂）落在 java 的可达输入上**似乎无法触发**：

- 末条是 `user` ⇒ 其块只可能是 `text` / `image` / `tool_result`（三者都在名单上，含 H2 的图片降级占位串＝`text`）；
- 末条是 `system` ⇒ 其块序是 `[text?, …tool_removal, …tool_addition]`（三者都在名单上）；
- 空块列表的消息**不落线**（java `continue`／pi 不 push）。

⇒ 按项目纪律（H2 的两处「车道侧能力门」同型，`docs/44 §10`）**照抄保留并在代码里注明「没有出参、不是夹具没牙」**，不伪造一条夹具。

### 4.7 已知并接受的差异：类型化字段的**键序**

J1/J4/J5 的 SDK 输出键序是 `{text, type, cache_control…}` / `{input_schema, name, cache_control, description}`，pi 是 `{type, text, …}` / `{name, description, …, cache_control}`。**JSON 对象键序无语义**，且 java 侧所有经 SDK 序列化的字段历来如此（非本包引入）。**唯一需要当心的是夹具**：比对必须**按键取值**而不是**逐字节比对**；§7.1 的 13 条 oracle 移植时按此写。（唯一的逐字节可比之处是原始 JSON 直通路径，见 F5。）

---

## 5. 拆分（四步，与 A2/A3/A7 同粒度）

| 步 | 内容 | 模块 | 预估 |
|---|---|---|---|
| **A1a** | `CacheRetention` ＋ `CacheBreakpointSpec` ＋ `CompatResolver.anthropicCacheControl` ＋ 单测 | `ai` | ~180 行 |
| **A1b** | `AnthropicRequestBuilder` 三处落点 ＋ `withCacheControl` 助手 ＋ `addMessages` 改 Row 形 | `ai` | ~200 行 |
| **A1c** | 选项通道：`StreamOptions` 加组件（＋便捷构造器）· `DefaultProviders` 转发 · `AnthropicMessagesApi` 读取与形参 · `LlmSummaryGenerator` 发 `none` | `agent-core`＋`coding-agent`＋`ai` | ~150 行 |
| **A1d** | wire 夹具（§7.1 的 13 条 oracle 移植）＋ 文档回填 | `ai`＋`docs` | ~350 行 |

⚠️ **A1b 与 A1c 的顺序**：A1b 可先落（默认 `short` 行为即生效，**生产可观察**），A1c 补 `none`/`long` 的入口。**两步之间会存在一段「压缩路径多发断点」的已知偏差** —— 若审核要求不出现中间态，则 A1b＋A1c 必须**同一次提交**（推荐后者，见 §9 R3）。

---

## 6. 先红与变异矩阵（**设计期预测**；实施后按 §12 对账并改写）

### 6.1 先红的形态

本包的先红**不能靠编译失败**（新类不参与既有夹具），故用两种手法：

1. **新夹具 + `git stash push -- <实现文件>` 复跑**（A2b 用过的手法，`docs/49 §12.2`）：13 条夹具在旧实现下应当**全红**，且红因分两类 —— `system` 是字符串（`AssertionError`）与「三处都没有 `cache_control`」。
2. **`system` 形状的单独先红**：先说结论 —— **没有既有夹具守它**。设计期逐文件核过 `pi-java-ai/src/test` 里读 `params.system()` 的夹具只有 `AnthropicSurrogateSanitizeTest` 一处，而它按 `isString()` / `asTextBlockParams()` **两分支**取值（§3 F10）⇒ 改块形态**零连带红**。**B89 的先红因此只能由新夹具提供**（§7.1 的 P1/P2/P18 三条移植过去即可），这条要写进 §12。

   ⚠️ 另一个方向也要钉：`AnthropicToolChangesWireTest` 只断 `tools` 与 `messages`，**不断 `system`** ⇒ pi 的 `sysCtx` 那一支（顶层 system 带断点）在本包之前**完全无人守**。

3. **conformance 不受影响**（实测）：`conformance/java-out/*.jsonl` 的金标全是 `openai-responses`＋mock provider（`grep -l anthropic` 零命中），`"system"` 字段来自**消息记录**而非 anthropic 请求体 ⇒ A-01 不会动那道回归门，闭环时 conformance 应仍 15/15。

### 6.2 预测的变异红集

| 变异 | 预期红 |
|---|---|
| M1 关掉 `system` 上的断点 | 3（P1/P4/P17 类） |
| M2 关掉工具断点 | 3（P1/P3/P10 类） |
| M3 关掉消息断点 | 6（P1/P9/P12/P13/P14/P15 类） |
| M4 把 `patch` 移到 `MessageParam` 构造**之后** | 6（同 M3 —— 若不等价则说明 SDK 持有了 list 引用，**这是本包最想知道的一个事实**） |
| M5 `supportsCacheControlOnTools` 门恒真 | 1（P5） |
| M6 `supportsLongCacheRetention` 门恒真 | 1（P6） |
| M7 `none` 不生效 | 4（P2/P18） |
| M8 env 只在 `"long"` 生效 ⇒ 改成「非空即 long」 | 1（P4b） |
| M9 末条是 assistant 时也挂 | 1（P8） |
| M10 通道：`DefaultProviders` 不转发 `cacheRetention` | 1（压缩路径的 `none`） |

⚠️ 预告两种可能的**假红/零红**：`A && B ;` 型假变异（`docs/53 §12.7` 第 2 次兑现），以及**语义等价**型零红（同文第 1 种成因）—— 若 M4 零红，须先判是「等价」还是「没牙」。

### 6.3 验收 grep（设计期写出，实施时实测并回填）

```bash
# 1. 三处落点都在
grep -rn "cacheControl(" pi-java-ai/src/main/java/com/pijava/ai/protocol/AnthropicRequestBuilder.java
#    ≥3 处：system 块、工具末项、消息末块

# 2. 两个 compat 门被读
grep -rn "supportsLongCacheRetention\|supportsCacheControlOnTools" \
    pi-java-ai/src/main/java/com/pijava/ai/catalog/
#    恰 1 处（解析层），零处在车道里（A-07 的纪律：compat 只读解析结果）

# 3. 通道
grep -rn "cacheRetention" pi-java-agent-core/src/main pi-java-coding-agent/src/main
#    StreamOptions 组件 + DefaultProviders 转发 + LlmSummaryGenerator 的生产者

# 4. 旧形状已消失
grep -rn "\.system(" pi-java-ai/src/main/java/com/pijava/ai/protocol/AnthropicRequestBuilder.java
#    0 命中（字符串重载）
```

---

## 7. 实测记录（逐字）

### 7.1 pi 侧探针（`cache_control` 的 oracle）

手法：抄 pi 自己的 `streamSimple` ＋ `onPayload`（`baseUrl: "http://127.0.0.1:9"`，与 `transcript-tool-changes.test.ts` 同法），**13 条全部跑通**。逐字输出：

```
P1.system  = [{"type":"text","text":"You are a helpful assistant.","cache_control":{"type":"ephemeral"}}]
P1.tools   = [{"name":"base_tool","description":"base_tool tool","eager_input_streaming":true,
               "input_schema":{"type":"object","properties":{},"required":[]},
               "cache_control":{"type":"ephemeral"}}]
P1.messages= [{"role":"user","content":[{"type":"text","text":"Hello","cache_control":{"type":"ephemeral"}}]}]

P2 (none).tools    = [… 无 cache_control，连 eager_input_streaming 仍在]
P2 (none).messages = [{"role":"user","content":"Hello"}]        ← 退回字符串形态

P3 (long).tools    = [{"name":"base_tool",…,"cache_control":{"type":"ephemeral","ttl":"1h"}}]
P3 (long).messages = [{"role":"user","content":[{"type":"text","text":"Hello","cache_control":{"type":"ephemeral","ttl":"1h"}}]}]

P4  env=long   .system = [{"type":"text","text":"You are a helpful assistant.","cache_control":{"type":"ephemeral","ttl":"1h"}}]
P4b env=weird  .system = [{"type":"text","text":"You are a helpful assistant.","cache_control":{"type":"ephemeral"}}]

P5 supportsCacheControlOnTools=false + long:
   .tools     = [{"name":"base_tool",…}]                        ← 整表不挂
   .messages  = [{"role":"user","content":[{"type":"text","text":"Hello","cache_control":{"type":"ephemeral","ttl":"1h"}}]}]

P6 supportsLongCacheRetention=false + long:
   .system = [{"type":"text","text":"You are a helpful assistant.","cache_control":{"type":"ephemeral"}}]   ← 只剩无 ttl
   .tools  = [{"name":"base_tool",…,"cache_control":{"type":"ephemeral"}}]

P8 末条为 assistant:
   .messages = [{"role":"user","content":"hi"},{"role":"assistant","content":[{"type":"text","text":"hello"}]}]
   ← 一个字都没挂（连字符串都没转）

P9 多条 user:
   .messages = [{"role":"user","content":"one"},
                {"role":"assistant","content":[{"type":"text","text":"a"}]},
                {"role":"user","content":[{"type":"text","text":"two","cache_control":{"type":"ephemeral"}}]}]

P10 nativeToolChanges:
   .tools = [{"name":"base_tool",…,"cache_control":{"type":"ephemeral"}},
             {"name":"__pi_deferred_placeholder__",…,"defer_loading":true},
             {"name":"late_tool",…,"defer_loading":true}]
   .messages = [{"role":"user","content":"before"},
                {"role":"system","content":[{"type":"text","text":"updated guidance"},
                 {"type":"tool_removal","tool":{"type":"tool_reference","name":"base_tool"}},
                 {"type":"tool_addition","tool":{"type":"tool_reference","name":"late_tool"},
                  "cache_control":{"type":"ephemeral"}}]}]
   .system = [{"type":"text","text":"base prompt","cache_control":{"type":"ephemeral"}}]

P13 末条为 toolResult:
   .messages = […,{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"done",
                  "is_error":false,"cache_control":{"type":"ephemeral"}}]}]

P14 末条 user 为裸字符串 + long:
   .messages = [{"role":"user","content":[{"type":"text","text":"plain string","cache_control":{"type":"ephemeral","ttl":"1h"}}]}]

P15 非视觉模型 + image:
   .messages = [{"role":"user","content":[{"type":"text","text":"look"},
                  {"type":"text","text":"(image omitted: model does not support images)","cache_control":{"type":"ephemeral"}}]}]

P16 视觉模型 + image:
   .messages = [{"role":"user","content":[{"type":"text","text":"look"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="},
                   "cache_control":{"type":"ephemeral"}}]}]

P17 OAuth（sk-ant-oat01-…）:
   .system = [{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.","cache_control":{"type":"ephemeral"}},
              {"type":"text","text":"be brief","cache_control":{"type":"ephemeral"}}]

P18 none + 有系统提示:
   .system = [{"type":"text","text":"You are a helpful assistant."}]     ← 仍是块数组，只是没断点
   .tools  = [{"name":"base_tool",…}]                                    ← 也没有
```

**同时跑过的 pi 自带套件**（oracle 的第一层）：

```
packages/ai/test/{cache-retention, openai-completions-cache-control-format,
                  openrouter-cache-control-models, openai-completions-prompt-cache}.test.ts
→ Test Files 1 failed | 3 passed · Tests 2 failed | 47 passed | 4 skipped
```

⚠️ **那 2 条红与本包无关，是数据漂移**：`openai-completions-prompt-cache.test.ts:174-181` 用 `getModel("fireworks", "accounts/fireworks/models/glm-5p2")`，而 `packages/ai/src/providers/data/fireworks.json` **被 gitignore**、由 `generate-models.ts` 从 models.dev 现拉 —— 该 id 已改名，文件里查无此模型（`grep -o '"glm-5p2[^"]*"' data/fireworks.json` 零命中）。**与 `docs/53 §10` B97 同型**（内置目录 id 与 pi 不匹配）。

### 7.2 Java 侧探针（SDK 可行性，跑完即删）

手法：临时 JUnit 探针（`ZzCacheControlProbeTest`，**已删除、未提交**），把 SDK 的序列化结果打出来。

```
J2 CC-short  = {"type":"ephemeral"}
J3 CC-long   = {"type":"ephemeral","ttl":"1h"}
J1 SYS-BLOCK = [{"text":"You are a helpful assistant.","type":"text","cache_control":{"type":"ephemeral"}}]
J4 TOOL-CC   = [{"input_schema":{…},"name":"lookup","cache_control":{"type":"ephemeral"},"description":"Look up a value"}]
J5 REBUILD-t = {"text":"t","type":"text","cache_control":{"type":"ephemeral"}}
J6 REBUILD-i = {"source":{"data":"aGk=","media_type":"image/png","type":"base64"},"type":"image",
                "cache_control":{"type":"ephemeral","ttl":"1h"}}
J7 REBUILD-r = {"tool_use_id":"t1","type":"tool_result","cache_control":{"type":"ephemeral"}}
J8 RAW-PASS  = {"type":"tool_addition","tool":{"type":"tool_reference","name":"late_tool"}}
   RAW-class = com.anthropic.core.JsonObject
J9 RAW-PATCH = {"type":"tool_addition","tool":{"type":"tool_reference","name":"late_tool"},
                "cache_control":{"type":"ephemeral"}}
   MSG-PATCH = {"content":[{"type":"tool_addition",…,"cache_control":{"type":"ephemeral"}}],"role":"system"}
```

结论：§4 的三处落点在 SDK 上**全部可表达**，且 J2/J3 与 pi 的 `{type:"ephemeral"}` / `{type:"ephemeral",ttl:"1h"}` **逐字节相同**；J9 的直通块与 pi 的 P10 **键序也相同**。唯一的类型化键序差异见 §4.7。

⚠️ 探针本身踩的一坑：`MessageCreateParams.Builder.build()` 要求 `messages` 必填（`` `messages` is required, but was not set ``，`Check.kt:12`）⇒ 只造工具表的探针要先塞一条消息。**实施写夹具时会再遇到。**

---

## 8. 遗留登记（本包预计产出，实施后落 `docs/32`，编号从 **B103** 起）

| 编号 | 事项 | 依据 |
|---|---|---|
| **B103** | **Anthropic 会话亲和头族未落**：`sendSessionAffinityHeaders` / `sessionAffinityFormat` / `x-session-affinity`，且 java 无 `sessionId` 通道。它挂在同一条 `cacheRetention` 上（`:563-564`：`none` ⇒ 不发），但产物是 HTTP 头 | `docs/53 §4.4` 零消费者列 |
| **B104** | **Responses 车道的 cache 半截实现需修正**：缺 `supportsLongCacheRetention` / `supportsExplicitPromptCacheMode` 两门、缺 `prompt_cache_options`（`supportsExplicitPromptCacheMode:true` 的模型上 java 多发 `prompt_cache_retention` 且从不发 options） | 本包 §3 F3 |
| **B105** | completions 的 `cacheControlFormat`（openrouter ＋ `anthropic/*`）—— **A-02 之前不可达** | `:1632` 判据含 `provider === "openrouter"` |
| **B106** | 宿主 `CacheWarmer`（主动预热 ＋ `settings.cacheWarmingMode` ＋ `onWarmed → entry_appended`）整条未落 | `sdk.ts:307`、`cache-warmer.ts:162` |
| **B107** | **两条请求选项通道在生产上恒空**（`ApiOptions.extra` 与 `StreamRequest.extra` 都是 `Map.of()`）⇒ `ResponsesOptions` 五个键全部生产不可达。本包只打通 `cacheRetention` **一个**键；其余四个键与 `StreamRequest.extra` 的存废需要自己的裁决 | 本包 §3 F1 |
| **B108** | `docs/41:55` 的权重行（闭环后划掉）＋ `docs/32` **B89** 结案 | 本包 §1.3 |

---

## 9. 裁决点（请审核时给结论）

| # | 裁决点 | 我的建议 | 理由 |
|---|---|---|---|
| **R1** | 包边界：只做 Anthropic 车道，还是顺带修正 Responses 的半截实现？ | **只做 Anthropic**；Responses 记 **B104** | 两个是不同车道的不同落点；混在一起会让「先红」的归因分叉（A2 的教训：R5 顺序排错导致 11/11 红全是同一个原因） |
| **R2** | `CacheRetention` 放哪 | `com.pijava.ai.api` 的 **enum** | 它是跨车道请求选项（pi 亦在 `types.ts` 层）；`agent-core` 要引用它 |
| **R3** | 选项通道是否本包做（A1c） | **做，且 A1b/A1c 同一次提交** | 不做 ⇒ 压缩路径会**主动**多发 pi 不发的断点（P12），是本包自己引入的偏差；分两次提交会留下这个中间态 |
| **R4** | 通道形状 | `StreamOptions` 加 `Optional<CacheRetention>` ＋ 便捷构造器；经 `ApiOptions.extra` 的字符串键 `"cacheRetention"` 过桥 | 沿用 `ResponsesOptions` 的既有方言；4 个构造点零改签（A-07 已验证，`docs/53 §12.3`） |
| **R5** | `system` 块形态 | **无条件**改成块数组（不只在有断点时） | P18 实测：pi 在 `none` 时也是块数组 ⇒ 这是 pi 的无条件线形状，B89 据此结案 |
| **R6** | P9 白名单的兜底分支 | 照抄保留 ＋ 代码注明「在可达输入上不可观察」 | §4.6；与 H2 的两处能力门同型（`docs/44 §10`） |
| **R7** | 类型化字段的键序差异 | 接受；夹具按**键取值**断言（不逐字节） | §4.7；JSON 键序无语义，且是 java 侧既有形态 |
| **R8** | 拆分粒度 | 四步（A1a–A1d），A1b＋A1c 合并提交 | §5 |

---

## 10. 本次设计的取证方式与已知不足

**做了的：**

- pi 侧：读锚点 `3390bd936` 的 `api/anthropic-messages.ts`（1520 行）逐点核对；**跑**了 13 条自写探针（§7.1）＋ pi 自带的 4 份 cache 套件（47 绿 / 2 红 / 4 跳过，红因已定性为数据漂移）。
- Java 侧：读 `AnthropicRequestBuilder` / `AnthropicMessagesApi` / `CompatResolver` / `DefaultProviders` / `StreamOptions` / `LlmSummaryGenerator` / `ResponsesMessageConverter`；**跑**了 SDK 可行性探针（§7.2，9 项，逐字留档后删除）。
- 两侧工作树在实测期间均干净、在锚点上（`git rev-parse HEAD` = `3390bd936309…`、`git status` 空）。

**已知不足（如实登记）：**

1. **未做端到端真实 API 请求**（无 Anthropic key）。所有 oracle 都来自 `onPayload`（请求体层面），**没有**验证过「断点真的提高了 cache hit」。
2. **`none` 路径的传递链只推演到 `LlmSummaryGenerator`**：java 的压缩是否**只有**这一条摘要出口，未逐条核（`docs/31 §8.20` 记过 `tokensBefore` 有三条路径；本包只钉了 `LlmSummaryGenerator:221` 一处）。
3. **P9 白名单兜底分支未构造出反例**（§4.6）—— 这是「查不到」不是「证否」。
4. **completions / responses 的 cache oracle 未逐条实测**（它们不在本包范围）；B104/B105 落地时需各自取证。
5. **`CacheWarmer` 只做了存在性核实**（`sdk.ts:307`、`cache-warmer.ts:162`），未读其 200 余行实现 ⇒ B106 的范围估计可能偏小。

---

## 11. 实施记录

（待裁决后填写。）
