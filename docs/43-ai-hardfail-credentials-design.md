# 43 - 包A0：硬故障与凭证小件（`sanitizeSurrogates` ＋ `RetryableError` 两模式 ＋ Anthropic Bearer 凭证链）

> **来源**：`docs/41 §1.3`（`sanitizeSurrogates` —— 权重 1 但**硬故障**，建议插队）· `§1.2`
> （`RetryableError` 两个新模式 · `ANTHROPIC_AUTH_TOKEN` Bearer · provider 凭证优先级链）。
> `docs/41 §7.2` 的补全次序（H1→H6→…）**经用户 2026-09-22 改判**：ai 模块整体先行 ⇒ 本包为
> ai 批次首包，代号 **A0**。
> **状态**：**已实施完毕**（用户 2026-09-22「按设计实施」⇒ §7 三条建议全部采纳，见 §8）。
> 实施记录见 §9（实测校正）与 §10（逐步台账）。
> **基准**：pi @ `3390bd936`（已核 `git rev-parse HEAD` ＋ 工作树干净）· pi-java @ `9173cd8`
> **证据**：本文件所有 `file:line` 均已实读（含两份并行取证报告）；pi 侧路径相对 `D:\workplaceForai\pi`，
> java 侧相对 `D:\workplaceForai\pi-java`。

---

## 1. 这一包解决什么

**三件事，都是「今天就会撞到」且有硬后果的**：

| # | 症状 | 后果 |
|---|---|---|
| ① | `sanitizeSurrogates` 在 pi 有 **46 处调用**，pi-java **零处** | 孤对代理字符原样出站 ⇒ **provider 400**（pi 的 JSDoc 逐字写明这一点） |
| ② | `RetryableError` 的表**恰好缺 2 个模式**（`"currently experiencing high demand"` / `"520"`） | 高需求错误与 HTTP 520 **不重试、直接失败** |
| ③ | Anthropic 车道只认 `x-api-key`，**无 Bearer 通道** | **Bearer 型网关（TeamoRouter 一类）连不上 Anthropic 车道**；且 `ANTHROPIC_OAUTH_TOKEN` 路径不存在 |

**为什么排第一**：①它是硬故障（400，不是降级）；③是用户当前环境（TeamRouter 网关）的直接阻塞；
②改动最小（表里插两行）。三件互不依赖，可一个包做完。

---

## 2. 命题表

### 2.1 pi 侧（行为）

