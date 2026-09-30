# 63 — B1：Anthropic OAuth credential → request path

**状态：已裁决并闭环（2026-10-01，`4a869c9`）—— R1/R2/R3 全按建议实施**
**创建基准：** pi-java `1bce529`（B103 收尾）

> 依赖顺序（`docs/48` 依赖图 `B → D`）：本包先于 D3。
> 验收目标＝Anthropic；接线做成**通用 stored-OAuth 通道**，其他 OAuth provider 自动受益。

---

## 1. pi 事实清单（file:line 已核）

### 1.1 provider 同时挂 apiKey 与 oauth 两条 auth

- `packages/ai/src/providers/anthropic.ts:43-59`：`auth.apiKey = anthropicApiKeyAuth()`、
  `auth.oauth = lazyOAuth({ isSubscription:true, load: loadAnthropicOAuth })`。
- oauth 是懒加载（`auth/helpers.ts:40-59` lazyOAuth）：暴露 `login / refresh / toAuth`，
  实现首次调用才动态 import（Node-only PKCE 不进 bundle）。

### 1.2 ★ 凭证裁决优先级（统一在 auth resolve）

`packages/ai/src/auth/resolve.ts:63-110` `resolveProviderAuthWithSignal`，顺序：

1. **`overrides.apiKey`**（CLI/settings 直给）最高 ⇒ 直接走 apiKey resolve；
2. **stored credential**（读统一 credential store，`:87`）：
   - `type==="oauth"` 且 provider 有 oauth ⇒ `resolveStoredOAuth`（`:89-98`）；
   - `type==="api_key"` ⇒ 走 apiKey resolve（`:99-102`）；
3. **ambient**（env / profile，`:106-109`）兜底。

即 **stored 优先于 ambient env**；「stored credential 拥有 provider」。

### 1.3 ★ stored OAuth：过期检查 → 全局单次刷新 → toAuth

`resolve.ts:127-179` `resolveStoredOAuth`：

- **5 分钟**剩余即视为临期（`DEFAULT_OAUTH_MINIMUM_VALIDITY_MS`，`:119`）；
- double-checked locking：在 credential store 的 `modify` 锁内复核过期，**全局只刷新一次**
  （别的进程/请求可能已刷），刷新超时 15s（`:120/:149-152`）；
- 刷新后的 rotated credential 已由 `modify` 持久化；
- 最后 `oauth.toAuth(credential)` 得到请求 auth（`:174-175`）。

### 1.4 ★ Anthropic 订阅 token 的 toAuth 形态：当 apiKey，不是 Bearer

`packages/ai/src/auth/oauth/anthropic.ts:361-363`
```ts
async toAuth(credential) { return { apiKey: credential.access } }
```

⇒ Claude Pro/Max 的 OAuth access token 经 **`x-api-key`** 槽发送（订阅 token 走 apiKey），
**不是** `Authorization: Bearer`。

（对照：ambient `ANTHROPIC_AUTH_TOKEN` 才是 `Authorization: Bearer`，
`providers/anthropic.ts:24-31`；两槽语义不同。）

### 1.5 请求如何消费 auth

`coding-agent/core/model-runtime.ts:574-609` prepareRequest：resolution.auth 注入
`apiKey: providerOptions.apiKey ?? resolution.auth.apiKey`、headers、env；store:false 无状态。

---

## 2. pi-java 现状差

| # | 点 | 现状 |
|---|---|---|
| 2.1 | **stored OAuth 未接进请求解析（核心断链）** | `auth/Credentials.resolveCredential`（`:65-93`）只读：active profile（env/file）→ token **env**（`ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_OAUTH_TOKEN`）→ default env → `FileCredentialStore`。**从不读 `OAuthCredentialStore`**。 |
| 2.2 | OAuthCredentialStore 仅 AuthCommand 用 | grep：`OAuthCredentialStore` 只被 `AuthCommand`（登录/状态/刷新）与其自身测试引用，请求路径（DefaultProviders→Credentials）不可见。 |
| 2.3 | **刷新逻辑已存在、可复用** | `AuthCommand.resolve:126-144` 已有「读 oauth store→`isExpired()` 则 `refresh()`（复用 `OAuthFlow`/`DeviceCodeFlow.refresh`）→ 回存→取 accessToken」，但只服务 auth 命令、不在请求路径，且形态（toAuth）未在请求侧表达。 |
| 2.4 | OAuth 端点/PKCE 配置齐全 | `OAuthProviders`（anthropic PKCE 端点＋client-id＋scopes）、`OAuthFlow`（login/refresh）均在。 |
| 2.5 | 缺「5 分钟临期 + 锁内单次刷新」接入 | `OAuthCredential.isExpired()` 是硬过期判断；pi 请求路径在**过期前 5 分钟**即主动刷新，且经 store 锁全局一次。java 请求路径无此环节（AuthCommand 也只在硬过期后刷）。 |
| 2.6 | 凭证顺序结构差异 | pi：stored 优先于 ambient env。java 当前：env（含 token env）在 FileCredentialStore **之前**。stored OAuth 接入放哪一位置，涉及既有顺序（见 §4 R1）。 |

