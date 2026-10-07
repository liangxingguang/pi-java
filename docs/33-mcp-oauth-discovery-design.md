# 包 33：MCP 对齐（B160）——第 6 包：OAuth 元数据发现

> **状态：✅ 已审核，源码校正后实施（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 6 包（pi `oauth/errors.ts` 55 行＋
> `oauth/types.ts` 204 行＋`oauth/discovery.ts` 185 行，合计 444 行）详细设计。
>
> **审核后校正（见 §11，2026-10-07）**：重读三个源文件全文，三处失实已改——
> 解析器不强制 https、authorization_servers 可选、PR metadata 仅三具名字段；
> 校正方向全部趋向更忠实，不扩大范围。M4 探针同步替换为「危险协议拒绝」。

## 1. 范围边界

本包只做**静态结构与发现**：错误类型、元数据 record 与解析校验、WWW-Authenticate
解析、三级 discovery（protected resource → authorization server → server info）、
资源匹配。**动态流程**（PKCE、DCR、token 交换、刷新）在第 7 包。

oauth.test.ts 8 条款中，直接归属本包：clause 5（issuer 不匹配拒绝）、clause 6
（配置的 AS metadata 原样使用）；其余全流程条款在包⑦。

## 2. 文件清单（主源，新包 `com.pijava.mcp.oauth`）

| 新文件 | 对齐 |
|---|---|
| `OAuthError.java` | errors.ts:1-14 |
| `OAuthIssuerMismatchError.java` | :16-30 |
| `OAuthInsecureEndpointError.java` | :32-42 |
| `OAuthRegistrationError.java` | :44-54 |
| `McpOAuthAuthorizationRequiredError.java` | :56-62 |
| `OAuthChallenge.java` | types.ts:30-37 |
| `OAuthServerInfo.java` | types.ts:167-172 |
| `OAuthProtectedResourceMetadata.java` | types.ts:39-68 |
| `AuthorizationServerMetadata.java` | types.ts:70-131 |
| `OAuthMetadataParsers.java` | types.ts:134-202（两个 parse） |
| `OAuthFetch.java`（测试/包⑦注入用小接口） | McpFetch shape |
| `WwwAuthenticate.java` | discovery.ts:39-65 |
| `OAuthDiscovery.java` | discovery.ts:67-185 |

## 3. 异常（errors.ts 逐字）

5 个 final 类，语义字段同 TS：

- `OAuthError extends RuntimeException`：`String code()`、`@Nullable String errorUri()`；
  message＝message||code。
- `OAuthIssuerMismatchError`：`String expected()`、`@Nullable String received()`；
  message：`OAuth issuer mismatch: expected "<e>", received none/"<r>"`。
- `OAuthInsecureEndpointError`：`String endpoint()`。
- `OAuthRegistrationError`：`int status()`、`String body()`。
- `McpOAuthAuthorizationRequiredError`：无字段。

## 4. 元数据 record（types.ts）

### 4.1 小类型

```java
record OAuthChallenge(@Nullable URL resourceMetadataUrl,
                      @Nullable String scope,
                      @Nullable String error,
                      @Nullable String errorDescription) { }

record OAuthServerInfo(String authorizationServerUrl,
                       @Nullable AuthorizationServerMetadata authorizationServerMetadata,
                       @Nullable OAuthProtectedResourceMetadata resourceMetadata) { }
```

### 4.2 Protected resource metadata（对齐 TS interface types.ts:9-14）

TS 侧具名字段只有三个，其余经 index signature 直通：

```java
record OAuthProtectedResourceMetadata(
        String resource,                        // 必选，safeUrl
        @Nullable List<String> authorizationServers, // 可选；每项 safeUrl
        @Nullable List<String> scopesSupported,      // 可选，字符串数组
        @Nullable Map<String,Object> extension) { }  // 其余未知键原样保留
```

### 4.3 Authorization server metadata（对齐 TS interface types.ts:16-30）

必选：`issuer`、`authorization_endpoint`、`token_endpoint`（三者均 safeUrl）、
`response_types_supported`（必选字符串数组）；可选：`registration_endpoint`
（optionalUrl，`""` 视同缺省）、`scopes_supported`、`grant_types_supported`、
`token_endpoint_auth_methods_supported`、`code_challenge_methods_supported`
（均 optionalStrings）、`client_id_metadata_document_supported` 与
`authorization_response_iss_parameter_supported`（严格 boolean；**非布尔值
静默丢弃为缺省，不报错**，types.ts:167-174）；其余键入 extension。

