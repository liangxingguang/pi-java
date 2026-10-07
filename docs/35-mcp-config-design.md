# 包 35：MCP 对齐（B160）——第 8 包：mcp.json 配置加载

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 总包路线见 `docs/28` §3；本文是第 8 包（pi `core/mcp-servers.ts` 319 行**配置面**
> ＋ `extensions/mcp/config.ts` 242 行）详细设计。配置加载是 MCP 扩展的**输入面**：
> 第 9/11/12 包（连接编排、`/mcp` 与 `pi mcp` CLI、ExtensionFactory 接线）全部
> 消费本包的类型与加载结果。实施记录见 §10。

## 1. 范围边界

**做**：配置**形状与校验**（`McpServerConfig` 三态 + exposure + namespace +
per-tool exposure）、`mcp.json` 的**全局/项目加载**（trust 门、同名覆盖、
override 条目）、**增删改**（`update`/`add`/`remove`），以及 pi 用
`JSON.stringify(parsed, null, indent)` 落盘所需的 **JS 等价 JSON 写出器**。

**不做**（各自归属已核）：

| 不做 | 出处 | 归属 |
|---|---|---|
| `${NAME}` / `!cmd` 值解析（env/headers/clientSecret） | `core/resolve-config-value.ts`，消费点 `runtime.ts:32,112,240` | 第 9 包（连接时解析，**加载期原样保留**，见 pi 夹具 `TOKEN_HEADER`） |
| `McpServerRegistry` / `RegisteredMcpServer`（`pi.registerMcpServer()`） | `mcp-servers.ts:281-319` | 第 12 包（裁决 A，§2.4） |
| `agentDir` 解析（`~/.pi-java/agent`、`PI_JAVA_CODING_AGENT_DIR`） | 已有 `FileSettingsStorage.defaultAgentDir():48-58` | 调用方传入 |
| project trust 判定 | 已有 `SettingsManager.isProjectTrusted():85` | 调用方传入布尔 |
| `/mcp`、`pi mcp` 的命令面 | `extensions/mcp/ui.ts`、`cli.ts` | 第 11 包 |

⚠️ 本包**暂无生产消费者**（类型/加载器由后续包接线）。这是路线图的固有次序，
不是「无生产者残留」——裁决与理由同 `docs/28` §3。

## 2. 五处裁决

### 2.1 配置模型：sealed interface + record，**不保留未知键**（登记 B181）

pi 的 `validateMcpServerConfig` 返回的是**原对象副本**（`mcp-servers.ts:263,275`
两处 `return value as unknown as Mcp…`），未知键随对象透传。Java 用 record 建模，
未知键会在「校验后」丢失。

**论证（为什么可以丢）**：未知键在生产上**没有读者**——
① 下游（第 9–12 包）只读具名键（`command/args/env/cwd/url/headers/oauth/auth/
exposure/toolExposure/enabled/timeout/description`）；
② 写路径 `update`/`add`/`remove` **重新读文件**（`config.ts:232`），未知键在
那里原样保留；
③ 唯一读 record 又回写的地方是 override 合并（`{...base.config, ...value}`，
`config.ts:116`），那里丢的也只是 base 的未知键，**结果同样无人读**。
⇒ 登记 **B181**（刻意偏差）。

### 2.2 校验结果用 sealed `McpConfigValidation`

pi 用 `McpServerConfig | string` 联合、调用方 `typeof === "string"` 判型
（`config.ts:116-126`）。Java 侧对齐 ADT：

```java
public sealed interface McpConfigValidation {
    record Valid(McpServerConfig config) implements McpConfigValidation { }
    record Invalid(String message) implements McpConfigValidation { }
}
```

### 2.3 配置目录：`.pi-java`（沿用 pi-java 既有约定）

pi：`CONFIG_DIR_NAME = pkg.piConfig?.configDir || ".pi"`（`config.ts:542`），
全局 `~/.pi/agent/mcp.json`、项目 `<cwd>/.pi/mcp.json`。
pi-java 全树既有约定是 `.pi-java`（`FileSettingsStorage:42` 项目
`.pi-java/settings.json`；`ModelsJsonConfig:59` 全局 `~/.pi-java/agent`）。

裁决：`McpConfig.PROJECT_DIR_NAME = ".pi-java"` 常量落在 mcp 模块内，
`load(agentDir, cwd, projectTrusted)` 签名与 pi 1:1（`config.ts:145`）。
「模块内硬编码目录名」有 `ModelsJsonConfig` 先例（ai 模块）。登记 **B182**。
（备选：把项目目录名做成参数——本包不取，因为 pi-java 无「可配 configDir」需求。）

### 2.4 `McpServerRegistry` 顺延第 12 包（裁决 A，推荐）