**结论**：B1 不是新造 OAuth，而是**把已能登录/存储/刷新的 OAuth 凭证接通到请求路径**，
并按 pi 形态（Anthropic 订阅 access token 走 apiKey 槽）与 5 分钟主动刷新对齐。

---

## 3. 改动方案

### Step 1 — 抽出可复用的「stored OAuth → 有效 access token」解析

- 把 `AuthCommand.resolve` 里读 store＋过期刷新的逻辑**下沉为 auth 层一个共享组件**
  （如 `OAuthCredentials.resolveEffective(provider)`），返回有效 access token（含刷新回存）。
- AuthCommand 改为复用同一组件（消除两份逻辑）。

### Step 2 — 接入 Credentials 凭证裁决（通用通道）

- 在 `Credentials.resolveCredential` 增加 **stored OAuth** 凭证源，产出
  `RecordedCredential(kind, accessToken, "stored-oauth")`。
- **Anthropic 订阅 token 的形态**：按 pi `toAuth ⇒ {apiKey}`，kind 记为 **`API_KEY`**
  （走 x-api-key 槽），不得记 BEARER。
  - 判定依据：provider＝anthropic 且凭证来自订阅 OAuth store。
  - 其他 provider 的 toAuth 形态若不同，后续按各自包对齐；本包先把 Anthropic 判对。

### Step 3 — 5 分钟临期主动刷新接入请求路径

- 复用处按 pi 口径：`now + 5min >= expiresAt` 即刷新（不只是硬过期）。
- 刷新经 `OAuthCredentialStore` 文件锁/`modify` 语义做全局单次（跨并发请求只刷一次）；
  刷新失败按 pi：**不静默回落 env**（stored 拥有 provider），抛出/按错误结果处理。
  - ⚠️ java OAuthCredentialStore 目前无 `modify` 原子方法 ⇒ 以其已有的文件锁（`writeAll` 用 FileLock）
    实现「锁内复核过期再刷」，或新增最小的条件更新方法（见 §4 R2）。

### Step 4 — DefaultProviders 请求路径贯通验证

- `DefaultProviders.apiOptions` 经 `Credentials.resolveCredential` 即可见 stored OAuth，
  kind/value 装入 ApiOptions；Anthropic 车道据此走 x-api-key。
- 核对：CLI `--api-key`/settings 直给仍**压过** stored OAuth（pi overrides 最高）。

**不做**：非 Anthropic provider 的 toAuth 细分（各自包）、动态能力降级、新 provider 接入。

---

## 4. 待裁决点（实施前）

- **R1 凭证顺序**：stored OAuth 接入位置。pi 口径＝仅次 CLI 直给、**高于 ambient env**。
  选项：(a) 严格照 pi，stored OAuth 提到 token-env 之前（可能改变现有 env 用户行为）；
  (b) 只在现有链尾补 stored OAuth（最小、但与 pi 优先级不符）。
  建议 (a)：stored 拥有 provider 是 pi 明文（`resolve.ts:44-48`）；但会牵涉 java 现有
  env-vs-store 顺序——是否在本包一并对齐，请裁决。
- **R2 原子刷新**：是否给 OAuthCredentialStore 加一个 `modify`/条件更新最小方法以实现
  锁内单次刷新（pi 有 CredentialStore.modify）。建议加（行为正确性所需）。
- **R3 形态落点**：Anthropic 订阅 token 记 `API_KEY`（x-api-key）。确认与现有
  `ANTHROPIC_OAUTH_TOKEN → OAUTH` env 分支不冲突（两条来源不同：env vs stored）。

---

## 5. 测试计划（RED 先）

1. **接通请求**：OAuthCredentialStore 有 Anthropic 订阅 token ⇒ 请求（RecordingHttpServer）
   经 **`x-api-key: <access>`** 发出；旧实现（不读 store）⇒ 无凭证/请求失败 ⇒ 先红。
2. **不是 Bearer**：同一情形断言**无** `Authorization: Bearer`，且走 x-api-key（钉 toAuth 形态）。
3. **5 分钟主动刷新**：构造 3 分钟后过期的凭证 + 脚本化刷新响应 ⇒ 请求发出的是**新** token，
   且 store 已回存；硬过期才刷的实现 ⇒ 红。