| # | 命题 | 证据 |
|---|---|---|
| P1 | `sanitizeSurrogates` 的语义：**删掉未配对的代理**，配对 emoji **不受影响**；正则 `[\uD800-\uDBFF](?![\uDC00-\uDFFF])\|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]`（`g` 全局） | `ai/src/utils/sanitize-unicode.ts:21`（JSDoc 逐字：「cause JSON serialization errors in many API providers」） |
| P2 | 调用点 **46 处 / 9 个文件**：anthropic-messages 11 · mistral-conversations 9 · openai-completions 7 · openai-responses-shared 7 · google-shared 6 · bedrock-converse-stream 3 · google-generative-ai 1 · google-vertex 1 · openrouter-images 1 | 逐文件实读（并行取证） |
| P3 | **43 处请求路径 / 3 处响应路径**；3 处响应路径**全在 Mistral**，作用于**流式增量**（text delta `:626` · thinking delta `:648` · text 型内容项 `:667`） | 实读 |
| P4 | 被净化的字段集合：**system 提示** · user 字符串内容 · user 文本块 · assistant 文本块 · assistant **thinking** 块 · **tool result 文本** · **grammar/custom 工具的入参** | 各车道转换函数 |
| P5 | ⚠️ **普通工具参数的「重放」不净化**：`JSON.stringify(tc.arguments)` 原样出站（`openai-completions.ts:1366`、`openai-responses-shared.ts:322`） | 实读；⇒ pi 自身也有这条缝，移植须**照缝** |
| P6 | 唯一的工具参数净化在 grammar/custom 路径：`openai-completions.ts:1359`、`openai-responses-shared.ts:312`（都包 `getGrammarToolInput`） | 实读 |
| P7 | **UI/宿主侧零调用**（`packages/coding-agent/src`、`packages/agent/src` 全无）；agent 侧另有 `replaceUnpairedSurrogates`（`truncate.ts:89`）但那是截断尾部专用，与本函数无关 | 实读 |
| P8 | `RetryableError` 表（`ai/src/utils/retry.ts`）：**先查不可重试表**（quota/billing，`:7-24`），再查可重试表（`:26-92`）；判据**只有** `stopReason === "error"` ＋ `errorMessage` 文本，**不看状态码、不看响应头** | `retry.ts:237-242` |
| P9 | 可重试表逐字顺序中，`"currently experiencing high demand"` 紧跟 `"overloaded"`（`:29`）；`"520"` 位于 `"504"` 与 `"524"` 之间（`:37`） | 实读 |
| P10 | 另一条**独立**的传输层判据在 `utils/provider-retry.ts:23-35`：读 `x-should-retry` 头（`true`/`false` 短路）→ `status === undefined` ⇒ true → 408/409/429/≥500 | 实读；**pi-java 完全无此移植**（登记，不在本包） |
| P11 | Anthropic 凭证解析次序（`providers/anthropic.ts:18-39`）：**① stored `credential.key`** → ② `ANTHROPIC_AUTH_TOKEN`（⇒ `auth: {headers: {Authorization: Bearer <tok>}}`）→ ③ 依次 `ANTHROPIC_OAUTH_TOKEN`、`ANTHROPIC_API_KEY`（⇒ `auth: {apiKey}`） | 逐行实读 |
| P12 | `ANTHROPIC_AUTH_TOKEN` **参与**环境发现/状态枚举，但 `getEnvApiKey()` **故意跳过**它（注释逐字：*"getEnvApiKey() skips it because requests must pass it as Authorization: Bearer"*） | `env-api-keys.ts:73-77` / `:150` |
| P13 | 车道侧形态识别（`api/anthropic-messages.ts:906-989`）：`isOAuthToken(apiKey) = apiKey.includes("sk-ant-oat")` ⇒ 走 SDK `authToken`（Bearer）**＋ 身份头** `user-agent: claude-cli/2.1.251`（常量 `:87`）、`x-app: cli`；否则走 `apiKey`（`x-api-key`）；github-copilot 恒走 Bearer | 逐行实读 |
| P14 | 显式请求头可**覆盖** `ANTHROPIC_AUTH_TOKEN`（pi 自带用例） | `ai/test/anthropic-auth-token.test.ts:183-190` |

### 2.2 pi-java 侧（现状）