`mcp-servers.ts:281-319` 的注册表只有**一个生产者**：扩展 API
`pi.registerMcpServer()`（`core/extensions/types.ts`），在第 12 包接线。
本包**不建**该类，`McpScope` 只留 `GLOBAL | PROJECT`（`EXTENSION` 与
`McpServerEntry.scope` 的第三态随第 12 包一起加）。理由：现在建＝零生产者代码。

（裁决 B：若要求 `mcp-servers.ts` 文件级 1:1 完整，则本包一并落
`McpServerRegistry` 与 `McpScope.EXTENSION`，直接单测三条语义
——register/unregister 的所有权检查、`list()` 深拷贝、change 监听。
差异仅此一处，其余设计不变。）

### 2.5 JS `JSON.stringify` 逐字等价写出器

`config.ts:241` 用 `JSON.stringify(parsed, null, indent)` 落盘（**保持原文件的
缩进**、其余内容原样）。Jackson 的 `DefaultPrettyPrinter` 输出
`"key" : value`、数组内联，**对不上**。本包新建
`JsJson.stringify(JsonNode, String indent)`：容器自己递归（`{`/`}` 与
`[\n …]`、空容器 `{}`/`[]`、`": "` 分隔），**标量直接用 `node.toString()`**
（Jackson 紧凑写法＝JS 字面量：`TextNode.toString()` 已带正确转义与引号）。
详见 §5.3。

## 3. 类型与校验（`mcp-servers.ts:17-278`）

### 3.1 `McpExposure`（enum，`:17-22,176-183`）

纯常量闭集 ⇒ enum（CLAUDE.md）。⚠️ **声明序即报错文本序**（`:226`
`MCP_EXPOSURES.map(v => \`"${v}"\`).join(", ")`）：

```java
/** pi `mcp-servers.ts:17` 的 exposure。声明序不可改（:226 的报错按此序拼接）。 */
public enum McpExposure {
    CODEMODE("codemode"), DEFERRED("deferred"), DIRECT("direct"), HIDDEN("hidden");

    /** 旧名：配置里仍接受，校验后替换为现名（:22,180-183）。 */
    private static final Map<String, McpExposure> ALIASES = Map.of("codemode-deferred", CODEMODE);

    private final String wire;

    McpExposure(String wire) { this.wire = wire; }

    /** 线上值（`"codemode"`…），`@JsonValue` 挂在它上面。 */
    @JsonValue
    public String wire() { return wire; }

    /** 解析 `wire()` 或旧名；未知 ⇒ {@code null}（调用方给报错文本）。 */
    public static @Nullable McpExposure fromWire(String value) { … }
}
```

### 3.2 `McpServerConfig`（sealed interface + 嵌套 record，`:24-115`）

对齐 `McpContentBlock.java` 的既有 ADT 风格（sealed 接口 + 嵌套 record 同文件）：

```java
/**
 * pi `mcp-servers.ts:115` 的 `McpServerConfig` 联合；公共字段在 :24-43。
 *
 * <p>⚠️ 与 `McpContentBlock` 不同：<b>不加</b> {@code @JsonTypeInfo}。
 * 这里的 `type` 是**配置自己的字段**（`"stdio"`/`"http"`），加类型信息会多发一个键。</p>
 */
public sealed interface McpServerConfig permits McpServerConfig.Stdio, McpServerConfig.Http {

    /** 默认 {@code codemode}（:26）。 */
    @Nullable McpExposure exposure();

    @Nullable String description();

    /** 键为**服务器给的工具名**；顺序＝文件顺序（模式匹配靠它，:211）。 */
    @Nullable Map<String, McpExposure> toolExposure();

    /** 默认 true（:40）；{@code null} ＝键缺席。 */
    @Nullable Boolean enabled();

    /** 秒；默认 60（:41）。用 {@link Number} 保住字面量形状（整数不写成 `60.0`，§5.4）。 */
    @Nullable Number timeout();

    record Stdio(@Nullable String type, @Nullable McpExposure exposure, @Nullable String description,
                 @Nullable Map<String, McpExposure> toolExposure, @Nullable Boolean enabled,
                 @Nullable Number timeout, String command, @Nullable List<String> args,
                 @Nullable Map<String, String> env, @Nullable String cwd) implements McpServerConfig { }

    record Http(@Nullable String type, @Nullable McpExposure exposure, @Nullable String description,
                @Nullable Map<String, McpExposure> toolExposure, @Nullable Boolean enabled,
                @Nullable Number timeout, String url, @Nullable Map<String, String> headers,
                @Nullable OAuth oauth, @Nullable Auth auth) implements McpServerConfig { }

    /** `:56-91`。 */
    record OAuth(@Nullable String clientId, @Nullable String clientSecret, @Nullable Integer callbackPort,
                 @Nullable String callbackUrl, @Nullable String scope, @Nullable String clientName,
                 @Nullable String clientRegistration, @Nullable String authServerMetadataUrl) { }

    /** `:112` 的 `auth?: { provider }`。 */
    record Auth(String provider) { }
}
```

