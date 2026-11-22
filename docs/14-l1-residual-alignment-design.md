# 14 - L1 余项对齐设计：strict 无类型节点 ＋ 暖请求 disableContinuation

> 【背景摘要】2026-11-22 对 L1（`pi-java-ai`，AI 层）按目标锚点 `a00a5048d` 做复验时，发现 `docs/map/02-ai.md` 的 245.5/246 读数下仍有两处代码未对位：严格 JSON Schema 的「无类型节点」判据未移植；缓存预热的暖重放未钉 `disableContinuation`。本稿给出两个单元的 pi 证据、Java 落点与验收口径，按用户 2026-11-22「按照建议实施」的裁决直接收口。

> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；目标锚点 `a00a5048d`（2026-11-20）。分层判定仍只写 `docs/map/02-ai.md`，本稿只做设计与验收。

## 1. 单元总览

| 序 | 单元 | 权重 | 当前状态 | 修复后 |
|---|---|---:|---|---|
| #101a | strict 逐节点判据：无 `type` 节点降级/抛错 | 1（与 B290 共摊） | 仅会话记忆已落，判据缺失 | 判据补齐，#101 整行对齐 |
| #6a | 暖请求 `disableContinuation: true` | 1（#6 内） | 未落，暖重放会改写线程状态 | 行为对齐；#6 仍余模块归属 0.5 |

> 读数影响：第 1 项是把已记的 0.5 分变成**实得**（`map/02` 的 245.5/246 保持不变，但 #101 不再「仅会话记忆」）；第 2 项属 #6 的同一 1 分单元，模块归属扣分不变。两处都修完后 L1 仍是 **245.5/246 ≈ 99.8%**，余下的 0.5 只代表「缓存预热策略类在 coding-agent 而非 ai」这一库作者可见差异，随 L5 迁移。

## 2. 单元 1：strict 无类型节点判据

### 2.1 pi 证据

`packages/ai/src/api/constrained-sampling.ts:73-76`（提交 `406e719a6`，`fix(ai): fall back from strict tool schemas with untyped nodes`）：

```ts
// OpenAI strict mode rejects nodes without a type; a bare enum counts as typed, a bare const does not.
if (schema.type === undefined && schema.anyOf === undefined && schema.enum === undefined) {
	throw new UnsupportedStrictJsonSchemaError("schemas without a type are unsupported");
}
```

位置固定在：通用禁用键之后、provider 专有谓词之后、`anyOf` 处理之前。`undefined` 判据按键是否缺席理解：`type: null` 仍算「有 type」，不触发。

调用方 `resolveJsonSchemaStrictSampling`（同文件 `:208-228`）的后果：

- `strict: "prefer"`：转换抛 `UnsupportedStrictJsonSchemaError` ⇒ 该工具回落普通（非 strict）工具；
- `strict: "require"`：抛错上浮为 `IllegalStateException`，文案 `Tool "<name>" requires JSON-schema constrained sampling, but schemas without a type are unsupported.`

### 2.2 Java 现状与落点

现状：`pi-java-ai/src/main/java/com/pijava/ai/api/StrictJsonSchema.java:61-81` 的 `makeNodeStrict` 只有「禁用键 → provider 谓词 → `anyOf`」三步，缺这条无类型检查。后果是 `StrictSampling.resolveStrict`（`:47-64`）在 prefer 下返回 `TRUE`、在 require 下不抛错，并把 pi 会拒绝的 schema 发到五条共用车道（`AnthropicToolWire`、`CompletionToolWire`、`ResponseToolWire`、`GoogleMessageConverter`、`MistralTools`）。

改法：在 provider 谓词块之后、`anyOf` 块之前插入同一判据：

```java
// pi :73-76：OpenAI strict 拒绝没有 type 的节点；裸 enum 算 typed，裸 const 不算。
if (!node.containsKey("type") && !node.containsKey("anyOf") && !node.containsKey("enum")) {
    throw new UnsupportedStrictJsonSchemaException("schemas without a type are unsupported");
}
```

【场景】现有夹具 `StrictJsonSchemaTest.propertiesRequireObjectType` 用「无 type ＋ properties」断言 `type object` 文案。补入新判据后该输入会先命中「无类型」错误，与 pi 顺序一致。
【决定】把该夹具的输入改为 `type: "string"` ＋ `properties`，继续钉「properties require type object」；另加无类型节点的独立夹具。
【风险/代价】无；错误顺序与 pi 相同，属[模型可见]报错文案。[内部，模型不可见]：无新增状态。

### 2.3 夹具

`StrictJsonSchemaTest` 新增：

1. 嵌套属性 `{}`（无 `type`/`anyOf`/`enum`）⇒ `schemas without a type are unsupported`；
2. 裸 `enum` 属性 ⇒ 转换成功（算 typed）；
3. 裸 `const` 属性 ⇒ 同上无类型错误。

`StrictSamplingTest` 新增：