4. **并发单次刷新**：两个并发请求遇临期 ⇒ 刷新端点只被调用一次（锁内复核）。
5. **优先级**：CLI `--api-key` 直给 ⇒ 压过 stored OAuth；（按 R1 结论补 stored-vs-env 顺序用例）。
6. **刷新失败不静默回落**：刷新端点报错 ⇒ 请求以错误收场，不悄悄改走 env（对照 pi）。

观测面统一 RecordingHttpServer（头＋体＋请求计数）。Mutation：去掉 5 分钟窗口⇒红、
toAuth 改成 BEARER⇒红、不读 store⇒红。

**回归**：`pi-java-ai` 全模块（Credentials/AuthCommand/OAuth 全套）；`pi-java-coding-agent`；
`pi-java-agent-core`；全 reactor。

---

## 6. 风险

- **改变 env-vs-stored 优先级**（R1）可能影响现有用户（既有 env token 与 stored 订阅并存时）：
  以 pi 明文为准，文档与 release note 注明。
- 无真实 Anthropic 订阅端到端条件 ⇒ 以「形状照 pi + 本地 wire 夹具」为证据（同各包口径）。

---

## 12. 实施记录

**闭环：2026-10-01，`4a869c9`（用户裁决「按照建议实施」，R1/R2/R3 全按建议）。**

| Step | 落点 |
|---|---|
| 1 下沉刷新 | 新增 `OAuthTokenRefresher`（函数式）＋ `DefaultOAuthTokenRefresher`（PKCE→`OAuthFlow`／device→`DeviceCodeFlow` 分派，原 `AuthCommand.refresh` 下沉）；`AuthCommand.resolve` 改为复用共享链。 |
| 2 接入凭证裁决 | `Credentials` 新增 stored OAuth 层（`StoredOAuthSource`）：profile env/file 之后、token env 之前；产出形态经 `oauthKind`——**anthropic ⇒ `API_KEY`（x-api-key，照 pi `toAuth⇒{apiKey}`）**，其余 provider 暂记 `BEARER`；source=`stored-oauth:<provider>`。 |
| 3 5 分钟主动刷新 | 新增 `StoredOAuthCredentials`：`now+5min>=expiresAt` 即刷（`MINIMUM_VALIDITY_SECONDS=300`），全程在 `OAuthCredentialStore.modify` 锁内读-刷-写 ⇒ 全局单次；失败抛 `OAuthRefreshException`，**不静默回落 env**。 |
| 4 路径贯通 | `DefaultProviders` 经 `Credentials::resolveCredential`（公共方法已含 stored OAuth）即得 x-api-key 凭证；CLI `--api-key`/settings 在 `apiOptions` 中先取、仍压过 stored OAuth（pi overrides 最高）。 |

**R2 落点与一处 JVM 事实**：`OAuthCredentialStore.modify` 做锁内读-改-写。`FileLock` 代表
整个 JVM 持有，同 JVM 第二线程对同一区域加锁会抛 `OverlappingFileLockException`（不阻塞），
故另加 per-path 的 `VM_LOCKS`（`ReentrantLock`）串行同 JVM 线程，文件锁只负责跨进程。
读字节不经 `Channels.newInputStream`（Jackson 关流会连带关通道），直接读入字节缓冲。

**证据（RED/回归）**：
- `CredentialChainTest`：`storedAnthropicOauthYieldsApiKeyNotBearer`（钉 API_KEY 非 BEARER）、
  `storedOauthWinsOverTokenEnvAndDefaultEnv`、`nonAnthropicStoredOauthDefaultsToBearer`、
  `emptyStoredOauthFallsThroughToAmbient`。
- `StoredOAuthCredentialsTest`（7）：新鲜不刷／3 分钟临期主动刷（且回存）／硬过期刷／
  permanent 不刷／刷新失败抛 `OAuthRefreshException` 且不改写／**并发两请求刷新恰一次**／临期边界。
- `OAuthCredentialStoreTest`：`modify` 锁内应用并持久化、缺失 provider 返回 null 且不调 mutation。
- 形态→真出站头的既有证据：`AnthropicAuthKindTest.apiKeyKindSendsXApiKeyAndNoAuthorization`。
- 回归：`pi-java-ai` **1208/1208**、`pi-java-coding-agent` **287/287**，checkstyle 0 违规。

**台账**：`docs/32` **B82 结案**（其原建议的 `AuthKind.OAUTH` 经核实更正为 `API_KEY`，
因 pi 订阅 token `toAuth` 返回 `{apiKey}`）；**B81**（系统提示前缀＋工具名映射）仍开，
属 A-15 范围、不在本包。