组件名＝JSON 键名（全 camelCase）⇒ **不需要 `@JsonProperty`**；
record → 树用 `McpJson.mapper().valueToTree(config)`（mapper 已 `NON_NULL`，
缺省键自然缺席）。

### 3.3 `validate`（`:221-278`）与错误文本

签名 `static McpConfigValidation validate(String name, JsonNode raw)`：
文件里的条目本来就是 `JsonNode`，record 合并（§4.2）经 `valueToTree` 归一，
**全包只有这一种表示**。

顺序即 pi 的顺序，**不可重排**（报错文本与`continue`依赖它）：

| 序 | 条件 | 结果 |
|---|---|---|
| 1 | `!SERVER_NAME.test(name)`（`^[A-Za-z0-9_-]+$`，`:117,222`） | `invalid server name "{name}" (use letters, digits, "_" and "-")` |
| 2 | `!isRecord(raw)`（Object 且非数组，`:223`） | `server "{name}" must be an object` |
| 3 | 别名替换（`resolveExposureAliases`，`:224,186-196`）：`exposure` 与 `toolExposure` 的每个值 | 不报错，仅改写 |
| 4 | `exposure` 非四值之一（`:227`） | `server "{name}": exposure must be one of "codemode", "deferred", "direct", "hidden"` |
| 5 | `toolExposure` 非对象 / 某个值非 exposure（`:230-235`） | `…: toolExposure must map tool names to exposures` / `…: toolExposure "{tool}" must be one of …` |
| 6 | `enabled` 非布尔（`:236`） | `server "{name}": enabled must be a boolean` |
| 7 | `description` 非串（`:237-239`） | `server "{name}": description must be a string` |
| 8 | `timeout` 非 number 或 `!(timeout > 0)`（`:240`；NaN 也拒） | `server "{name}": timeout must be a positive number of seconds` |
| 9 | `type === "sse"`（`:243`） | `server "{name}": legacy SSE transport is not supported; use the streamable HTTP URL` |
| 10 | `url` 是串 **且** `type ∈ {absent, "http", "streamable-http"}`（`:245`） | 进 HTTP 分支（11–14），否则落 15 |
| 11 | URL 不可解析或 scheme ∉ {http, https}（`:246`） | `server "{name}": url must be an http or https URL` |
| 12 | `headers` 非 string→string（`:249`） | `server "{name}": headers must map names to strings` |
| 13 | `oauth` 见 §3.5（`:252`） | `server "{name}": {oauth 子消息}` |
| 14 | `auth` 非 `{provider: 非空串}`（`:255`）→ `auth.provider must be a provider name`；否则 URL 非 https 且主机非 loopback（`:259`）→ `server "{name}": auth requires an https URL, or http on localhost, 127.0.0.1, or [::1]` | 见左 |
| 15 | `command` 是串 **且** `type ∈ {absent, "stdio"}`（`:265`） | 进 stdio 分支（16–18），否则 19 |
| 16 | `args` 非 string[]（`:266-270`） | `server "{name}": args must be an array of strings` |
| 17 | `env` 非 string→string（`:272`） | `server "{name}": env must map names to strings` |
| 18 | `cwd` 非串（`:274`） | `server "{name}": cwd must be a string` |
| 19 | 兜底（`:277`） | `server "{name}" needs either "command" (stdio) or "url" (streamable HTTP)` |

⚠️ 判「键存在」一律用 `node.has(k)`（**`null` 也算存在**）：JS 侧判的是
`value.command === undefined`，`{"command": null}` 在两边都是「非 override/非串」。
`hasNonNull` 会给出相反答案。

### 3.4 namespace 与 per-tool exposure（`:117-122,198-215`）

```java
/** 工具名前缀：`mcp__<server>`，`-` → `_`（:120-122）。冲突判定同用它（config.ts:128）。 */
public static String namespace(String server) { return "mcp__" + server.replace('-', '_'); }
```

`getMcpToolExposure(config, toolName)` 三条规则（**顺序不可换**）：

1. `toolExposure[toolName]`（精确名）命中 ⇒ 返回它；
2. 否则**按 `toolExposure` 的插入顺序**扫含 `*` 的键，**第一个**匹配的模式胜
   （`:211` `for (const [pattern, exposure] of Object.entries(...))`）⇒ Java 的
   `Map` 必须是保序的（Jackson 反序列化 JSON 对象＝`LinkedHashMap`，
   `valueToTree` 亦保序）；
3. 都没有 ⇒ `config.exposure ?? CODEMODE`。

模式→正则逐字对齐 `:198-204`：按 `*` 切段、每段转义 `. + ? ^ $ { } ( ) | [ ] \`
后 join `.*`，两端 `^…$`。**只有 `*` 特殊**（`.` 是字面量）——
pi 夹具把这点钉死：`{"get_file.*": "direct"}` **不**匹配 `get_file_x`
（`mcp-extension.test.ts:234-236`）。Java 用 `Pattern.quote(part)` 亦可，
但 `\E` 会破 `\Q…\E`；**按上面 11 个字符显式转义**更稳。

### 3.5 `isLoopbackRedirectUri`（`:93-100`）与 `validateOAuth`（`:132-174`）

```java
/** 只有这三个主机名算 loopback（:93）。⚠️ 与 oauth 包的 `OAuthEndpoints.loopback`
 *  （四个值、含裸 `::1`）**不是同一个函数**，别统一。 */