| # | 事实 | 证据 |
|---|---|---|
| J1 | **全仓零代理处理**：`surrogate\|0xD800\|0xDC00\|isSurrogate\|MIN_SURROGATE` 在 `pi-java-*/src/main` 只有 2 处命中，且都是 emoji 转义字面量（`tui/screen/ChatScreen.java:122,126`），非逻辑 | 全量 grep |
| J2 | **无中央出站序列化收口**：四条车道（Anthropic / OpenAI-completions / OpenAI-responses / Google）把序列化交给厂商 SDK；只有 Mistral（`protocol/MistralConversationsApi.java:58` `MAPPER` ⇒ `:282 writeValueAsString`）与 pi-messages（`protocol/PiMessagesApi.java:37` ⇒ `:193`）自带 `ObjectMapper` | 实读 |
| J3 | 出站写入点（每车道）：Anthropic `buildParams:382`（system `:401` · text `:497` · tool-result text `:571` · tool 入参 `:486` via `toJsonValues:578`）· OpenAI-completions `buildParams:409`（system `:418` · user `:428` · assistant `:533` · reasoning `:544` · tool result `:438` · 入参 `toArgumentsJson:589`）· OpenAI-responses `ResponsesMessageConverter:54`（system `:109` · user `:152` · assistant `:215` · tool result `:122` · 入参 `:237`）· Google `buildConfig:287`＋`toGoogleContents/toGoogleParts`（system `:294` · text `:336` · tool 入参 `:342` · tool result `:352`）· Mistral `toMistralMessages`（system `:293` · user `:305` · assistant `:309` · tool result `:316`）· pi-messages `:163-231` | 并行取证逐条实读 |
| J4 | `RetryableError.java` 与 pi **同形**（`buildProviderErrorPattern:128` 用 `Pattern.CASE_INSENSITIVE` ＋ `Matcher.find()`，判据 `:140-150`），非重试表 `:37-55` 与 pi 逐条相同，可重试表 `:61-126` **恰缺 2 条**（P9 两条） | 实读比对 |
| J5 | `http/RetryPolicy.java` 五个预设（`anthropic():40` / `openai():49` / `google():58` / `mistral():68` / `deepseek():77`）**零调用者**；`PiHttpClient` 走 `defaultPolicy()`（`{408,409,429}` ∪ 任意 5xx）；`Retry-After` 只在 429/503 读 | 全仓 grep ＋ 实读 |
| J6 | **无 `x-should-retry`**、无 `provider-retry` 移植（P10 的对照物缺席） | 全仓 grep（仅 docs 命中） |
| J7 | Anthropic 车道：`AnthropicMessagesApi:80` `resolveApiKey(options, apiKeyEnvVar)` ⇒ `:81` `AnthropicOkHttpClient.builder().apiKey(apiKey)`；次构造器默认 env 名 `"ANTHROPIC_API_KEY"`（`:70`） | 实读 |
| J8 | `AbstractChatApi.resolveApiKey:250`：`options.apiKey` → 该 env → （env 名为空 ⇒ 占位 `"local"`）→ 否则 `IllegalStateException`。**无「凭证种类」概念**，一切皆 `apiKey` 字符串 | 实读 |
| J9 | `EnvApiKeyResolver` 的 provider→env 名来自 `ProviderConfig.apiKeyEnvVar()`；`provider/AnthropicProvider.java:12-16` 声明 `"ANTHROPIC_API_KEY"`。⇒ `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_OAUTH_TOKEN` **无任何读取点** | 实读＋grep |
| J10 | `auth/Credentials.java:23-41` 解析次序：**① 激活 profile 的 env（`<PROVIDER>_API_KEY_<PROFILE>`）→ ② 激活 profile 的文件凭证（`provider::profile`）→ ③ 默认 env → ④ 默认文件凭证**（P6-18，**java 扩展**，pi 无 profile 维度） | 实读 |
| J11 | 生产装配：`coding-agent/core/DefaultProviders.java:145-149` `apiOptions(...)` 次序 = `args.apiKey` → `settings.defaultApiKey` → `Credentials.resolveApiKey`，装进 `ApiOptions(baseUrl, apiKey, 120s, 2, Map.of())` | 实读 |
| J12 | SDK **支持** Bearer 与自定义头：`anthropic-java-client-okhttp 2.52.0` 的 `AnthropicOkHttpClient$Builder` 有 `authToken(String)`／`apiKey(String)`／`putHeader(String,String)`（`javap` 实测；`anthropic-java` 本体是 407 B 空壳，实现类在 `-core`／`-client-okhttp`） | javap |
| J13 | `AuthCommand` 已有 Bearer **概念**但只在 CLI：`subcommand/AuthCommand.java:104-126` `printBearerToken` / `bearer(apiKey) = "Bearer " + apiKey`（P6-15，java 扩展）——**请求路径上没有对应物** | 实读 |
| J14 | 夹具现成：`ai/src/test/.../AnthropicMessagesApiTest.java:184` 已用 JDK `HttpServer`(0) ＋ baseUrl 覆盖跑 SDK；`LaneTransformMessagesWiringTest:192`、`OpenAICompletionsReasoningReplayTest:282` 已做**请求体捕获** | 实读 |

---

## 3. 设计决策

### D1 —— 净化落点：**逐车道字段级照抄 pi 的调用点，不做中央收口**（已定）
理由三条：① 四条车道里**三条**的序列化在厂商 SDK 内部（J2），java 侧**不存在**可收口的单点；
② 中央「凡出站字符串都净化」会**多**净化 pi 不净化的东西（P5：普通工具参数重放），属行为偏离；
③ 本项目的判据是「分支所有功能和 pi 表现一样」——**照点移植**是唯一能逐条对账的口径。
**登记**：P5 那条缝（普通工具参数重放不净化）**照缝移植**，并登记为 pi 自身缺口。

### D2 —— 工具参数边界：**只净化 grammar/custom 工具入参**（已定）
照 P6。⚠️ java 侧 **grammar/custom 工具面本身缺失**（`docs/41 §1.3`：constrained sampling / grammar 权重 1）
⇒ 本包**只落「普通工具参数不净化」这一半**（即什么都不做），grammar 那一半随 grammar 包补。

### D3 —— 净化实现：**手写 code-unit 循环**（推荐）＋ **Java 正则作测试 oracle**（已定）
- 实现：遍历 UTF-16 code unit，高代理(`0xD800-0xDBFF`)后紧跟低代理(`0xDC00-0xDFFF`)⇒ 两者保留并跳 2；
  否则**删除**；遇低代理且未被前一个高代理消费 ⇒ 删除。（`String.codePointAt` 不适用：孤对代理
  没有码点，必须按 code unit 走。）
