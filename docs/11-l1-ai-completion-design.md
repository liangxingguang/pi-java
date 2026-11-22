# 11 - L1（ai）收口设计稿：从 96.5% 到 100%

> 【背景摘要】按 2026-11-22 裁决「由底往上、逐层对齐，能对齐就先对齐」，L1 成为第一个收口层。本稿把 `map/02` 预审计的 **8.5 分差额**拆成 6 个可独立实施的单元，给出 pi 证据、Java 落点、夹具与提交切分。**本稿不改任何读数**：L1 在代码落地并重测前仍是 96.5%。
>
> 【已收口（2026-11-22）】**本稿六个单元已全部落地**：B289 `49336f70`、B290 `6d393725`、B291 `c8df0315`、
> B292 `083d3552`、D6 threads 见 [`12`](12-anthropic-threads-design.md)（`177acc36`…`93b4bfe4`）。
> **当前读数以 `docs/map/02` 为准：L1 = 245.5/246＝99.8%**（余 cache 策略单元 0.5，属模块归属、随 L5 迁移）。
> **2026-11-22 复验补记**：§3.2 的判断「逐节点判据已具备」不准确，无类型节点判据与暖请求
> `disableContinuation` 已在当晚补入，见 [`14`](14-l1-residual-alignment-design.md)；读数不变。
> 下文「96.5%」「242.5/246」等数字是**收口前的规划基线**，保留只为说明拆解逻辑，不是现状。
>
> 【编号约定】新设计稿按**当前存在的最新编号 +1** 顺延（2026-11-22 起执行）。本稿占用 **11**，下一份为 **12**。`docs/06` 为历史空缺，不补；旧批次稿（原 11–71 段）已删除、只在 git 中可追溯，号可复用，不再按历史最大号跳号。
>
> 锚点：pi `a00a5048dfae964fe89dce593215d0c3d275017c`（2026-11-20）；pi-java 取实施时 HEAD。取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`。
> 口径：`docs/05` §0；分层判定只在 `map/02` 记录，本稿只做设计与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | L1 = **246/246＝100%**，即 `map/02` §预审计的 6 个缺口全部清零 |
| 中间态 | 补 #1–#4 ⇒ 242.5/246＝98.6%；再补 #6 ⇒ 243.0/246＝98.8%；再补 #5 ⇒ 100% |
| 不做项 | Codex 三个专属形状（developer item／`max_output_tokens`／`configuration_update`）按 R2 记 w0/N-A，**不阻挡 100%** |
| 完成判据 | ①`map/02` 逐行证据更新为「对齐」；②`mvn test -pl pi-java-ai -am` 与 `-pl pi-java-coding-agent -am` 全绿；③`docs/04` B289–B292 销号（实际未立项：四项只在计划／本稿里引用，`docs/04` §1 从未登记，故无销号动作，`docs/04` 口径行已改为「B289–B292 已随 L1 收口落地」）；④新增窄差登记无遗漏 |

## 2. 单元清单

| 序 | 单元 | 权重 | 扣分 | 所属包 | 规模 |
|---|---|---:|---:|---|---|
| #1 | `withCauseDetail`（#102 错误体透传） | 3 | −1.5 | B289 | S |
| #2 | strict 会话级超限记忆（#101） | 1 | −0.5 | B290 | S |
| #3 | setup 期取消映射 `aborted`（#76） | 1 | −1.0 | B291 | S |
| #4 | `refreshModelsAuth` 的 `configured` 相（#118） | 2 | −2.0 | B292 | M |
| #6 | cache 策略单元「部分」 | 1 | −0.5 | 待核 | 核查优先 |
| #5 | Anthropic message threads（#113） | 3 | −3.0 | 线程包 | L |

> 编号沿用 `map/02` 的拆解表（#5 是 threads）。实施顺序按规模由小到大：**#1 → #2 → #3 → #4 → #6 → #5**。

## 3. 逐项设计

### 3.1 D1 `withCauseDetail`（B289）

**pi 证据**：`packages/ai/src/utils/error-body.ts:57-76`

- 仅在**既无 status 又无 body** 时启用（连接级失败）：`normalizeProviderError` 第 55 行 `status === undefined && body === undefined ? withCauseDetail(error) : error.message`。
- 走 `error.cause` 链，最多 **5 层**（`MAX_CAUSE_DEPTH`），取**最深**的 `Error`。
- `detail = deepest.message || deepest.name`，`trim()`；若 `code` 是 string/number 且未被 detail 包含，则追加 `" (code)"`。
- `error.message` 已包含 detail 时不重复追加；否则拼成 `"<message>: <detail>"`（message 为空则只留 detail）。

**Java 落点**：`pi-java-ai/src/main/java/com/pijava/ai/utils/ProviderErrors.java`

- 新增 `static String withCauseDetail(String message, Throwable error)`：`getCause()` 循环 ≤5 层、取最深、`trim`、已包含即跳过、`": "` 拼接。
- **接入点只在「无 status 无 body」分支**：`format()`（`:63-99`）的 message-only 出口，以及 `fromOpenAi()`（`:121-131`）在 status/body 均为空时的返回值。
- 出口清单（逐车道，避免漂移）：`OpenAICompletionsApi:421`、`OpenAIResponsesApi:201`、`AzureOpenAIResponsesApi:134`、`OpenRouterImagesApi:87`、`OpenAIImagesApi:178`、`PiMessagesApi:247`、`MistralConversationsApi:239`。
- **`(code)` 段**：Java 异常链无统一 `code` 字段，`(ECONNREFUSED)` 这类尾巴**不虚构**；登记窄差（D 新号），只对齐「最深 cause 的 message 追加 + 去重 + 5 层上限」。若最深 cause 是 `java.net.ConnectException`，其 message 本身已是 `Connection refused`，不重复包装。

**夹具**：`ProviderErrorsTest`（新增 4 例：2 层 cause、5 层截断、已包含去重、无 cause 原样）；`ProviderErrorBodyLanesTest` 现有逐车道字面断言必须不变（有 status/body 时不走新分支）。

### 3.2 D2 strict 会话级超限记忆（B290）

**pi 证据**：`packages/ai/src/api/strict-tool-limits.ts:5-51`、`anthropic-messages.ts:890,932`

- 触发文本三条（`includes` 匹配）：`The compiled grammar is too large`、`Too many strict tools (`、`Schemas contains too many parameters with union types (`。
- 会话级 `Set<string>`，容量 **10,000**，超限丢最旧；**无 sessionId 不记账**（`undefined` 直接返回）。
- 每次请求起始 `disablePreferredStrictTools = hasExceededStrictToolLimits(sessionId)`；命中三条文本时 `mark`。
- 由 `cleanupSessionResources(sessionId)` 释放；注释明确「切回 strict 会重写工具定义、丢 prompt cache」，所以只要超限就整会话保持非 strict。

**Java 落点**：

- 新类 `pi-java-ai/src/main/java/com/pijava/ai/api/StrictToolLimitMemory.java`：`LinkedHashSet<String>` + 10k 上限、`mark(sessionId)`、`exceeded(sessionId)`、`clear(sessionId)`。
- `AnthropicMessagesApi`（`:244-276`）：把局部 `disablePreferredStrictTools` 的初值改为 `StrictToolLimitMemory.exceeded(sessionId)`，在 `:362-371` 的 fallback 判定处命中三条文本时 `mark(sessionId)`。`sessionId` 已有通道（`:206-207` 字段、`AnthropicStreamSupport:25-28` 从 `extra["sessionId"]` 读）。
- 逐节点判据当时**只具备 provider 专有谓词**（`AnthropicStrictKeywords:20-47`、`AnthropicToolWire:116-145`、`StrictSampling:47-71` 的 prefer 降级/require 抛错，docs/17 已闭环）；pi `constrained-sampling.ts:73-76` 的**无类型节点拒绝**当时未移植。本项先补会话记忆；无类型判据于 2026-11-22 复验补入 `StrictJsonSchema.makeNodeStrict`，见 [`14-l1-residual-alignment-design.md`](14-l1-residual-alignment-design.md)。
- 清理钩子：Java 无 `cleanupSessionResources` 等价物，落到 coding-agent 会话关闭点（与 `DefaultProviders` 同层）；先做「10k 上限自愈」，清理钩子作为第二步。

**夹具**：`StrictSamplingTest` 新增 3 例（三条文本各 mark 一次后下一轮直接非 strict；无关 400 不影响；`clear` 后恢复）；10k 上限用循环 10,001 验证丢最旧（对齐 pi 测试）。

### 3.3 D3 setup 期取消 → `aborted`（B291）

**pi 证据**：`packages/ai/src/api/lazy.ts:66-95`

- `setup().catch(error => { const reason = signal?.aborted ? "aborted" : "error"; ... })`：**只要 signal 已中止，setup 失败一律记 `aborted`**，不区分底层异常。
- 无 signal 或未中止时才是 `error`。

**Java 落点**：Java 没有 lazy 车道（车道每请求 eager 构造：`DefaultProviders:392`），因此**不移植 lazy**，只对齐可观察终局：

- `pi-java-ai/.../AbortedException.java` 已存在；`StreamApi:182-196` 已能产出 `StopReason.ABORTED`。
- 缺的是**构造/setup 期**的翻译：`DefaultProviders:326-407` 的 `streamFn` 无 catch，`createApi` 异常直接冒泡；`AbstractChatApi:111-121 → 156-160` 把一切失败落 `ERROR`。
- 改法：新增 `static StopReason setupFailureReason(Throwable, AbortSignal)`（`signal.aborted() || t instanceof AbortedException ? ABORTED : ERROR`），在 `DefaultProviders.streamFn` 的 catch 与 `AbstractChatApi` 的 `publisher.closeExceptionally` 处使用。
- 语义边界：**只有中止信号在场时**才映射 aborted；无 signal 的真实失败仍是 error（否则会把配置错误伪装成用户取消）。

**夹具**：新增 `SetupAbortMappingTest`（ai）：signal 先中止 → 终局 `aborted`；中止发生在 setup 中 → `aborted`；真实构造失败且未中止 → `error`。

### 3.4 D4 `refreshModelsAuth` 的 `configured` 相（B292）

**pi 证据**：`packages/ai/src/models.ts:204,640-655`、`coding-agent/src/core/provider-composer.ts:624-625`、`coding-agent/src/core/remote-catalog-provider.ts:127`

- 模型级字段 `refreshModelsAuth?: "credential" | "configured"`。
- `"configured"` 时走 `checkProviderAuth(provider, stored, signal)`：**只判「是否已配置」，不解析/刷新凭据**；不是已配置就整段跳过（返回 `undefined`）。否则走 `resolveRefreshCredential`（OAuth 过期即刷新）。
- composer 把该字段从 base 透传，除非扩展提供了自己的 `refreshModels`／`oauth.modifyModels`；远程目录 provider 显式设为 `"configured"`。

**Java 落点**：

- `RefreshModelsContext`（`:36-49`）现只有 `offline()`／`online()`，缺 `configured` 相。
- `ModelCatalogRefresh.runOne`（`:143-153`）改为三态：`CONFIGURED` → 只做认证检查（不调 `credentials.apply`）；`CREDENTIAL` → 现状；`OFFLINE` → 现状。
- **实现定稿（2026-11-22）**：不给 registry 加 `builtinIds`，改为在 `Provider` 上加 **default 方法** `refreshModelsAuth()`（返回 `CREDENTIAL`／`CONFIGURED` 枚举），由 `RemoteCatalogProvider` 覆写为 `CONFIGURED`。default 方法对扩展作者**二进制与源码兼容**，比隐藏的 registry 集合更贴合 pi 的「provider 自己声明」语义；`ModelCatalogRefresh` 的三参构造注入 `Predicate<String>` 以便测试。
- 失败语义：`configured` 相失败只记 warning、不 fail 整次刷新（对齐 B292 的「签出时只告警不失败」）。

**夹具**：`ModelCatalogRefreshTest`（coding-agent）新增：内置 provider + 过期 OAuth → **零刷新调用**（用计数型 fake credential resolver）+ warning 记账 + 其余 provider 继续；自定义 provider 行为不变。

### 3.5 D5 cache 策略单元核查（0.5 分，先核后改）

**pi 证据**：`promptCacheKeyMode` 全仓只有 4 个读点——

- `openai-completions.ts:808-812`（三态：`enabled` 直发、`auto` 按条件、`disabled` 不发）、`:1739`；
- `openai-responses.ts:96,362`（仅 `disabled` 撤键）；
- `azure-openai-responses.ts:209`（仅 `disabled` 撤键）。

**Java 对应**：`PromptCacheKeyMode:24-45`（解析）、`OpenAICompletionsMessageConverter:344-350`（三态齐）、`ResponsesMessageConverter:294-298`（仅 disabled）、`ModelCompat:324-327` 明确「本组件只被 completions 与 responses 两条车道读」。Azure responses 与 responses 共用同一 converter。

**结论规则**：

1. 若「部分」指的就是 `promptCacheKeyMode` ⇒ 覆盖面与 pi 一致，**应回补 0.5**（P12 行级重判时改判对齐，不改代码）。
2. 若「部分」指的是 `docs/09` L1 表里的「缓存预热策略从 ai 暴露」（`cache-warming.ts`）⇒ 在 ai 侧提供策略面（`CacheWarmingPolicy` 接口 + 默认实现），由 coding-agent 消费，1 个 S 级提交。

**行动**：本单元**先出核查结论再决定是否写码**；核查证据落 `map/02` 对应行，避免为 0.5 分写无用代码。

### 3.6 D6 Anthropic message threads（#113，L 级）

**pi 证据**：`anthropic-messages.ts:249,847` 与 `:280-430`（create/continue 两态、beta 头、前缀哈希、每会话上限、线程错误回退整请求、`cleanupSessionResources`）；提交 `a758f3ae4`、`64c96d00d`。默认 `api.anthropic.com` 开启，`disableContinuation` 可关。

**Java 接缝**：

- beta 头与亲和头同路：`AnthropicRequestBuilder:170-190`（`AnthropicBetaFeatures.assemble` + 显式 header 覆写门）、`:229-234`（`SessionAffinityHeaders.anthropic`）；
- body 扩展点：`:222-224`（`addAdditionalBodyProperty`，已被 `fallbacks` 使用）；
- 会话 id：`AnthropicMessagesApi:139-151,206-207,393-402`、`AnthropicStreamSupport:25-28`。

**本稿只定边界**：threads 单独立稿，见 [`12-anthropic-threads-design.md`](12-anthropic-threads-design.md)（状态机、前缀哈希、上限、回退矩阵、`disableContinuation`、真实 provider 冒烟）。**它是 L1 达 100% 的最后一环**，不与 #1–#4 同批提交。

## 4. 夹具与验收

| 层 | 夹具 |
|---|---|
| HTTP 桩 | `RecordingHttpServer`（`pi-java-ai/src/test/java/com/pijava/ai/protocol/RecordingHttpServer.java:27-115`）：录 body/headers、脚本响应；strict 两轮观测已用此基座 |
| 错误文本 | `ProviderErrorsTest`、`ProviderErrorBodyLanesTest`（逐车道字面断言） |
| strict | `StrictSamplingTest`、`AnthropicStrictFallbackTest` |
| setup 中止 | 新增 `SetupAbortMappingTest` |
| 刷新相位 | `ModelCatalogRefreshTest`（coding-agent，计数型 fake resolver） |
| faux | `FauxProvider` + `FauxStreamSynthesis`（主代码内，非测试夹） |

验收命令：每包 `mvn test -pl pi-java-ai -am`；#4 与清理钩子另跑 `mvn test -pl pi-java-coding-agent -am`；收尾一次全量 `mvn clean verify -Dfrontend.skip=true`。

## 5. 提交切分

| 提交 | 内容 | 规模 |
|---|---|---|
| S0 | L0 两项（`telemetryNow` 微秒时间戳、`renderTrace` 转义）——严格自底向上先清 | S×2 |
| S1 | D1 `withCauseDetail`（含 7 出口接线 + 窄差登记） | S |
| S2 | D2 strict 会话记忆（新类 + anthropic 接线 + 10k 上限） | S |
| S3 | D3 setup 期 aborted 映射 | S |
| S4 | D4 `configured` 相（ai + coding-agent 两侧） | M |
| S5 | D5 核查结论（可能零代码，仅文档改判） | 0–S |
| S6–S9 | D6 threads（独立设计稿 → 实现 → 冒烟） | L |

S1–S4 相互独立，可并行评审；S6 必须独立评审（改请求生命周期）。每提交 200–500 行，按 `feat/fix(ai): ...`。

## 6. 风险与不变量

1. **`ProviderErrors` 是七出口分叉区**：只挂 `fromOpenAi` 会让 Mistral/pi-messages 两条自有格式漂移；`ProviderErrorBodyLanesTest` 的字面断言是护栏，先加用例再改实现。
2. **会话态没有宿主通道**：`requestExtra` 只桥接 `cacheRetention`/`sessionId`；strict 记忆必须靠 `sessionId` 走这条唯一通道，不能新开旁路（暖请求 `warmStreamFnFor:305-324` 也要覆盖）。
3. **builtin 标记与注册语义冲突**：`loadBuiltinProviders()` 是「注册即可覆盖」；`builtinIds` 必须是**只增不改**的判定源，不能影响覆盖顺序。
4. **aborted 误判**：只在中止信号在场时映射，否则会把配置错误伪装成用户取消（会污染转录与重试逻辑）。

## 7. 台账与口径影响

- B289／B290／B291／B292 的销号条件：对应夹具绿 + `map/02` 行改判「对齐」。
- 新增窄差（D 号由 P12 分配）：Java 无异常 `code` 字段，`(ECONNREFUSED)` 这类尾巴不虚构。
- 本稿**不发布读数**（设计稿只记拆解与口径）。读数出口：`map/02` 先记（现为 L1 **245.5/246＝99.8%**）→
  `docs/05` §1.2 → `docs/09` §3「当前」列；正式换锚与总分发布归 P12。
- 若 D5 核查结论是「应回补」，需在 `map/02` 写明依据（pi 只有三条 OpenAI 车道读该键），避免下一轮又被当成缺口重做。