private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");
```

`validateOAuth` 逐条（顺序即报错顺序）：

| 条件 | 报错（`server "x": oauth…`） |
|---|---|
| 非对象 | `oauth must be an object` |
| `clientId`/`clientSecret`/`scope` 非串 | `oauth.clientId must be a string` 等 |
| `callbackPort` 非 1–65535 整数 | `oauth.callbackPort must be a port number` |
| `callbackUrl` 非 loopback http URI，或带 query/fragment | `oauth.callbackUrl must be an http URI on localhost, 127.0.0.1, or [::1] without query or fragment` |
| `callbackUrl` 的端口与 `callbackPort` 不同（两者都有端口时） | `oauth.callbackUrl and oauth.callbackPort name different ports` |
| `clientName` 非串或全空白 | `oauth.clientName must be a non-empty string` |
| `clientRegistration` ∉ {`dcr`,`cimd`} | `oauth.clientRegistration must be "dcr" or "cimd"` |
| `cimd` + `clientId`/`clientName` | `oauth.clientRegistration "cimd" cannot be combined with oauth.clientId or oauth.clientName` |
| `cimd` + callbackUrl 是 `[::1]` 或 path ≠ `/callback` | `oauth.clientRegistration "cimd" requires oauth.callbackUrl on localhost or 127.0.0.1 with path /callback` |
| `authServerMetadataUrl` 非 https（loopback 除外）或不可解析 | `oauth.authServerMetadataUrl must be an https URL, or http on localhost, 127.0.0.1, or [::1]` |

⚠️ **坑 1（大小写）**：JS 的 `new URL(...).hostname` **小写化**
（`http://LOCALHOST/mcp` ⇒ `localhost`）。Java `URI.getHost()` 保留原大小写
⇒ 比较前必须 `toLowerCase(Locale.ROOT)`，否则 pi 放行的 `http://LOCALHOST/mcp`
在 pi-java 报 `auth requires an https URL`。
⚠️ **坑 2（可解析性）**：JS 用 `URL.canParse`；Java 的 `URI.create` 对相对串
不抛（`"example.com/mcp"` ⇒ scheme=null）。逐条判型时**先看 scheme**，
不要只靠 try/catch（本包统一走 `parseHttpUrl(String) → @Nullable URI`：
可解析 **且** scheme ∈ {http,https} 才返回）。
⚠️ 坑 3：JS `URL.port` 缺省是 `""`，Java `URI.getPort()` 缺省是 `-1`
（`:147-150` 的 `urlPort &&` 守卫 ⇒ Java `getPort() > 0`）。

## 4. 加载（`config.ts:91-156`）

### 4.1 类型

```java
/** 配置里定义的一个服务器条目（`config.ts:51-63`）。 */
public record McpServerEntry(String name, McpServerConfig config, Path source,
                             McpScope scope, @Nullable Path override) { }

/** `scope`（`config.ts:60`）；`extension` 随第 12 包（裁决 A）。 */
public enum McpScope { GLOBAL, PROJECT }

/** `config.ts:65-72`。 */
public record LoadedMcpConfig(List<McpServerEntry> servers, @Nullable Boolean autoEnableCodemode,
                              List<String> errors, @Nullable Path projectConfig) { }
```

### 4.2 `load(Path agentDir, Path cwd, boolean projectTrusted)`

```java
var state = new LoaderState();                     // LinkedHashMap + Boolean + ArrayList
readConfigFile(agentDir.resolve("mcp.json"), McpScope.GLOBAL, state);
Path projectConfig = projectTrusted ? cwd.resolve(PROJECT_DIR_NAME).resolve("mcp.json") : null;
if (projectConfig != null) readConfigFile(projectConfig, McpScope.PROJECT, state);
return new LoadedMcpConfig(List.copyOf(state.servers.values()), state.autoEnableCodemode,
                           List.copyOf(state.errors), projectConfig);
```

`readConfigFile` 逐条（`:91-139`）：

1. 文件不存在 ⇒ 静默返回（`:93`）。⚠️ 用 `Files.exists`，**不要** `isRegularFile`
   ——JS 是 `existsSync` + `readFileSync`，同名目录会进 catch 变一条错误。
2. 读+解析失败 ⇒ `errors.add(path + ": " + message)`（**文本必然与 V8 不同**，
   结构同：`<路径>: <消息>`；登记 B183 同族）。
3. 顶层非对象，或 `mcpServers` 存在但非对象 ⇒
   `path + ": expected an object with an \"mcpServers\" object"`，**return**。