- oracle：把 pi 的正则**原样**写进测试（Java 支持定长 lookbehind `(?<![\uD800-\uDBFF])`），
  与被测实现做**双向差分**（含随机串种子固定 ＋ 边界集：孤高 · 孤低 · 高低高 · 低高低 · 配对 emoji ·
  纯 BMP · 空串 · 连续孤低）。
- 为什么不用正则做实现：可读性与「删除」语义显式；但**等价性必须有证明**（oracle 差分就是证明）。
- **待实证**：Java 的 `String` 与 `JSON` 序列化对孤对代理的默认行为（Jackson 可能替换成 `?`，
  厂商 SDK 亦然）——设计**不依赖**该行为，只依赖「我们出站的字符串里没有孤对代理」。

### D4 —— 净化调用点清单（42 处，逐车道提交）
| 车道 | pi 调用点数 | 本包落点（J3 的写入点） | 备注 |
|---|---:|---|---|
| Anthropic | 11 | system `:401` · user 串/文本块 · assistant 文本 · **thinking `:571` 区** · tool-result 文本 `:571` | 含 cache_control 两分支的 system 文本 |
| Mistral | 9 | `:293`(system) `:305`(user) `:309`(assistant) `:316`(tool result) **＋ 响应路径 3 处**（流式 delta） | **唯一响应路径车道** |
| OpenAI-completions | 7 | `:418` `:428` `:533` `:544` `:438` | 入参那一处按 D2 不做 |
| OpenAI-responses | 7 | `:109` `:152` `:215` `:122` | 同上 |
| Google | 7 | `:294` `:336` `:352` | 同上 |
| openrouter-images | 1 | `OpenRouterImagesApi:41` 区的 prompt 文本项 | 图片车道仍在 |
| **合计** | **42** | | pi 46 − bedrock 3 − vertex 1（两条车道 java 未移植） |

**pi-messages 车道**（java 独有，非 pi 车道）⇒ **不动**（保持现状，避免发明 pi 没有的行为）。

### D5 —— 凭证载体：**`ApiOptions` 新增种类组件**（推荐，待裁决 ①）
现状是「一切皆 `apiKey` 字符串」（J8），而 pi 的凭证是**三种形态**（P11/P13：Bearer 头 · OAuth(带身份头) ·
x-api-key）。两个选项：

| 选项 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | `ApiOptions` 加一组件 `AuthKind`（`API_KEY`(默认) / `BEARER` / `OAUTH`）＋ `auth/Credentials` 返回 `RecordedCredential(kind, value, source)`；`AbstractChatApi` 按 kind 分派 | 波及 `ApiOptions` 构造点（约 6 处：`defaults()` · `DefaultProviders` · `ModelsJsonProvider`×2 · `OpenAiCompatibleProvider` · evals 两处）——机械改动，类型安全 |
| B | 复用 `ApiOptions.extra` 放 `auth.kind` | 零波及，但隐式、无编译期保护；`extra` 已承载 provider 选项（`thinking.budgetTokens`），语义混装 |

**建议 A**；`ApiOptions` 是 5 组件 record（`api/ApiOptions.java:16-22`），加一个带默认值的组件不影响语义。

### D6 —— 凭证链次序（pi 逐条移植 ＋ java 扩展分层）（已定）
最终次序（**从高到低**）：
1. `args.apiKey` / `settings.defaultApiKey`（J11，**java 扩展**：pi 无 CLI/settings 直给 key 这一层）
2. stored 文件凭证（`~/.pi-java/auth.json`：profile 键优先 `provider::profile`，再 `provider`）（J10/J14）
   —— 对应 pi 的「① stored `credential.key`」，java 的 profile 维度**保留**（已登记为扩展）
3. `ANTHROPIC_AUTH_TOKEN` ⇒ **Bearer**（P11 ②）
4. `ANTHROPIC_OAUTH_TOKEN` ⇒ OAuth 形态（P11 ③ 前半，配合 D7）
5. `ANTHROPIC_API_KEY` ⇒ x-api-key（P11 ③ 后半）
**边界**：`ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_OAUTH_TOKEN` **仅 Anthropic**（pi 只定义了这两个符号，
其余 provider 一律 `<PROVIDER>_API_KEY`，见 `env-api-keys.ts` 的 `envMap`）。
**profile 层与 AUTH_TOKEN 的关系**：java 的 profile 层（`<PROVIDER>_API_KEY_<PROFILE>`）**不扩展**出
`..._AUTH_TOKEN_<PROFILE>`——pi 无此物，凭空发明会成为无法对账的行为（**登记为有意不加**）。
**等价物对齐**：pi 的 `getEnvApiKey()` 跳过 AUTH_TOKEN（P12）⇒ java 侧对应的是「`EnvApiKeyResolver`
（只认 `<PROVIDER>_API_KEY`）不认 AUTH_TOKEN，Bearer 只在 `Credentials` 的链上出现」，同构。

