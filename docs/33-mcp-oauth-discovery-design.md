# 包 33：MCP 对齐（B160）——第 6 包：OAuth 元数据发现

> **状态：📝 待审核（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 6 包（pi `oauth/errors.ts` 55 行＋
> `oauth/types.ts` 204 行＋`oauth/discovery.ts` 185 行，合计 444 行）详细设计。
> 审核通过后才许写代码。

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

### 4.2 Protected resource metadata（:39-68）

```java
record OAuthProtectedResourceMetadata(
        String resource,                  // 必选，https URL
        List<String> authorizationServers,// 必选；每项 https URL
        @Nullable String name,
        @Nullable String clientRegistrationEndpoint,  // https
        @Nullable List<String> scopesSupported,
        @Nullable String codeChallengeMethodsSupported,   // string[]
        @Nullable Map<String,Object> extension) { }       // snake_case 额外字段
```

### 4.3 Authorization server metadata（:70-131）

必选：`issuer`（https）；其余按 RFC 8414：
`authorizationEndpoint`（:90）、`tokenEndpoint`（:95）、
`jwksUri`、`registrationEndpoint`、`scopesSupported`、
`responseTypesSupported`（:107 必选）、`grantTypesSupported`、
`tokenEndpointAuthMethodsSupported`、`codeChallengeMethodsSupported`、
其余可选项（:114-126）＋extension。

## 5. 解析器（对齐 types.ts:134-202 的 compact 语义）

Java 用 Jackson 先读 `Map<String,Object>`，手工校验后构造 record：

- `parseProtectedResourceMetadata(Object raw)`：
  - 非对象 ⇒ OAuthError("invalid protected resource metadata …")；
  - `resource` 必须字符串且为 **https URL**（:143-149）；
  - `authorization_servers` 必须存在且是数组，**逐项 https URL**（:150-160）；
  - 未知键收入 extension（:161-164）。
- `parseAuthorizationServerMetadata(raw)`：
  - `issuer` 必选 https（:178-185）；
  - `authorization_endpoint` 若有须 https（:186）；
  - `response_types_supported` 必选数组（:173-176, RFC 必选项）；
  - 提供的 `grant_types_supported` 等数组校验元素类型；
  - compact 的「drop undefined」对 Jackson 自动满足；缺字段⇒null（record 组件对象类型）。

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
| 2 | PR metadata 解析：合法；resource 非 https/authorization_servers 缺失或含非 https ⇒ 错 |
| 3 | AS metadata 解析：issuer 必选 https；response_types_supported 必选；合法通过 |
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
| M4 | PR metadata 不校验 authorization_servers https | 用例 2 |
| M5 | selectResource 不做路径前缀检查 | 用例 8 |
| M6 | field 正则把空值（`=""`）当有效值 | 用例 1 |

每次变异 grep 复核落地。

## 10. 台账影响

- B160 不销号；B177 维持。闭环回填 docs/28 banner 与本文。