4. `autoEnableCodemode`：布尔 ⇒ 覆盖 state（文件内**后者胜**，项目文件覆盖全局）；
   存在但非布尔 ⇒ 一条错误（**不** return，`:105-106`）。
5. 按 `mcpServers` 的**文件顺序**遍历条目：
   - **项目 override 分支**（`scope == PROJECT && isRecord(value) && isOverride(value)`）：
     `isOverride` ＝ `command`/`url`/`type` **三键全缺**（`:77-79`）。
     - base ＝ 已加载的同名条目（全局先读 ⇒ 通常来自全局）；
     - `extra` ＝ 除 `enabled`/`exposure`/`toolExposure` 外的键（`:74,110`）；
     - base 缺席 ⇒ `server "{name}" needs "command" or "url", or a global server to override`
       （**此错优先于** extra 的错误，`missing` 与 `tools` 两例的顺序即此，`mcp-extension.test.ts:84-90`）；
     - `extra` 非空 ⇒ `server "{name}": an override can only set enabled, exposure, toolExposure`；
     - 否则：`merged = valueToTree(base.config)` 再 `setAll(value)`，**重新校验**
       （`:116`），通过 ⇒ 存成 `new McpServerEntry(name, config, base.source(),
       base.scope(), path)`——⚠️ **scope/source 保持 base 的**（测试断言
       override 条目的 `scope` 仍是 `global`），只加 `override = 本文件路径`；
     - 三条出口都 `continue`（override 分支**不做**下面两项检查）。
   - **普通条目**：`validate(name, value)`；`Invalid` ⇒ `errors.add(path + ": " + msg)`，continue。
   - **namespace 冲突**（`:128`）：在**已加载**条目里找第一个 `other != name`
     且 `namespace(other) == namespace(name)` 的 ⇒
     `server "{name}" conflicts with "{other}"`，continue。
     ⚠️ 排除同名 ⇒ 项目同名条目**替换**全局（不是冲突）；
     ⚠️ `LinkedHashMap.put` 对已存在键**保持原位置** ⇒ 结果数组里 `shared`
     仍在首位（`mcp-extension.test.ts:73-77` 的顺序即此）。
   - **项目 auth 拒绝**（`:133-136`）：`scope == PROJECT` 且配置是 `Http` 且
     `auth != null` ⇒ `server "{name}": auth is only allowed in the global mcp.json`，continue。
6. 禁用条目**照收**（`enabled:false` 也进列表，`:142-144` 注释）。

## 5. 增删改（`config.ts:159-242`）

### 5.1 API

```java
public record McpConfigPatch(@Nullable Boolean enabled, @Nullable McpExposure exposure) { }

public static void update(Path path, String name, McpConfigPatch patch) { update(path, name, patch, false); }
public static void update(Path path, String name, McpConfigPatch patch, boolean override) { … }
public static boolean add(Path path, String name, McpServerConfig config) { … }   // 返回「替换了既有条目」
public static boolean remove(Path path, String name) { … }                        // 文件不存在或没这条 ⇒ false
```

抛错用 `McpConfigError extends RuntimeException`（pi 抛裸 `Error`，无类名可对，
登记 **B184**；模块既有约定是 `*Error extends RuntimeException`）。

### 5.2 `update` 的 `keepDefaults`（`:169-193`）

- 目标条目缺席且 `override == true` ⇒ 新建空对象并挂到 `mcpServers`
  （`parsed.mcpServers = {...servers, [name]: server}`，Java 用
  `root.withObject("/mcpServers").set(name, server)`，结果树相同）。
- 目标非对象（缺席且非 override、或显式 `null`、或标量）⇒ 抛
  `path + " does not define MCP server \"" + name + "\""`。
- `keepDefaults = isOverride(server)`：**override 条目保留默认值**
  ⇒ `enabled: true` 与 `exposure: "codemode"` 照写；普通条目的默认值**删键**
  （`enabled:false` 与其余三种 exposure 恒写）。测试即此：
  `updateMcpServerConfig(project, "tools", {enabled: true})` 后文件里是
  `{enabled: true}`（`mcp-extension.test.ts:113-115`）。

### 5.3 `editMcpServers`（`:228-242`）与 `JsJson`

```java
private interface Edit { boolean apply(@Nullable ObjectNode servers, ObjectNode parsed); }

private static void editMcpServers(Path path, Edit edit) {
    String text = Files.exists(path) ? Files.readString(path) : null;   // IOException ⇒ McpConfigError
    var parsed = text == null ? McpJson.mapper().createObjectNode() : parseTree(text);
    if (!parsed.isObject() || (parsed.has("mcpServers") && !parsed.get("mcpServers").isObject())) {
        throw new McpConfigError(path + ": expected an object with an \"mcpServers\" object");
    }
    var root = (ObjectNode) parsed;
    var servers = root.get("mcpServers") instanceof ObjectNode object ? object : null;
    if (!edit.apply(servers, root)) return;                 // false ⇒ 不落盘
    Files.createDirectories(path.getParent());
    Files.writeString(path, JsJson.stringify(root, JsJson.detectIndent(text)) + "\n");
}
```