## 5. 解析器（对齐 types.ts:134-202 的 compact 语义）

Java 用 Jackson 先读 `Map<String,Object>`，手工校验后构造 record。
**注意：pi 的解析器不强制 https**——`safeUrl`（types.ts:121-128）＝非空字符串
→ URL.canParse → 拒绝 `javascript:`/`data:`/`vbscript:` 三种协议；http 可通过。
https 门在包⑦ `flow.ts:108`（发送凭据前，loopback 放行）。

- `parseProtectedResourceMetadata(Object raw)`（types.ts:134-144）：
  - 非对象 ⇒ `Error("Invalid OAuth protected resource metadata")`；
  - `resource` 走 safeUrl（必填，缺失/非串/不可解析/危险协议 ⇒ 错）；
  - `authorization_servers`：**可选**；undefined/null ⇒ 缺省；`""` 或非数组、
    含非字符串元素 ⇒ 错；存在则逐项走 safeUrl（:139-141）；
  - `scopes_supported`：optionalStrings（不做 URL 校验）；
  - 未知键收入 extension；compact 丢 undefined 在 Java 侧天然成立。
- `parseAuthorizationServerMetadata(raw)`（types.ts:146-176）：
  - `response_types_supported`：optionalStrings 后必须存在，否则
    `Error("Invalid response_types_supported")`；
  - `issuer`/`authorization_endpoint`/`token_endpoint` 均 safeUrl 必填；
  - `registration_endpoint`：optionalUrl（absent 含 `""`，:105-108）；
  - 四个字符串数组各自校验；两布尔字段非布尔 ⇒ 静默丢弃。

## 6. WWW-Authenticate（discovery.ts:39-65）

```java
static OAuthChallenge parse(@Nullable String header)
```

- scheme：首词小写，仅 `bearer`/`dpop`，否则空 challenge；
- `field(header,name)` 正则：`(?:^|[,\s])name=(?:"([^"]*)"|([^\s,]+)` 大小写不敏感；
  空字符串（含 `=""`）视为缺省；
- 提取 resource_metadata（可解析为 URL 才收）、scope、error、error_description。

## 7. Discovery（discovery.ts）

### 7.1 `OAuthFetch`（本包小接口）

```java
@FunctionalInterface
interface OAuthFetch {
    Fetched fetch(URI url, Map<String,String> headers) throws Exception;
    record Fetched(int status, boolean ok, byte[] body) { }
}
```

包⑦提供 JDK HTTP 实现；测试脚本化。

### 7.2 Protected resource（:67-101）

`discoverProtectedResourceMetadata(serverUrl, resourceMetadataUrl, protocolVersion, fetch)`：
- 默认 URL：`<origin>/.well-known/oauth-protected-resource + pathSuffix(pathname)`
  （RFC 路径＝带路径后缀）；显式 URL 直接用；
- 请求头 `Accept: application/json`、`MCP-Protocol-Version`；
- **回退**：非显式 URL、pathname≠/、status 命中 `isDiscoveryMiss`（4xx 或 502）⇒
  discard 后改取 `/.well-known/oauth-protected-resource` 根路径；
- !ok ⇒ `HTTP <n> loading OAuth protected resource metadata`；
- 2xx ⇒ parse。

### 7.3 Authorization server URLs（:103-116）

`buildAuthorizationServerDiscoveryUrls(AS)` 顺序：
1. `/.well-known/oauth-authorization-server<path>`（oauth）
2. `/.well-known/openid-configuration<path>`（oidc）
3. path 非空时 `<path>/.well-known/openid-configuration`（oidc）

### 7.4 Authorization server（:118-149）

依次请求候选：
- non-ok：discard；miss（4xx/502）⇒ 下一候选；其他 ⇒ 抛 HTTP 错误；
- 2xx：parse；**issuer 校验**：trim 末尾 `/` 后与传入 AS 字符串比较，不等 ⇒
  `OAuthIssuerMismatchError(expected, metadata.issuer)`；skipIssuerValidation 跳过；