1. `prefer` ＋ 无类型节点 ⇒ `resolveStrict` 返回 `null`（不抛）；
2. `require` ＋ 无类型节点 ⇒ `IllegalStateException`，文案逐字：`Tool "t" requires JSON-schema constrained sampling, but schemas without a type are unsupported.`

## 3. 单元 2：暖请求钉 `disableContinuation`

### 3.1 pi 证据

`packages/ai/src/cache-warming.ts:76-79`（提交 `c9552c7d8`，`feat: prompt cache warming in p2`）：

```ts
/**
 * The options of a refresh: the request's own, capped at one output token, without retries, and without replacing the
 * conversation's provider thread state (the refresh repeats the last request).
 */
export function getCacheWarmingRequestOptions<T extends SimpleStreamOptions>(options: T, signal: AbortSignal): T {
	return { ...options, maxTokens: 1, disableContinuation: true, maxRetries: 0, signal };
}
```

`SimpleStreamOptions.disableContinuation` 定义在 `packages/ai/src/types.ts:265`；Anthropic 车道的读点是 `anthropic-messages.ts:908`，值为 `true` 时整轮不发 `thread`。

### 3.2 Java 现状与落点

现状：

- `pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/CacheWarmer.java:360-364` 的 `withWarmOverrides` 只覆盖 `maxTokens: 1`；
- 暖路径 `DefaultProviders.warmStreamFnFor`（`DefaultProviders.java:305-324`）用 `requestExtra(options, sessionId)` 组 `ApiOptions.extra`，只带 `cacheRetention` 与 `sessionId`，没有 `disableContinuation`；
- `AnthropicStreamSupport.disableContinuationOf`（`pi-java-ai/.../AnthropicStreamSupport.java:76-88`）已能读 `ApiOptions.extra["disableContinuation"]`（`docs/12` D-2 通道）。

后果：暖重放会携带真实 `sessionId`，可能 `create`、`continue` 或替换会话的 Anthropic thread state。pi 用该开关明确避免这一点。

改法：在 `warmStreamFnFor` 组 extra 后、调用 `apiOptions` 前就地加键（该方法已贴近 500 行上限，不再新增独立方法）：

```java
var extra = requestExtra(options, sessionId);
extra.put("disableContinuation", true);
var resolved = apiOptions(args, model.provider(), settings,
    Credentials::resolveCredential, extra);
```

`CacheWarmer.withWarmOverrides` 的注释同步指明 `maxRetries: 0` 与 `disableContinuation` 在请求面落地，避免下一轮误读为未移植。

【场景】pi 的开关是 `SimpleStreamOptions` 的布尔字段；Java 的 `StreamOptions`（agent harness）没有该组件，`docs/12` D-2 已把同类开关定为 `ApiOptions.extra` 通道。
【决定】不为暖请求新增 `StreamOptions` 记录组件，只在 coding-agent 的暖路径 extra 里钉 `disableContinuation=true`。理由是新增 record 组件要同步全部重建点（`withWarmOverrides` 等），收益为零；extra 通道已是 thread 开关的既定落点。
【风险/代价】非 Anthropic 车道会看到一个未消费的 extra 键，不产生 wire 字段，无影响。主路径不受染，[模型可见]面（请求 wire）只在暖请求上变化，符合 pi。

### 3.3 夹具

`DefaultProvidersWarmTest` 新增 `warmPathDisablesContinuation`：

- 暖路径 `ApiOptions.extra` 含 `disableContinuation=true`；
- 同 settings、同 sessionId 的主路径不含该键（防止开关串到真实请求）。

## 4. 验收

| 命令 | 期望 |
|---|---|
| `mvn test -pl pi-java-ai -am` | 全绿（既有 2241 测 ＋ 新增 5 例） |
| `mvn test -pl pi-java-coding-agent -am -Dtest=DefaultProvidersWarmTest -DfailIfNoSpecifiedTests=false` | 全绿（含新增 1 例） |

收口时同步的文档：

- `docs/map/02-ai.md`：拆解表 #2 改为「含无类型节点判据」；#2 与 #6 的落地面说明更新；
- `docs/05` §1.2：L1 行缺口描述追加两项；
- `docs/09`：P2 余项行改为已落；
- `docs/11` §3.2：更正「逐节点判据已具备」的旧判断；
- `docs/04` §0：记账本次两个余项（不新开 B 号；如需可归入 B290/#6 的收口说明）。

## 5. 提交切分

| 序 | 内容 | 模块 |
|---|---|---|
| 1 | 本设计稿 | docs |
| 2 | `fix(ai): reject untyped nodes in strict tool schemas` | ai |
| 3 | `fix(coding-agent): disable thread continuation for cache warming` | coding-agent |
| 4 | 读数与台账回填 | docs |

不新增 D 号：暖开关沿用 `docs/12` D-2 的 extra 通道；无类型判据无窄差。