`JsJson`（新文件，≈70 行）：

```java
/** 首个「行首空白 + 非空白」行的缩进；无则两空格（`config.ts:239`）。 */
static String detectIndent(@Nullable String text) { … INDENT = Pattern.compile("^([ \\t]+)\\S", MULTILINE) … }

/** `JSON.stringify(node, null, indent)` 等价。空容器是 `{}`/`[]`，分隔是 `": "`。 */
static String stringify(JsonNode node, String indent) { var out = new StringBuilder(); write(out, node, indent, 0); return out.toString(); }

private static void write(StringBuilder out, JsonNode node, String indent, int depth) {
    if (node.isObject()) {
        if (node.isEmpty()) { out.append("{}"); return; }
        out.append("{\n");
        for (var it = node.fields(); it.hasNext(); ) {
            var field = it.next();
            out.append(indent.repeat(depth + 1)).append(TextNode.valueOf(field.getKey())).append(": ");
            write(out, field.getValue(), indent, depth + 1);
            if (it.hasNext()) out.append(',');
            out.append('\n');
        }
        out.append(indent.repeat(depth)).append('}');
    } else if (node.isArray()) { …同构，空数组 `[]`… }
    else { out.append(node); }      // 标量：Jackson 紧凑写法 ＝ JS 字面量（含转义与引号）
}
```

### 5.4 已知字面量偏差（登记，低危）

| 输入 | JS 写出 | Java 写出 | 说明 |
|---|---|---|---|
| `{"timeout": 1.0}` | `1` | `1.0` | JS number 只有一个类型；用 `Number` 组件＋Jackson 运行时子类可保住**整数**字面量（`60`→`60`），但 `1.0` 这类源字面量会保留小数 |
| `> 2^53` 的整数 | 四舍五入后的值 | 原值 | JS 精度丢失，Java 不丢 |
| 缩进 > 10 字符 | 截前 10 | 同（`detectIndent` 里截） | 已对齐 |
| `pi mcp add` 落盘的**键序** | CLI 自己的构造序 | record 组件序 | JSON 对象键序无语义；pi 夹具用 `toEqual`（序不敏感）。第 11 包可自行决定是否改判 |

## 6. 文件清单（均在 `pi-java-mcp/src/main/java/com/pijava/mcp/config/`）

| 文件 | 行数估 | 对齐 |
|---|---|---|
| `McpExposure.java` | ~45 | `mcp-servers.ts:17-22,176-183` |
| `McpServerConfig.java`（sealed＋Stdio/Http/OAuth/Auth） | ~90 | `:24-115` |
| `McpConfigValidation.java`（sealed＋Valid/Invalid） | ~15 | `:221` 返回型 |
| `McpServerConfigs.java`（validate/namespace/getMcpToolExposure/isLoopbackRedirectUri） | ~230 | `:96-215,221-278` |
| `McpServerEntry.java` / `McpScope.java` / `LoadedMcpConfig.java` | ~45 | `config.ts:51-72` |
| `McpConfigPatch.java` | ~10 | `config.ts:159-162` |
| `McpConfig.java`（load/readConfigFile/update/add/remove/editMcpServers） | ~230 | `config.ts:91-242` |
| `JsJson.java` | ~70 | JS 语义（§2.5） |
| `McpConfigError.java` | ~15 | pi 裸 `Error` |

每文件 ≤500 行；无 `@SuppressWarnings`。

## 7. 测试计划（L5，映射 pi 夹具）

pi 侧归属本包的夹具是 `test/mcp-extension.test.ts` 的 `describe("MCP config")`
（7 个 `it`）＋ `test/mcp-command.test.ts` 的落盘断言。逐条对齐：