- 全部 miss ⇒ `undefined`。

### 7.5 server info（:151-185）

`discoverOAuthServerInfo(serverUrl, opts)`：
- PR 发现失败：TypeError（网络）抛出，其他吞掉，resourceMetadata＝null；
- 显式 AS metadata URL：直接 GET，parse，返回（**信任、不校验 issuer**）；
- AS url：`resourceMetadata.authorization_servers[0]` 缺省 ⇒
  `new URL("/", serverUrl).toString()`（服务器源）；
- 组合 `OAuthServerInfo`。

### 7.6 资源匹配（:187-202）

- `resourceUrlFromServerUrl`：去 hash；
- `selectResource(serverUrl, metadata)`：无 metadata ⇒ undefined；
  origin 不等或 server path 不是 resource path 前缀 ⇒ 抛
  "Protected resource … does not匹配 MCP server …"；两边路径按「末尾补 `/`」规范化；
  匹配 ⇒ metadata.resource。

## 8. 测试计划（L5，`OAuthDiscoveryTest` 等）

脚本化 OAuthFetch（队列应答）：

| # | 用例 |
|---|---|
| 1 | parseWwwAuthenticate：bearer/dpop、引号/裸值、空值缺省、非 bearer 空 challenge |
| 2 | PR metadata 解析：合法（含 authorization_servers 整体缺省）；resource 缺失/非串/`javascript:` 危险协议 ⇒ 错；authorization_servers 非数组或含不可解析 URL ⇒ 错 |
| 3 | AS metadata 解析：issuer/authorization_endpoint/token_endpoint 必填可解析；response_types_supported 必选；两布尔非布尔静默丢弃；合法通过 |
| 4 | build URLs：三候选顺序与 path 变体（根路径只 2 个） |
| 5 | PR 发现：路径后缀→404⇒根路径回退成功；5xx 不回退直接抛 |
| 6 | AS 发现：oauth 候选 miss⇒oid 命中；issuer 不等抛错（含 bare origin 末尾斜杠对齐）；全 miss⇒空 |
| 7 | server info：PR 提供 AS 取首项；PR 缺失⇒服务器源；显式 metadata URL 原样信任 |
| 8 | selectResource：同源前缀匹配；origin 不符/路径非前缀⇒错；无 metadata⇒空 |

对应 oauth.test clause 5/6 端到端形状（仅发现段）。

## 9. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | PR miss 不回退根路径 | 用例 5 |
| M2 | AS 发现跳过 issuer 校验（恒通过） | issuer 条款（oauth.test clause 5） |
| M3 | URL 候选顺序 oauth/oidc 调换 | 用例 4/6 |
| M4 | safeUrl 不拒绝 `javascript:`/`data:`/`vbscript:` 危险协议 | 用例 2 |
| M5 | selectResource 不做路径前缀检查 | 用例 8 |
| M6 | field 正则把空值（`=""`）当有效值 | 用例 1 |

每次变异 grep 复核落地。

## 10. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。

## 11. 审核后源码校正（2026-10-07）

设计稿审核通过后，重读 `errors.ts`/`types.ts`/`discovery.ts` 全文（初稿取证
仅靠局部摘录），三处失实，实施前更正：

| # | 初稿说法 | pi 源码事实 | 落点 |
|---|---|---|---|
| 1 | 解析器校验字段为 https URL | `safeUrl`（types.ts:121-128）只拒绝 `javascript:`/`data:`/`vbscript:`；https 门在 `flow.ts:108`（包⑦） | §4.2/§5 |
| 2 | `authorization_servers` 必选 | optionalStrings，可整体缺省（types.ts:139） | §4.2/§5 |
| 3 | PR metadata 具名 name/clientRegistrationEndpoint 等字段 | TS interface 仅 resource/authorization_servers/scopes_supported 三具名字段（types.ts:9-14） | §4.2 |

附带修正：两布尔字段非布尔值静默丢弃（不报错）；M4 探针由「去 https 校验」
（事实不存在该检查，会零红）替换为「不拒绝危险协议」。

校正方向全部趋向更忠实，不扩大范围。教训同包⑤：**设计取证必须读源文件全文，
局部摘录不足以支撑逐字对齐。**