### D7 —— OAuth 形态（`sk-ant-oat`）（推荐纳入，待裁决 ②）
照 P13 移植：值含 `sk-ant-oat` ⇒ SDK `authToken` ＋ `putHeader("user-agent", "claude-cli/2.1.251")`
＋ `putHeader("x-app", "cli")`。
- `2.1.251` 是 pi 的**硬编码常量**（`anthropic-messages.ts:87`）⇒ java 侧照抄同值并注明来源，不读运行时版本。
- java 已有的 OAuth 流（`auth/OAuthProviders.java:24` anthropic PKCE ＋ `OAuthCredentialStore`）**目前只在
  `AuthCommand` 里用**（J13 邻域）⇒ 本包**只做「形态识别 ＋ 头形状」**，把 OAuth 流**接进请求路径**列为
  后续步骤（若 java 的 anthropic OAuth 端点/作用域与 pi 的不一致，接线本身要单独立项）。
- **若你裁决不纳入**：D7 整体移到登记，本包只做 ③ 的 Bearer。

### D8 —— `RetryableError` 两模式：**按 pi 的逐字顺序插入**（已定）
`"currently experiencing high demand"` 插在 `"overloaded"` 之后；`"520"` 插在 `"504"` 与 `"524"` 之间。
⚠️ 两处都是**子串匹配**（`find()`，同 pi 的 regex）⇒ `"520"` 会命中任意含 `520` 的文本；**这是 pi 的
既有语义，照抄不收紧**（收紧会成为行为偏离）。

### D9 —— `RetryPolicy` 五个预设零调用者（登记，不在本包修）
J5 的发现进 `docs/41 §1.5` 死功能表（本包只登记，不动代码——是否接线属 `x-should-retry`／传输重试那包的范围）。

---

## 4. 实施步骤（每步：先红 → 实现 → 探针 → 回归）

| 步 | 内容 | 先红夹具 | 探针（改实现 ⇒ 恰该条红） |
|---|---|---|---|
| 1 | `SanitizeUnicode.surrogates(String)` ＋ pi 正则 oracle 双向差分 | 空实现下差分用例红 | 把「跳 2」改成「跳 1」⇒ 配对 emoji 用例红 |
| 2 | Anthropic 车道 11 处接线（system/user/assistant/thinking/tool-result） | 请求体捕获：含孤高代理的 system/user/tool-result ⇒ 出站体含 `\uD83D` 裸代理 | 注掉任一接线点 ⇒ 对应用例红 |
| 3 | OpenAI-completions（7）＋ responses（7）车道接线 | 同上（两车道各一例） | 同上 |
| 4 | Google（7）＋ openrouter-images（1）＋ Mistral 请求面（6）接线 | 同上 | 同上 |
| 5 | Mistral **响应路径** 3 处（流式 delta） | 剧本里让 provider 返回含孤对代理的 delta ⇒ 事件里的文本已被净化 | 注掉一处 ⇒ 该 delta 用例红 |
| 6 | `RetryableError` 两模式 | 「`currently experiencing high demand`」/「HTTP 520」两条 ⇒ 修复前 `isRetryableAssistantError == false`（**恰 2 红**）；反例 `billing` 仍 false | 删任一模式 ⇒ 对应用例红 |
| 7 | 凭证：`AuthKind` 载体（D5-A）＋ `Credentials` 返回带 kind ＋ Anthropic 车道按 kind 分派 | 四组合矩阵：仅 AUTH_TOKEN ⇒ `Authorization: Bearer`（**且无 `x-api-key`**）· 仅 OAUTH_TOKEN ⇒ Bearer ＋ 身份头 · 仅 API_KEY ⇒ `x-api-key` · AUTH_TOKEN 优先于其余两条（**恰 4 红**） | 调换链上任两条次序 ⇒ 优先性用例红 |
| 8 | （裁决 ② 纳入时）OAuth 身份头两枚 ＋ `sk-ant-oat` 识别 | 头形状：`user-agent: claude-cli/2.1.251`、`x-app: cli` | 去掉 `x-app` ⇒ 该用例红 |
| 9 | 回归 ＋ 台账回填 ＋ 收尾 | — | — |