| # | 用例 | pi 出处 |
|---|---|---|
| 1 | 全局+受信项目合并：同名项目条目**替换**且保位次、`off` 保留、未受信项目整文件忽略、4 条错误按**文件序**、`Bearer ${TOKEN}` 原样存活 | `:60-86` |
| 2 | override：只改 enabled/exposure/toolExposure；`extra` 键与「无 base」两条错误；合并后 config＝base+override、`override` 字段＝项目路径、`scope` 仍 global；`update` 后文件为 `{enabled:true}` | `:88-115` |
| 3 | `-`/`_` 同名前缀冲突（`work-files` vs `work_files`） | `:117-124` |
| 4 | exposure 四值＋别名（`codemode-deferred`→`codemode`，含 toolExposure 内）；`autoEnableCodemode` 项目覆盖与类型错误；`description` 两条 | `:125-160` |
| 5 | OAuth 校验 8 条错误＋6 个合法条目（`ipv6`/`same`/`cimd` 等边界） | `:162-212` |
| 6 | per-tool exposure：精确 > 顺序模式 > 默认；`get_file.*` 不匹配 `get_file_x` | `:214-236` |
| 7 | `auth`：仅全局；http 非 loopback 拒；空 provider 拒；项目同名替换被拒（且**不**报冲突） | `:238-263` |
| 8 | 写路径单测（pi 在 CLI 层）：`add` 新建/替换、`remove` 不存在文件 false / 没这条 false / 删到空留 `mcpServers: {}`、**4 空格缩进文件重写后仍 4 空格**、其余键与键序保留、`\t` 缩进 | `mcp-command.test.ts:160-190` |
| 9 | `JsJson`：空对象/空数组、嵌套、`": "` 分隔、键与串的转义（`"`/`\`/`\n`）、标量 `60` 不写成 `60.0`、`detectIndent` 四态（无缩进 / 2 / 4 / 制表） | §5.3 |

用例一律用 `@TempDir` 真文件（pi 用 `mkdtempSync`）＋ AssertJ。

## 8. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `isOverride` 的三键删掉一个（如 `type`） | 用例 2（有 `type` 的条目被当 override） |
| M2 | override 分支去掉 `extra` 检查 | 用例 2 |
| M3 | namespace 冲突检查去掉 `other != name` 排除 | 用例 7（同名替换被误报冲突） |
| M4 | 项目 auth 拒绝删除 | 用例 7 |
| M5 | `namespace` 不做 `-`→`_` | 用例 3 |
| M6 | `getMcpToolExposure` 先扫模式再看精确名 | 用例 6 |
| M7 | `keepDefaults` 恒 false | 用例 2（override 文件被删掉 `enabled:true`） |
| M8 | `detectIndent` 恒返回两空格 | 用例 8（4 空格文件被重排） |
| M9 | `resolveExposureAliases` 删除 | 用例 4（`codemode-deferred` 报错） |
| M10 | `JsJson` 空容器写成 `{\n}` / `[\n]` | 用例 9 |
| M11 | `LOOPBACK_HOSTS` 比较不小写化 | 用例 7（`http://LOCALHOST` 误拒）——**先补一条夹具再变异** |

每次变异后 grep 复核落地（CRLF 教训：`$` 行尾锚点默认失效 ⇒ 跨行变异走 Edit），
还原后 clean 全量复跑。

## 9. 台账影响

- B160 不销号；B177 维持。
- 新登记：**B181**（未知键不保留，§2.1）、**B182**（`.pi-java` 常量硬编码，
  §2.3）、**B183**（解析错误文本与 V8 不同，结构同，§4.2-2）、
  **B184**（`McpConfigError` 是 Java 特有类型；含 §5.4 字面量/键序偏差）。
- 裁决 A 若被否（用户选裁决 B）⇒ 另加 `McpServerRegistry`/`McpScope.EXTENSION`，
  本文件清单与测试各增一列。
- 闭环时回填：**docs/28 banner**（第 8 包闭环）、本文 banner 与 §12、
  必要时 `docs/04` 的 B160 行注记。

## 10. 实施记录（2026-10-07，R-A）

**落地（1 feat ＋ 1 docs）**，16 文件：11 主源新增、4 测试新增、
`spotbugs-exclude.xml` 改 1（B185）。

| 组件 | 文件（`com.pijava.mcp.config`） |
|---|---|
| 类型 | `McpExposure`（enum＋`@JsonValue`）、`McpServerConfig`（sealed＋Stdio/Http/OAuth/Auth）、`McpConfigValidation`（Valid/Invalid）、`McpScope`、`McpServerEntry`、`LoadedMcpConfig`、`McpConfigPatch` |
| 校验 | `McpServerConfigs`（validate 19 条次序／namespace／getMcpToolExposure／isLoopbackRedirectUri／validateOAuth 10 条） |
| 加载与增删改 | `McpConfig`（load／readConfigFile／readServer／applyOverride／update／add／remove／editMcpServers）、`McpConfigError` |
| 写出 | `JsJson`（`JSON.stringify(node,null,indent)` 等价＋缩进探测） |

**测试 144/144 绿**（模块；新增 29：`JsJsonTest` 4／`McpConfigLoadTest` 6／
`McpConfigWriterTest` 10／`McpServerConfigsTest` 9）；checkstyle 0、spotbugs 0；
最长文件 431 行（`McpServerConfigs`）。

**变异探针 12/12 命中**（共 20 条红）：