**命令纪律**：`JAVA_HOME=D:/soft/jdk/graalvm-jdk-25` ＋ `D:/soft/apache-maven-3.9.9/bin/mvn` ＋ **`-am`**；
AI 模块既有 surefire 坑：`-pl pi-java-ai -am -Dsurefire.failIfNoSpecifiedTests=false`。

---

## 5. 验收

- **单元/车道级**：J14 的 `HttpServer` 夹具 ＋ 请求体/请求头捕获；四组合凭证矩阵；差分 oracle 双向；
  流式 delta 净化（步 5）。
- **不进 L5**：本包三件事**都不出现在流式帧**上（净化是**请求侧**、重试是**宿主层判据**、凭证是**请求头**）
  ⇒ L5 剧本**不动**（S1–S15 保持 15/15 全绿即为「无回归」的证据）。
- **回归**：`pi-java-ai` 全绿（现 346 用例基线）＋ `agent-core`（受影响的是 `RetryableError` 的调用方
  `PostRunRetry`/`PostRunCompactionCheck`/`LlmSummaryGenerator`，现 489 基线）＋ `telemetry` 31。
- **零 `System.out` 残留**；checkstyle 0 告警。

---

## 6. 遗留登记（本包产出）

1. **pi 自身缺口**：普通工具参数重放不净化（P5）——照缝移植，登记。
2. **grammar/custom 工具入参净化**：随 grammar 包（D2）。
3. **`RetryPolicy` 五预设零调用者**（J5/D9）→ `docs/41 §1.5`。
4. **`x-should-retry` ／ `provider-retry` 传输层判据未移植**（P10/J6）→ 原计划在 A6 裁决面，本包只登记。
5. **`ANTHROPIC_AUTH_TOKEN` 的 profile 变体有意不加**（D6）。
6. **java 的 anthropic OAuth 流未接进请求路径**（D7）——若裁决 ② 不纳入，此处为登记项。
7. **`ANTHROPIC_AUTH_TOKEN` 之外的 Bearer 型网关**（如用户环境里给 `MISTRAL_*` 也配 Bearer）——
   pi 无此物，不做；登记备查。

**实施中新发现（§9 详述，一律「照缝移植 ＋ 登记」）**：

8. **pi 同族的三条缝**（与 P5 同根：这些路径 pi 自己也不净化，java 同样不净化，各配 guard 夹具）：
   - `openai-completions.ts:1339` 签名驱动的 reasoning 字段（`assistantMsg[signature] = ….join("\n")`）；
   - `openai-responses-shared.ts:322` `function_call.arguments = JSON.stringify(...)`；
   - `google-shared.ts:274` `functionCall.args = block.arguments ?? {}`。
9. **四个落点「面不存在」**（不是漏净化，是整个形态没解析）：Google 的 thinking 两分支
   （java 一律丢 thinking，`GoogleGenerativeAiApi:337`）· Mistral 的数组形态 content 两处
   （`delta.content` 只按 String 读）。后者更严重一层：**数组形态会让车道抛 ClassCastException**，
   属本车道的形态缺口，另立项。
10. **pi 的 OAuth 分支还有两个面未移植**：系统提示前置 `"You are Claude Code, Anthropic's
    official CLI for Claude."`（`anthropic-messages.ts:1078-1090`）与工具名 `toClaudeCodeName`；
    java 的 anthropic OAuth 流（`OAuthProviders`）目前只在 `AuthCommand` 里用 ⇒ 接线另立项。
11. **pi 的两枚浏览器头未移植**：`accept` ／ `anthropic-dangerous-direct-browser-access`
    （pi 恒传 `dangerouslyAllowBrowser: true`）——java 无浏览器场景，移植反而是噪音。
12. **三处重复的「逐块 vs 拼接」口径**：assistant 文本（completions/responses/Mistral）
    与 tool result（Mistral）在 pi 是**先净化后拼**，java 已照此落（步3/步4 的跨块边界夹具钉住）；
    而 tool result（completions/responses/Google）在 pi 是**先拼后净化**，java 亦照此。
    ⚠️ 两者在「块1 尾孤高 ＋ 块2 首孤低」这一例上答案不同（前者 `"AB"`、后者 `"A🙈B"`）
    —— **照点移植意味着这个差别必须保留**，别「统一」。

---

## 7. 待裁决（两点，请审核时给结论）

| # | 问题 | 我的建议 |
|---|---|---|
| ① | 凭证种类载体：`ApiOptions` 新组件（D5-A）还是 `extra` 键（D5-B） | **A**（显式、类型安全；波及 6 处构造点，机械改动） |
| ② | OAuth 形态（`sk-ant-oat` ＋ 两枚身份头）是否纳入本包（D7） | **纳入**（改动小；缺它则 `ANTHROPIC_OAUTH_TOKEN` 拿到手也不会走 Bearer 形态） |
| ③ | 净化范围是否**逐点照抄**（含 P5 那条缝） | **照抄**（否则无法逐条对账） |

---

## 8. 裁决与执行

**用户 2026-09-22「按设计实施」** ⇒ §7 三条建议**全部采纳**：① 载体 A（`ApiOptions` 新组件）·
② OAuth 形态纳入 · ③ 净化逐点照抄。步 1–9 全部落地，提交清单：

| 步 | 提交 | 内容 |
|---|---|---|
| — | `a5051ca` | 本设计文档 |
| 1 | `34ca389` | `SanitizeUnicode`（code-unit 循环）＋ pi 正则 oracle 双向差分 |
| 2 | `e5c5e41` | Anthropic 车道 6 落线点（pi 11 处） |
| 3 | `acb32a2` | OpenAI-completions 4 点 ＋ responses 5 点（pi 各 7 处） |
| 4 | `fa98d5e` | Google 3 点 ＋ openrouter-images 1 点 ＋ Mistral 请求 4 点 |
| 5 | `14c45f0` | Mistral 响应路径（流式增量）1 点 |
| 6 | `d9e7099` | `RetryableError` 两个模式 |
| 7 | `4492d3e` | 凭证载体 `AuthKind` ＋ Anthropic 按 kind 分派（D5-A/D6） |
| 8 | `5f3833d` | OAuth 身份头 ＋ `sk-ant-oat` 值识别（D7） |
| 9 | 本提交 | 回归 ＋ 台账回填（§9/§10 ＋ `docs/41`/`docs/32` 回填） |

---

## 9. 实施中的实测校正（相对本设计正文）

1. **D4 的落点表按逐行实读校正**（设计写的是「估计值」）。pi 在范围内的 42 处调用 →
   java **24 个落线点**，差额由三类构成：
   | 车道 | pi | java | 差额来源 |
   |---|---:|---:|---|
   | Anthropic | 11 | 6 | thinking 3→1（值域同一）· user 3→1（两角色共用）· system 2→1（两分支）· tool result 2→1（串/块两形） |
   | completions | 7 | 4 | user 2→1 · ＋grammar 1（D2 不落）＋ `:1339` 1（照缝，§6-8） |
   | responses | 7 | 5 | tool result 2→1 · ＋grammar 1 ＋ `:322` 1（照缝） |
   | Google | 7 | 3 | text 3→1 · ＋thinking 2（面不存在，§6-9） |
   | openrouter-images | 1 | 1 | — |
   | Mistral | 9 | 5 | 请求面 user 2→1 · ＋响应面 2（面不存在）|
2. **D3「待实证」定案 —— 序列化器对孤高代理有三种口径**（都在线格上实测）：
   - **UTF-8 字节生成器**（`anthropic-java` ／ `openai-java` 的 SDK）⇒ 写成 JSON **转义**
     `\uD83D`（6 个 ASCII 字符）：孤对代理**能成功出站**、被 provider 判非法 ⇒ 正是 pi JSDoc
     说的 400 症状。
   - **`com.google.genai`** ⇒ 替换成 **`?`**（既不转义也不抛错）⇒ 该车道上表现为**静默错字**。
   - **Jackson 的 char 型生成器**（Mistral 的 `writeValueAsString`、`toArgumentsJson`）⇒ 孤高代理
     **原样留在 Java 串里**（断言可以直接做 code-unit 探测）。
   ⇒ 「我们出站的字符串里没有孤对代理」仍是唯一有效口径（设计 D3 的判断成立）。
3. **D6 的展开**：两个 token 层插在「**默认 env 的 `ANTHROPIC_API_KEY` 层之前**」——
   设计正文只列了相对次序，实施时明确到「java 既有的 profile 两层 → token 两层 → 默认 env
   → 默认 file」。理由：pi 的 `ANTHROPIC_API_KEY` 在 `ANTHROPIC_AUTH_TOKEN`/`_OAUTH_TOKEN`
   之后，若把 token 层放最后，「两边都配」时会挑到 key，与 pi 相反（探针②实测钉住）。