| # | 变异 | 设计预期 | 实测红 |
|---|---|---|---|
| M1 | `isOverride` 去掉 `type` 判 | 1 | **1**（LoadTest override 用例新增条款） |
| M2 | override 去掉 `extra` 检查 | 1 | **1** |
| M3 | 冲突检查去掉 `other != name` 排除 | 1 | **2**（多中 case 1 的同名替换） |
| M4 | 删除项目 auth 拒绝 | 1 | **1** |
| M5 | `namespace` 不做 `-`→`_` | 1 | **2**（LoadTest＋ConfigsTest） |
| M6 | 模式先于精确名匹配 | 1 | **1** |
| M7 | `keepDefaults` 恒 false | 1 | **1** |
| M8 | `detectIndent` 恒两空格 | 1 | **5**（JsJson 1＋Writer 4） |
| M9 | 别名解析删除 | 1 | **0 ⇒ 修实现后 2**（见发现 1） |
| M10 | 空容器不特判 | 1 | **2** |
| M11 | loopback 主机名不小写化 | 1 | **1** |
| M12 | `withObject("/mcpServers")` 换成别的键（**实施后补的条款**） | — | **2**（两条 override 新建用例） |

每次变异后 grep 复核落地（`if (false)`／`// M9` 标记），还原后 grep 零残留。

### 设计外发现

1. **M9 首轮零红，成因是「实现里的冗余路径把变异体掩盖了」**（第五种成因又一面）。
   `McpExposure.fromWire` 当时也认 `codemode-deferred` ⇒ 校验期其实解析了两次，
   节点改写那一步**行为上冗余** ⇒ 删掉它零红。修法不是加夹具而是**消除冗余**：
   别名表移出 enum（`EXPOSURE_ALIASES` 进 `McpServerConfigs`，位置同 pi 的
   `mcp-servers.ts:22`），`fromWire` 改严格（对齐 pi 的 `isExposure`：只认四个现名）。
   重构后 M9 恰 2 红。**这是「夹具写在实现之后」的又一次实证：唯一能看见它的是探针。**
2. **SpotBugs 在 mcp 模块从未被跑过**（包 ⑥/⑦ 只跑了 `test` ＋ `checkstyle`；
   `spotbugs:check` 绑在 `verify`）⇒ 本包跑 `verify` 立刻红两条：① 本包
   `Files.createDirectories(path.toAbsolutePath().getParent())` 的潜在 NPE（已修：
   parent 判空；`/` 这类无父路径不再 NPE）；② 包 ⑥ 的
   `optionalBoolean` 三态返回是误报（TRUE/FALSE/null，null 是「键缺席」，
   与 B144 同形，`@Nullable` 用的是 jspecify，SpotBugs 不认）⇒ 按本仓排除机制
   登记 **B185**。**教训：包闭环的验证口径要含 `verify`，不只是 `test`。**
3. **设计预测的「预期红」条数普遍偏少**（M3/M5/M8）：夹具覆盖面比设计时以为的更广
   ⇒ 该列今后按「至少这些」写。
4. **收尾复跑抓到一条包④ 的既有抢跑夹具**（`InMemoryTransportTest`）：旧断言
   「`send` 返回时监听器尚未跑」是在**跟虚拟线程抢调度**（实测 7 跑 1 红，1 红即
   全局 144 红）。产品契约（`send` 不在调用栈上重入）由 `startVirtualThread`
   结构性保证 ⇒ 夹具改为确定性断言「监听器线程 ≠ 发送线程」，8 跑 8 绿，
   反向变异（改内联投送）恰 1 红。登记 **B187**。
   **教训：凡「某时刻还没发生」的断言都是运气**（与并发夹具那批教训同族）。
5. **补了两条超出 pi 夹具的条款**（都是实施后才写的，故**逐条补探针**）：
   ①带 `type` 的项目条目是**定义**不是 override（`config.ts:78` 判三键全缺）——
   pi 自己的夹具没覆盖 `type` 这一键，M1 若无此条款即零红；②`update(override)`
   打在**没有 `mcpServers` 键**的文件上（`withObject` 指针路径）—— M12 把它钉住。
   **教训：实施后补的夹具必须配一个探针，否则它只是「绿着的猜测」**（同「夹具写在
   实现之后」的固有风险）。

### 刻意偏差（登记）

- **B181**：配置 record 不保留未知键（§2.1 论证：下游只读具名键、写路径重读文件）。
- **B182**：项目配置目录 `.pi-java` 硬编码（pi 从 `package.json` 的
  `piConfig.configDir` 读；pi-java 全树既有约定，`FileSettingsStorage:42` 先例）。
- **B183**：解析错误文本与 V8 不同（结构同：`<路径>: <消息>`；含「空文件」
  在 pi 是语法错、在 Java 是 `expected an object with an "mcpServers" object`）。
- **B184**：`McpConfigError` 是 Java 特有类型（pi 抛裸 `Error`）；含 §5.4 的
  字面量偏差（`1.0` 保留小数、`pi mcp add` 的键序按 record 组件序）。
- **B185**：`OAuthMetadataParsers.optionalBoolean` 的 SPOTBUGS 排除（发现 2）。
- **B186**：mcp 模块的 `verify` 在包 ⑥/⑦ 从未跑过（发现 2 的通用化）。
- **B187**：包④ `InMemoryTransportTest` 的抢跑断言（发现 4 已修）。

以上 B181–B187 已登记 `docs/04`。