4. **新增测试缝**：`EnvApiKeyResolver` 加了注入式环境读取口（生产仍是 `System::getenv`）——
   凭证链的次序断言必须在可控环境里跑；`envVarName` 只服务于 `source` 文案。
5. **步7/步8 分两个提交**（同一分派点）：第一次只做载体 ＋ Bearer 分派，第二次加身份头 ＋
   值识别 —— 这样「裁决②不纳入」的退路在提交史上可见。
6. **实测口径两处写错又改对**（记为新形态：**断言写在实现之前，但断言依据是「我以为的 SDK 行为」**）：
   - ① `openai-java` 的 `JsonValue.convert(String.class)` **不做 JSON 往返** ⇒ 值就是原 Java 串，
      `\uD83D` 转义断言注定错，改成「与脏串逐字相等 ＋ code-unit 探测」。
   - ② `anthropic-java` **自带**一枚 `user-agent: AnthropicClientImpl/Java 2.52.0` ⇒
     「无身份头」的判据是「**不是** claude-cli 那枚」，不是「头不存在」。
   ⇒ 教训：凡断言「某 SDK 的某个默认行为」前，先跑一次把实测值打出来。

---

## 10. 实施记录（逐步：先红 → 实现 → 探针 → 回归）

| 步 | 先红（实测） | 探针（实测红集） | 回归 |
|---|---|---|---|
| 1 | 3 用例 **2 红**（差分两条；配对保留那条在桩下平凡绿） | 「跳 2 改跳 1」⇒ **3/3 红**，含「配对 emoji 与 oracle 一致」「干净串不动」 | ai **516 → 519** |
| 2 | 9 用例 **8 红**（唯一绿＝配对门） | 逐点：system ⇒ 2 红 · 文本块 ⇒ 3 红 · thinking ⇒ 3 红 · tool result ⇒ 2 红（每次含线格那条） | ai 528 |
| 3 | completions 8 用例 **6 红** · responses 9 用例 **7 红** | 按机制四条：两条 system ⇒ 各 2 红 · user（1＋2 点）⇒ 2/3 红 · tool result ⇒ 各 2 红 · assistant 逐块 ⇒ 各 3 红（他车道零红） | ai 545 |
| 4 | Google 7 用例 **4 红** · Mistral 6 用例 **5 红** · OpenRouter 2 用例 **1 红** | 每车道一轮全落点注入：Google ⇒ 4 红 · Mistral ⇒ 5 红 · OpenRouter ⇒ 1 红（**跨车道零红**） | ai 560 |
| 5 | 2 用例 **1 红** | 注掉净化 ⇒ 恰 1 红、门仍绿 | ai 562 |
| 6 | `RetryableErrorTest` 57 用例 **2 红**，恰为新加的第 [2]、[10] 条 | 删 `"520"` ⇒ 恰 [10] 红 · 删高需求 ⇒ 恰 [2] 红 | ai 562 → 564 · agent-core 489 · L5 15/15 |
| 7 | 两批 12 用例：编译失败（API 尚不存在）⇒ 落实现后 8＋4 全绿 | ① 分派塌成 apiKey ⇒ 恰 2 红{Bearer, OAuth} · ② token 两层挪到 API_KEY 之后 ⇒ 恰 2 红{两条优先性} · ③ token 层不限 anthropic ⇒ 恰 1 红 | ai 576 · coding-agent 266 |
| 8 | 3 用例 **2 红**（Bearer 那条是回归门） | 去掉 `x-app` ⇒ 恰 2 红、门仍绿 | ai 579 |
| 9 | — | — | **telemetry 31 · ai 579 · agent-core 489 · L5 15/15 · 零 `System.out` · checkstyle 0 违规** |

**基线对照**：设计 §5 写的是「ai 346 ／ agent-core 489 ／ telemetry 31」—— 346 是 H1 **之前**的读数，
实施时的真实起点是 **516**（H1 已把它推到 516），收尾 **579**（**+63**＝净化 6 个测试类 33 条
＋ 凭证 12 条 ＋ `RetryableErrorTest` 参数化 +2 ＋ 步5 的 2 条 ＋ 步1 的 3 条，逐步合计与逐次
实测的 519/528/545/560/562/564/576/579 完全一致）。agent-core 489 与 telemetry 31 与设计一致。