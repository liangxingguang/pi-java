# coding-agent 批 18 设计稿：E6 包管理器第一批——npm/git 源＋resolve 机制（资源优先级/过滤/忽略文件）

【背景摘要】本批是 coding-agent 对齐批次的第 18 批（总账 docs/20 §3 序 17），目标是
map/04 的 **E6 包管理器域**——全模块唯一零对齐域（对齐 0/16，加权 16.1%）。批 17
闭环后模块账面 354.0/485 ≈ 73.0%。本批落地 pi 包管理器的**源解析与资源解析内核**：
npm 源、git 源、三档作用域、资源过滤、五档资源优先级、忽略文件、进度回调，以及
settings 的 `packages`/`npmCommand` 两键（批 11 留白）。CLI 子命令面
（install/remove/update/list/config）与 `settings.packages` 写回链归批 19（序 18）。

**拍板变量②已拍板（2026-10-24，用户裁决）**：方案 D——resolve 机制逐字＋git 源真实
落地（spawn 系统 git，pi 逐字）＋npm 源真实落地改实现（HTTP registry API 直拉，
保留 spawn npm CLI 的扩展接口，本批先实现 HTTP 臂）。详见 §6 裁决①②③。

术语（首现释义）：

- **resolve**：pi `package-manager.ts` 的资源解析入口。输入＝settings 的包表与四类
  资源数组＋自动发现目录，输出＝四类资源的有序路径表（带 enabled 标记与来源元数据）。
- **包（package）**：npm 包 / git 仓库 / 本地目录，内部可含四类资源：扩展
  （pi 为 `.ts/.js`，pi-java 为 `.jar`）、技能（`.md`/`SKILL.md`）、提示模板（`.md`）、
  主题（`.json`）。
- **五档优先级**：同名资源冲突时「先到先得」的排序依据（project 设置条目 >
  project 自动发现 > user 设置条目 > user 自动发现 > 包 > 内置）。
- **临时作用域（temporary）**：`-e`/`--skill` 等 CLI 显式源的作用域，装进
  `agentDir/tmp/extensions/` 下的哈希目录，不写 settings。
- **narrow gap / 窄差（D 编号）**：与 pi 无法完全对齐、登记在册的差异。

---

## §0 基线与预期翻动

| 指标 | 前（批 17 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485） | 354.0 ≈ 73.0% | **370.0 ≈ 76.3%**（+16.0） |
| E6 域（Σ 31） | 5.0 ≈ 16.1%（对齐 0/存疑 5/缺失 11） | **17.0 ≈ 54.8%**（对齐 7/存疑 4/缺失 5） |
| E2 域（Σ 84） | 76.5 ≈ 91.1% | **80.5 ≈ 95.8%**（E2-20/E2-26 翻，缺失清零） |
| 全仓（Σ 1379） | 1,035.5 ≈ 75.1% | **1,051.5 ≈ 76.3%**（+16.0） |

翻动行（9 行，+16.0）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|
| E6-3 npm 源（版本/range/pinning） | 3 | 缺失 | 对齐 | T3 |
| E6-4 git 源 | 2 | 缺失 | 对齐 | T4 |
| E6-5 作用域 user/project/temporary | 2 | 存疑 | 对齐 | T2+T5 |
| E6-6 资源过滤 | 2 | 缺失 | 对齐 | T5 |
| E6-7 资源优先级（5 档） | 2 | 缺失 | 对齐 | T5+T6 |
| E6-8 忽略文件 | 1 | 缺失 | 对齐 | T5 |
| E6-9 进度回调 `onProgress` | 1 | 缺失 | 对齐 | T5（发射面；消费面随批 19 CLI） |
| E2-20 `npmCommand` | 2 | 缺失 | 对齐 | T1（声明＋解析＋校验面；消费窄差 D104） |
| E2-26 `packages` | 2 | 缺失 | 对齐 | T1（声明＋解析）＋T5（resolve 消费） |

不动行：E6-1/E6-2（local/http install 存疑，CLI 面随批 19 重裁）、E6-10/11/12
（update 子命令，批 19）、E6-13（config TUI）、E6-14（settings 写回链，批 19）、
E6-15（pi manifest 读取——**机制随 T5 落地**，但该行的 CLI/展示消费面在批 19，
本批改注不翻行，同 B214 先例）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

pi 侧主体是单文件 `packages/coding-agent/src/core/package-manager.ts`（2,760 行），
配 `utils/git.ts`（226 行，git URL 解析）、`core/pi-manifest.ts`（35 行）、
`core/settings-schema.ts`（schema）与消费面 `core/resource-loader.ts`（1,287 行）。

### 1.1 源解析三型：`parseSource`（package-manager.ts:1480-1505）

```ts
private parseSource(source: string): ParsedSource {
    if (source.startsWith("npm:")) {
        const spec = source.slice("npm:".length).trim();
        const { name, version } = this.parseNpmSpec(spec);
        return { type: "npm", spec, name, version,
                 range: getNpmVersionRange(version), pinned: isExactNpmVersion(version) };
    }
    if (isLocalPath(source)) { return { type: "local", path: source }; }
    const gitParsed = parseGitUrl(source);
    if (gitParsed) { return gitParsed; }
    return { type: "local", path: source };
}
```

- **npm 形**：`npm:<name>[@<version>]`。`parseNpmSpec`（:1765-1773）用正则
  `/^(@?[^@]+(?:\/[^@]+)?)(?:@(.+))?$/` 拆 name/version（scoped 名 `@org/pkg` 的
  首个 `@` 不算分隔）。`pinned`＝version 是精确 semver（`valid()` 非 null）；
  `range`＝`validRange(version)`（range 形如 `^1.2` 也入 range）。
- **local 形**：`isLocalPath`（utils/paths.ts:50-65）——除 `npm:`/`git:`/`github:`/
  `http:`/`https:`/`ssh:`/`builtin:` 前缀外一律视为本地路径（`file:` 也算本地）。
- **git 形**：`parseGitUrl`（utils/git.ts:172-226）——无 `git:` 前缀时只收显式协议
  URL（`https?|ssh|git://`）；有 `git:` 前缀时收历史简写形（`git:github.com/u/r`、
  scp-like `git@host:u/r`）。先走 `hosted-git-info`（知名宿主规范化），回落
  `parseGenericGitUrl`。`@<ref>` 后缀拆出 ref；`pinned = Boolean(ref)`。
  `buildGitSource`（:104-124）校验：path 去 `.git` 尾、至少两段、host/path 无
  `\0`、`\`、前导 `/`、`..` 段（解码后再校一遍，防 `%2e%2e`）。
- **恒定 fallback**：git 解析失败回 local（不报错）。

包身份（去版本/ref 的查重键，`getPackageIdentity` :1721-1735）：

```ts
if (parsed.type === "npm") return `npm:${parsed.name}`;
if (parsed.type === "git") return `git:${parsed.host}/${parsed.path}`;  // SSH/HTTPS 归一
// local：scope 给了就按 scope 基目录解析，否则按 cwd
```

### 1.2 settings 两键 schema（settings-schema.ts）

`PackageSourceSchema`（:170-190）——string 与 object 二选一：

```ts
const PackageSourceSchema = Type.Union([
    Type.String({ description: "Load all resources from the package." }),
    Type.Object({
        source: Type.String(),
        autoload: Type.Optional(Type.Boolean({
            description: "When false, start empty and only apply explicit resource patterns." })),
        extensions: Type.Optional(Type.Array(Type.String())),
        skills:     Type.Optional(Type.Array(Type.String())),
        prompts:    Type.Optional(Type.Array(Type.String())),
        themes:     Type.Optional(Type.Array(Type.String())),
    }),
], { description: "Package source for npm or git packages. ..." });
```

`npmCommand`（:268-273）：

```ts
npmCommand: Type.Optional(Type.Array(Type.String(), {
    description: 'Command used for npm package lookup and installation, in argv form such as ["mise", "exec", "node@20", "--", "npm"].',
})),
```

`packages`（:295-299）：`Type.Optional(Type.Array(PackageSourceSchema))`。

`getNpmCommand`（package-manager.ts:1781-1791）：未配置 ⇒ `{ command: "npm", args: [] }`；
配置了但首项为空 ⇒ 抛 `Invalid npmCommand: first array entry must be a non-empty command`
（**模型可见文案，逐字**）。

### 1.3 npm 安装面（pi 实现，供对照；java 改 HTTP 实现见 §3 T3）

- 安装根（`getNpmInstallRoot` :2085-2094）：user＝`agentDir/npm`；project＝
  `<cwd>/.pi/npm`（过信任门）；temporary＝`tmp/extensions/` 下哈希目录。
- 包装目录（`getManagedNpmInstallPath` :2126-2135）：`<root>/node_modules/<name>`。
- user 作用域有**遗留全局回落**（`getNpmInstallPath` :2145-2152 +
  `getLegacyGlobalNpmInstallPath` :2137-2143）：managed 路径不存在时查
  `npm root -g`/pnpm 全局路径——**java HTTP 实现无此概念，登窄差**。
- 安装（`installNpm` :1868-1872）：`ensureNpmProject`（写 `package.json`
  `{name:"pi-extensions",private:true}` + `.gitignore`＝`*\n!.gitignore\n`）后
  spawn `npm install <spec> --prefix <root> --legacy-peer-deps`。
- 版本查询（`getLatestNpmVersion` :1545-1564）：spawn `npm view <spec> version --json`；
  返回串直接用，返回数组则 `maxSatisfying(versions, range)`，无 range 按 `rcompare`
  取首。**离线/失败时 update 判定回退为「需要更新」**（`shouldUpdateNpmSource`
  :1188-1194 的 catch 臂）——保留旧行为。
- 已装版本读取（`getInstalledNpmVersion` :1533-1543）：读安装目录
  `package.json` 的 `version`（strip BOM，解析失败 ⇒ undefined）。
- resolve 时的一致性门（`installedNpmMatchesConfiguredVersion` :1507-1513）：
  已装版本不满足配置的 range ⇒ 重装。

### 1.4 git 安装面（spawn 命令表，逐字对位目标）

| pi 方法 | 行号 | spawn 命令 |
|---|---|---|
| `installGit` | :1891-1923 | `git clone <repo> <dir>`；有 ref ⇒ `git checkout <ref>`；有 package.json ⇒ 跑 npm 依赖安装（**java 裁掉**，窄差 D103）。失败 ⇒ 删目标目录＋清父空目录 |
| `ensureGitRef` | :1992-2018 | `git fetch <args>` → `rev-parse HEAD` vs `rev-parse <ref>^{commit}`；相等且有 marker ⇒ 修复臂；不等 ⇒ 写 marker 文件 → `git reset --hard <ref>^{commit}` → `git clean -fdx` → 删 marker |
| `getLocalGitUpdateTarget` | :1601-1662 | `rev-parse --abbrev-ref @{upstream}` → 非 `origin/` 开头抛错；取 `@{upstream}` 的 head；fetchArgs＝`fetch --prune --no-tags origin +refs/heads/<b>:refs/remotes/origin/<b>`。catch 臂：`remote set-head origin -a` → `rev-parse origin/HEAD` → `symbolic-ref refs/remotes/origin/HEAD`；都失败 ⇒ fetchArgs＝`fetch --prune --no-tags origin +HEAD:refs/remotes/origin/HEAD` |
| `getRemoteGitHead` | :1583-1599 | `ls-remote origin <upstreamRef>` 正则 `^([0-9a-f]{40})\s+`；回落 `ls-remote origin HEAD` 正则 `^([0-9a-f]{40})\s+HEAD$`；失败抛 `Failed to determine remote HEAD` |
| `removeGit` | :2033-2038 | 删目录＋删 marker＋`pruneEmptyGitParents`（:2040-2060 向上删空父目录直到 installRoot） |
| `refreshTemporaryGitSource` | :2020-2031 | temporary＋未 pin＋非离线 ⇒ 包一层 `withProgress("pull", …)` 跑 `updateGit`；**失败静默保留缓存检出**（catch 空臂） |

公共约束：

- marker 路径（:1971-1973）：`<parent>/.<basename>.pi-update-incomplete`。
- git 安装根（:2166-2175）：user＝`agentDir/git`，project＝`<cwd>/.pi/git`（过信任门），
  目标路径＝`<root>/<host>/<path>`；temporary＝`tmp/extensions/git-<host>/<hash8>/<path>`，
  hash＝`sha256("git-<host>-<path>[@<ref>]")` 前 8 位（:2177-2184）。
- `resolveManagedPath`（:2186-2193）：解析结果必须仍在 install root 内，否则抛
  `Refusing to use path outside package install root: <path>`（**模型可见，逐字**）。
- 远程命令统一带 `GIT_TERMINAL_PROMPT=0`（:1685-1687），网络超时 10s
  （`NETWORK_TIMEOUT_MS` :50）。
- 离线门（:54-60）：`PI_OFFLINE` ∈ {`1`,`true`,`yes`}（大小写不敏感）⇒ 跳过一切
  网络动作。**java 已有同款判定**（`ModelCatalogRefresh.offlineMode`，
  ModelCatalogRefresh.java:62/76），复用。

### 1.5 resolve() 总装（:920-989）与五档优先级

```ts
async resolve(onMissing?): Promise<ResolvedPaths> {
    const accumulator = this.createAccumulator();
    // ① 包：project 先入（cwd 资源赢冲突），按身份去重（dedupePackages :1742-1763，
    //    project 赢 user；project autoload=false 是 user 条目的 delta ⇒ 两条都留）
    // ② settings 四类数组（project 条目 → projectBaseDir，user 条目 → agentDir）
    // ③ 自动发现目录（addAutoDiscoveredResources :2413-2577）
    // ④ 内置扩展（builtin:<name>，可被 settings.extensions 的 +/-/! 覆写）
    return this.toResolvedPaths(accumulator);
}
```

五档优先级（`resourcePrecedenceRank` :171-185，**逐字对位目标**）：

```ts
function resourcePrecedenceRank(m: PathMetadata): number {
    if (m.source === "builtin") return 5;
    if (m.origin === "package") return 4;
    const scopeBase = m.scope === "project" ? 0 : 2;
    return scopeBase + (m.source === "local" ? 0 : 1);
}
// 0 project+settings 条目 / 1 project+自动发现 / 2 user+settings 条目
// 3 user+自动发现 / 4 包 / 5 内置
```

输出整形（`toResolvedPaths` :2637-2663）：每类按 rank 升序排，再按
`canonicalizePath`（realpath，失败回原串）去重——**同路径先到者胜**。
`addResource`（:2616-2626）本身也是 first-wins（`if (!map.has(path))`）。

onMissing 臂（`resolvePackageSources` :1302-1313）：包装未安装时——离线 ⇒ 跳过；
无回调 ⇒ 直接装；回调返回 `skip` ⇒ 跳过，`error` ⇒ 抛 `Missing source: <source>`
（**模型可见，逐字**），`install` ⇒ 装。

### 1.6 资源过滤（PackageFilter 与 pattern 代数）

条目四形（`applyPatterns` :745-791 注释逐字）：

- 普通 pattern：命中即收（无 include 时默认全收）；
- `!pattern`：排除；
- `+path`：强制收（精确路径，压过排除）；
- `-path`：强制排除（精确路径，压过强制收）。

匹配（`matchesAnyPattern` :661-687）：对「相对基目录路径 / 文件名 / 绝对路径」三个
形态跑 `minimatch`；`SKILL.md` 额外对父目录三形态再跑一遍。
`matchesAnyExactPattern`（:694-712）：去 `./` 前缀后只做**字符串等值**（相对路径、
绝对路径、SKILL.md 父目录两形态）。

`autoload=false` 臂（`applyAutoloadDisabledPatterns` :793-810）：不产路径集，产
**路径→enabled 布尔映射**（`+`/普通＝true，`!`/`-`＝false；`+`/`-` 精确、其他
pattern）；`applyPackageDeltaFilter`（:2313-2329）只写映射里点名的条目（delta 语义：
project 条目覆写 user 同包的局部资源开关）。

包资源收集（`collectPackageResources` :2214-2263）：有 filter ⇒ 逐类型走
delta/pattern/default 三支；否则先读 pi manifest（`package.json` 的 `pi` 字段，
`pi-manifest.ts` 全文 35 行逐字可移），manifest 有 ⇒ 按 manifest 条目收
（`addManifestEntries` :2358-2376，条目里的 override pattern 再过滤）；manifest 无
⇒ 约定目录 `<root>/{extensions,skills,prompts,themes}` 存在即全收；四目录都不存在
⇒ 返回 false（local 源此时把目录本身当扩展路径收，`resolveLocalExtensionSource`
:1360-1390）。

### 1.7 忽略文件与目录扫描族

- 忽略文件名三件（:227）：`.gitignore` / `.ignore` / `.fdignore`。
- `addIgnoreRules`（:258-281）：每个目录把三件文件的规则**拍平进同一个 matcher**，
  相对 root 的子目录规则加 `dir/` 前缀（`prefixIgnorePattern` :233-256：`!` 否定、
  `\!`/`\#` 转义、前导 `/` 剥掉）。
- `collectFiles`（:315-365）：递归扫，跳过 dot 开头条目与 `node_modules`；目录带
  `/` 尾查忽略；符号链接 stat 失败跳过；全函数 try/catch 静默。
- `collectSkillEntries`（:373-448）：含 `SKILL.md` 的目录即技能根、不再下钻
  （pi 模式只收 root 直属 `.md`，agents 模式只收子目录里的 `.md`——`mode` 参数
  :428-435）；目录含 SKILL.md 时其本身 `.md` 文件不重复收。
- `collectAutoExtensionEntries`（:593-645）：目录本身有 `resolveExtensionEntries`
  （manifest extensions 条目 → `index.ts`/`index.js` 回落，:563-591）就用它；否则
  扫直属 `.ts/.js` 文件＋子目录的显式入口。
- **java 翻译点**：扩展文件型 `.ts/.js` ⇒ `.jar`（裁决⑤）；skills/prompts/themes
  三型纯数据逐字。

### 1.8 消费面（resource-loader.ts:529-644）

`reload()`：`resolve()` → 按 `enabled` 过滤 → 各类路径喂扩展加载器/技能加载/
模板加载/主题加载；CLI 源走 `resolveExtensionSources(additionalExtensionPaths,
{temporary: true})` 并入最前；`noExtensions/noSkills/…` 各开关在合并点裁剪。
元数据按路径留存（`resourceMetadataByPath`）供 sourceInfo/警告用（本批不移植该表，
见 §4）。

### 1.9 进度回调（:95-101, 899-918）

```ts
export interface ProgressEvent {
    type: "start" | "progress" | "complete" | "error";
    action: "install" | "remove" | "update" | "clone" | "pull";
    source: string; message?: string;
}
```

`withProgress`（:903-918）：操作前发 `start`，成发 `complete`，转发 `error`
（message＝异常 message）并把异常继续抛。pi 侧 `progress` 型事件本文件无发射点
（留给 CLI 层）——java 同（只实装 start/complete/error 三型发射）。

---

## §2 java 侧现状（取证 2026-10-24，main f2f729f2）

1. **`ExtensionPackageManager`**（extension/ExtensionPackageManager.java，200 行）：
   只有「本地 JAR 复制 / http(s) 下载到扩展目录」两源，`install/remove/update/list`
   四方法；无 npm/git/temporary/过滤/优先级概念。
2. **资源发现散装**：技能＝`SessionSetup.discoverSkills`（SessionSetup.java:162-176 →
   `SkillDiscovery.discoverAll`，**USER → PROJECT 先到先得**——与 pi 五档优先级
   **反向**，见裁决⑨）；提示模板＝`SessionSetup.loadPromptTemplates`（:144-159）；
   主题＝扩展贡献候选；扩展＝`AgentSession.loadExtensions`（AgentSession.java:
   2471-2591）的 ServiceLoader＋全局/项目扩展目录 JAR＋`-e` 显式三路。
3. **settings 四类数组键空转**：`Settings.extensions/skills/prompts/themes`
   （Settings.java:49-52）字段在，但会话装配零消费（只有 `ConfigCommand` 的
   enable/disable 读写，ConfigCommand.java:107/116）。`packages`/`npmCommand` 无字段。
4. **信任门已有**：`SettingsManager.isProjectTrusted()`（SettingsManager.java:128），
   pi `assertProjectTrustedForScope`（:1775-1779）有对位基础。
5. **忽略过滤已有基建**：agent-core `ToolIgnoreFilter`（JGit `IgnoreNode`，
   层级 .gitignore 语义）——技能扫描在用；pi 包管理器是「拍平加前缀」模型
   （§1.7），两者边角语义不同（窄差 D107）。
6. **glob 基建**：ai 模块 `ModelGlob` 是 minimatch 子集但为模型 id 定制；包管理器的
   pattern 匹配需路径向 minimatch 子集（T5 自写 `MinimatchLite`，裁决⑧）。
7. **离线判定已有**：`ModelCatalogRefresh.offlineMode`（PI_OFFLINE 同款）。
8. **`SessionReload`**（批 16 七步序）已重放 settings/skills/prompts——resolve 接入后
   随其重放自然覆盖包资源。

---

## §3 任务分解

> 包放置：新代码集中 `com.pijava.coding.agent.pkg`（coding-agent 模块新包），
> 不挤 `extension` 包（ExtensionPackageManager 是 CLI 面的旧实现，批 19 再收编）。
> settings 字段/accessor 进 `core`。每文件 ≤500 行。

### T1：settings 两键（E2-20/E2-26，~180 行＋测试）

1. `Settings` 加两字段：`public List<PackageSource> packages;` 与
   `public List<String> npmCommand;`。
2. 新 `core/PackageSource.java`：pi union 形（字符串｜对象）的 java 表达——
   `record PackageSource(String source, Boolean autoload, List<String> extensions,
   List<String> skills, List<String> prompts, List<String> themes)`，字符串形在
   Jackson 反序列化层归一成对象形（`@JsonCreator` 委托 + `@JsonValue` 序列化回
   字符串当且仅当初始为裸串——保持写回不胀形，批 19 写回链依赖此）。
3. `SettingsAccessors` 加 `getPackages()/getNpmCommand()`（声明面逐字：
   `getNpmCommand` 返回防御性拷贝，pi settings-manager.ts:1014 对位）。
4. 校验：`PackageManager` 构造/解析处的 `npmCommand` 首项空 ⇒ 抛
   `Invalid npmCommand: first array entry must be a non-empty command`（逐字）。
5. 消费面：packages → T5 resolve 读取；npmCommand → T3 的 `NpmResolver` 工厂读取
   （HTTP 实现下**不消费命令本体**，窄差 D104）。

### T2：源解析三型（E6-5 一半，~260 行＋测试）

1. `pkg/ParsedSource.java`：`sealed interface ParsedSource` ＋三 record——
   `NpmSource(String spec, String name, String version, String range, boolean pinned)`、
   `GitSource(String repo, String host, String path, String ref, boolean pinned)`、
   `LocalSource(String path)`。（ADT 规范：变体携带不同字段 ⇒ sealed+record。）
2. `parseSource` 逐字对位（§1.1），含 `parseNpmSpec` 正则逐字、`isLocalPath`
   前缀表逐字（`builtin:` 在内）。
3. `pkg/GitUrlParser.java`：`parseGitUrl` 移植——scp-like/`https?`/`ssh`/`git://`/
   `git:` 前缀历史简写、`@ref` 拆分、`buildGitSource` 校验（`\0`、`\`、前导 `/`、
   `..` 段、URL 解码后复审）逐字。**hosted-git-info 的知名宿主表裁为通用 URL
   解析**（pi 对它只做 domain/user/project 规范化，通用臂 `parseGenericGitUrl`
   已覆盖同语义）——冷僻宿主简写形（如 `bitbucket:u/r`）不支持，窄差 D105。
4. 身份/查重：`getPackageIdentity`、`dedupePackages`（project 赢 user、
   autoload=false delta 双留）、`packageSourcesMatch`、
   `normalizePackageSourceForSettings`（写回链工具，批 19 用，本批随测）。
5. 作用域：`enum SourceScope { USER, PROJECT, TEMPORARY }`（纯常量闭集 ⇒ enum，
   `@JsonValue` 不需要——不进 JSON）；`getBaseDirForScope`＋信任门
   （`assertProjectTrustedForScope` 逐字，复用 `SettingsManager.isProjectTrusted`）。

### T3：npm 源 HTTP 实现（E6-3，~520 行＋测试）

1. **seam**（用户裁决③）：`pkg/NpmResolver` 接口——
   `List<String> versions(String name)`、`String latestSatisfying(String name, String range)`、
   `Path install(NpmSource source, Path installRoot)`。默认实现 `HttpNpmResolver`；
   将来 `SpawnNpmResolver`（pi 逐字的 npm CLI 臂）实现同一接口插入。
2. `HttpNpmResolver`：
   - 元数据：`GET <registry>/<name>`，请求头
     `Accept: application/vnd.npm.install-v1+json`（精简 packument），JDK HttpClient，
     10s 超时（pi `NETWORK_TIMEOUT_MS` 对位）。
   - registry 来源裁决④：`NPM_CONFIG_REGISTRY` 环境变量 > 项目/用户 `.npmrc` 的
     `registry=` 键（简单 `key=value` 解析）> 默认 `https://registry.npmjs.org`。
   - 版本选择：`latestSatisfying`＝有 range 取 maxSatisfying，无则取最大版
     （pi `rcompare` 首项对位）；**pinned（精确版本）不查元数据直接下载**。
   - 下载：`versions[<v>].dist.tarball` → 下载 `.tgz` → 校验 `dist.integrity`
     （SRI `sha512-<base64>`，JDK MessageDigest；缺 integrity 跳过校验并记
     诊断）→ 解 tar.gz 到 `<installRoot>/node_modules/<name>`（tar 内统一
     `package/` 前缀剥一层）。
   - tar 解压：引入 `org.apache.commons:commons-compress`（裁决⑦；ustar/pax/gnu
     长名自写风险高，不值）。
   - 写安装目录 `package.json`（`{"name":…,"version":…,"private":true}`）——
     pi `getInstalledNpmVersion` 的读取面对齐（§1.3）。
   - `ensureNpmProject` 对位：installRoot 建目录＋写 `.gitignore`（`*\n!.gitignore\n`
     逐字）；pi 的 `markPathIgnoredByCloudSync`（macOS xattr）无对位 ⇒ no-op，
     窄差 D108。
3. `pkg/Semver.java`＋`SemverRange.java`：npm-semver 子集——`valid`（精确三元组
   +可选 prerelease/build）、`validRange`、`satisfies`（精确/`^`/`~`/`*`/`x`/
   比较符集/空格 AND/`||` OR）、`gt`/`compare`（prerelease 排序按 semver §11）、
   `maxSatisfying`/`rcompare`。hyphen range（`1.2 - 2.3`）不支援 ⇒ 窄差 D106。
   （先验证 `com.vdurmont:semver4j` 的 NPM range 覆盖；达标则引库替代自写——
   裁决⑥。）
4. resolve 时一致性门 `installedNpmMatchesConfiguredVersion` 逐字（§1.3）。
5. 离线门复用 `ModelCatalogRefresh.offlineMode` 同款判定。

### T4：git 源 spawn 实现（E6-4，~420 行＋测试）

1. `pkg/GitRunner.java`：ProcessBuilder 包装——capture（stdout/stderr 合并回报错
   文案）、10s 网络超时（超时 kill，`… timed out after 10000ms` 文案对位 pi
   :2719）、远程命令统一 env `GIT_TERMINAL_PROMPT=0`、失败文案
   `git <args> failed with code <n>: <stderr|stdout>`（pi :2727 对位，模型可见面
   逐字）。
2. `pkg/GitPackageStore.java`：`installGit`/`ensureGitRef`/`getLocalGitUpdateTarget`/
   `getGitUpstreamRef`/`getRemoteGitHead`/`updateGit`/`removeGit`/
   `pruneEmptyGitParents`/marker 文件/`cleanAndInstallGitDependencies`/
   `refreshTemporaryGitSource`——按 §1.4 命令表逐字对位。
3. **裁 npm 依赖安装臂**（裁决⑩）：pi 在 clone/更新后对有 package.json 的包跑
   `npm install --omit=dev …`——java 扩展是 JAR，无 node 依赖概念 ⇒ 跳过，
   窄差 D103。
4. temporary 检出目录命名：sha256 前 8 位逐字（§1.4）；`getExtensionTempFolder`
   的 `0700` 在 Windows 无存 ACL 对位 ⇒ best-effort（POSIX 属性支持时才设），
   窄差 D109。
5. `resolveManagedPath` 越根防护逐字（§1.4 文案）。

### T5：resolve() 机制（E6-5/6/7/8/9，~850 行＋测试）

1. 数据形：`pkg/PathMetadata.java`（record：source/scope/origin/baseDir/packageRoot）、
   `pkg/ResolvedResource.java`（path/enabled/metadata）、`pkg/ResolvedPaths.java`
   （四类列表）、`pkg/ResourceAccumulator.java`（四张 LinkedHashMap——**保序**，
   pi Map 的插入序语义）、`pkg/ProgressEvent.java`＋`ProgressCallback`。
2. `pkg/PackageManager.java`（主类，pi `DefaultPackageManager` 对位）：
   `resolve()`/`resolveExtensionSources()`/`getInstalledPath()`/
   `setProgressCallback()`/`withProgress`/`installParsedSource`（resolve 缺包臂）。
   `addResource` first-wins、`toResolvedPaths` 排序+canonicalize 去重逐字
   （java 对位 `Path.toRealPath(NOFOLLOW_LINKS)` 失败回原串）。
3. 过滤代数：`pkg/ResourcePatterns.java`——`applyPatterns`/
   `applyAutoloadDisabledPatterns`/`isEnabledByOverrides`/`matchesAnyPattern`/
   `matchesAnyExactPattern`/`splitPatterns`/`isPattern`/`isOverridePattern`/
   `hasGlobPattern` 逐字（§1.6）。
4. `pkg/MinimatchLite.java`（裁决⑧）：路径向 minimatch 子集（`*` 不跨 `/`、
   `**` 跨、`?`、`{a,b}` 展开、`.` 开头段默认不匹配——pi minimatch 默认
   `dot:false`）；extglob/字符类等冷僻形不支持 ⇒ 窄差 D110。
5. 扫描族 `pkg/PackageResourceScanner.java`：`collectFiles`/`collectSkillEntries`
   （pi/agents 两 mode）/`collectAutoExtensionEntries`（**扩展改扫 `.jar`**，
   `resolveExtensionEntries` 的 index 臂翻译为 `index.jar`——裁决⑤）/
   `collectAutoPromptEntries`/`collectAutoThemeEntries`/`collectResourceFiles`/
   `collectFilesFromPaths`/`expandPackageGlob`。
6. 忽略：`pkg/PackageIgnore.java`——三件文件名逐字、**拍平加前缀模型**逐字
   （`prefixIgnorePattern`），matcher 复用 JGit `IgnoreNode` 的逐条规则解析
   （IgnoreNode.parse 逐行进同一 node ⇒ 拍平语义可保），语义边角差登 D107。
7. pi manifest：`pkg/PiManifest.java`——pi-manifest.ts 35 行逐字（package.json 的
   `pi` 字段、四资源键、非对象/解析失败 ⇒ null）。
8. 自动发现臂：`addAutoDiscoveredResources` 对位——user/project 四目录＋
   `~/.agents/skills`＋project 祖先 `.agents/skills`（`collectAncestorAgentsSkillDirs`
   的 git 根截断逐字）；**project 臂过信任门**（pi :2478 对位）。java 侧
   `CONFIG_DIR_NAME` 用既有 `.pi-java`（既定翻译，不新登窄差）。
9. 内置扩展臂（:971-986）：`builtin:<name>` 路径生成＋override 门逐字；java 的
   内置扩展名表＝`ExtensionManager` 已知内置（本批消费侧只过滤不加载，见 T6）。
10. `onProgress`：`ProgressEvent` 三型（start/complete/error）在
    install/remove/git refresh 的发射点全接（E6-9 发射面）；`progress` 型同 pi
    无发射点。

### T6：会话装配接线（E6-7 的消费半，~300 行＋测试）

1. `SessionSetup`/`AgentSession.loadExtensions` 接入 `PackageManager.resolve()`：
   - 技能：resolve 输出（enabled 臂）的 SKILL.md/`.md` 路径 → `SkillDiscovery`
     的显式路径车道按序喂入（先到先得去重沿用现有 `LoadSkillsResult.dedupe`）；
     **旧 USER→PROJECT 直扫臂退役**（由 resolve 的自动发现臂接管，裁决⑨）。
   - 提示模板/主题：同形接入（resolve 输出 → `PromptTemplates.load` 显式车道 /
     主题候选表）。
   - 扩展：resolve 输出的 `.jar` 路径 → `ExtensionManager.loadJar`（替代现
     「扫全局/项目扩展目录」两臂）；`builtin:` 前缀路径在消费点过滤（java 内置
     扩展装配走既有 ServiceLoader 路，不在 resolve 输出消费）。
   - `--no-extensions/--no-skills/--no-prompt-templates` 各开关在合并点裁剪
     （pi resource-loader :577-644 的合并臂对位）。
2. `-e/--extension` 显式源改走 `resolveExtensionSources(temporary)`：本地路径
   原样过；npm:/git: 源自此可写进 `-e`（temporary 检出）。
3. `/reload`：`SessionReload` 重放 resolve（pi resource-loader.reload 对位）。
4. `resolve()` 的 `onMissing` 回调本批恒 null（pi 同：无回调 ⇒ 缺包直装）；
   TUI 三选询问臂（install/skip/error）随批 19 CLI/TUI 面。

---

## §4 不做项与重排

- **批 19（序 18）**：CLI 子命令面重裁（`PackageCommand` 接新 PackageManager：
  install/remove/update/list/config、E6-1/E6-2/E6-10/11/12/13/16 行重判）、
  `settings.packages` 写回链（E6-14：`installAndPersist`/`removeAndPersist`/
  `addSourceToSettings`/`removeSourceFromSettings`/`setPackages`/`setProjectPackages`）、
  `update` 机制（`updateConfiguredSources`/`checkForAvailableUpdates`/并发 4 闸）、
  onMissing 的 TUI 三选询问、onProgress 的 CLI/TUI 消费面、E6-15 行终判。
- **本批不做**：`resourceMetadataByPath` 表与 sourceInfo/包警告
  （`collectExtensionPackageWarnings`）——消费面在批 19/后续；
  self-update（E6-10）java 无安装形态（docs/20 R29 挂起注记）；codemode 等 R5 面。
- **遗留全局 npm 回落**（pi `getLegacyGlobalNpmInstallPath`/`getGlobalNpmRoot`/
  pnpm 特判）：HTTP 实现无对位 ⇒ 窄差 D102，不重排。

---

## §5 窄差登记（接续 D101；台账格式随闭环入 docs/04）

| 编号 | 条目 | 性质 |
|---|---|---|
| D102 | 无 `npm root -g`/pnpm 全局遗留回落（pi :2137-2152）——HTTP 实现只认 managed 安装根 | 内部 |
| D103 | git 包不执行 npm 依赖安装（pi :1914-1917/:1985-1988）——java 扩展无 node 依赖概念 | 内部 |
| D104 | `npmCommand` 键在 HTTP 实现下不消费命令本体（不支持 pnpm/bun 包装与 registry 推导） | 模型可见（键被忽略） |
| D105 | `parseGitUrl` 无 hosted-git-info 知名宿主表——`bitbucket:`/`gist:` 等简写不支持 | 模型可见（源解析） |
| ~~D106~~ | ~~SemverRange 不支持 hyphen range~~ **改注销号（闭环实测）**：semver4j NPM 模式覆盖 hyphen/`^`/`~`/x/`\|\|`/比较符集（`SemverRangeTest` 真值表钉） ⇒ 无缺口 | 内部 |
| D112 | `-builtin:name` 关停标记只算不消费（resolve 内置臂产出 enabled=false，消费侧过滤后 ServiceLoader 恒全装）——生产零内置扩展，纯理论缺口；MCP 工厂批落地时重裁 | 内部 |
| D107 | 忽略语义：pi `ignore` 包拍平加前缀 vs java JGit IgnoreNode 层级解析的边角差 | 内部 |
| D108 | `markPathIgnoredByCloudSync`（macOS xattr）无对位，no-op | 内部 |
| D109 | temporary 目录 `0700` 权限 Windows 无 ACL 对位，best-effort | 内部 |
| D110 | `MinimatchLite` 子集：extglob/POSIX 字符类等 minimatch 冷僻形不支持 | 内部 |
| D111 | 包内扩展扫 `.jar`＋`index.jar` 回落（pi 扫 `.ts/.js`＋`index.ts/js`）——结构性，不可消除 | 模型可见（包约定） |

---

## §6 裁决

> 工单版三段式（场景/决定/风险）。①②③为用户拍板（2026-10-24 问答）。

**裁决①（总体方向＝方案 D）**
- 【场景】pi 的 npm 源装的是 TS 扩展包，pi-java 跑不了 TS；逐字移植 npm CLI
  依赖机器装 node，与「纯 Java 复刻」目标冲突；但不装远程源则 E6 域永远零对齐。
- 【决定】resolve 机制（来源无关）逐字；git 源真实落地；npm 源真实落地但改
  HTTP registry 实现。批 18/19 保持两批。
- 【风险/代价】[模型可见] 包内扩展约定从 `.ts/.js` 变 `.jar`（D111）；npm 生态里
  现存的 pi TS 包对 java 只贡献 skills/prompts/themes 数据资源。

**裁决②（git 实现＝spawn 系统 git）**
- 【场景】JGit（agentscope-java 先例，纯 Java）与 spawn（pi 逐字）两路。
- 【决定】spawn 系统 git：命令面/错误文案/认证链（系统 gitconfig、SSH）与 pi
  完全一致，零新依赖。
- 【风险/代价】[内部] 依赖外部环境有 git；测试要本地 `file://` 裸仓夹具。
  Windows 无 git 环境下 git 源报错（与 pi 在无 git 环境同结局）。

**裁决③（npm 实现＝HTTP registry API＋保留 spawn 扩展接口）**
- 【场景】HTTP 直拉不依赖 node 生态、可测性好；但 pi 的 `npmCommand` 键语义
  （换 pnpm/bun/包装器）只有在 spawn 臂下才完整。
- 【决定】定义 `NpmResolver` 接口（versions/latestSatisfying/install 三方法），
  本批实装 `HttpNpmResolver`；`SpawnNpmResolver` 留作未来实现，接口预留。
- 【风险/代价】[模型可见] `npmCommand` 键声明解析但 HTTP 臂不消费（D104）；
  registry 镜像改走 `NPM_CONFIG_REGISTRY`/`.npmrc`（裁决④）。

**裁决④（registry 镜像配置来源）**
- 【场景】pi 靠 npm CLI 自动读 `.npmrc`（镜像对企业/国内用户是刚需）；HTTP 实现
  没有 npm CLI 代读。
- 【决定】自读：`NPM_CONFIG_REGISTRY` 环境变量 > 项目 `.npmrc` > 用户 `~/.npmrc`
  的 `registry=` 键（简单 key=value 解析）> 默认 `https://registry.npmjs.org`。
- 【风险/代价】[内部] `.npmrc` 的 scope 化 registry（`@org:registry=`）与认证
  （`_authToken`）本批不支持——碰私有源再补，登 D104 同条注记。

**裁决⑤（包内扩展文件型＝.jar）**
- 【场景】pi 扫 `.ts/.js`（FILE_PATTERNS :220-226）与 `index.ts/index.js` 回落
  （:581-588）；java 扩展的可执行形态是 JAR（`META-INF/pi-extension.json` 或
  SPI 标记，`InstalledExtension.isExtensionJar` 已有校验面）。
- 【决定】`FILE_PATTERNS.extensions` 翻译为 `\.jar$`；`resolveExtensionEntries`
  的 index 回落翻译为 `index.jar`；manifest 的 extensions 条目原样尊重
  （条目指到哪收哪，不强制 `.jar` 后缀——加载臂校验）。
- 【风险/代价】[模型可见] 结构性窄差 D111；npm 上的 pi TS 包扩展代码恒不加载，
  只贡献数据资源（裁决①已述）。

**裁决⑥（semver：自写子集优先，semver4j 备选）**
- 【场景】pi 用 npm `semver` 包的 `valid/validRange/satisfies/gt/maxSatisfying/
  rcompare` 六件；prerelease 排序等边角自写易错。
- 【决定】T3 开工先小验证 `com.vdurmont:semver4j` 的 NPM range 覆盖
  （`^`/`~`/hyphen/`\|\|`/x-range）；达标 ⇒ 引库＋薄适配；不达标 ⇒ 自写
  `Semver`/`SemverRange` 子集（hyphen 不支持登 D106）。
- 【风险/代价】[内部] 引库则增一枚零传递依赖；自写则 hyphen range 缺席。

**裁决⑦（tar 解压引 commons-compress）**
- 【场景】npm tarball 是 `.tgz`；JDK 无 tar API，自写 ustar 解析在 pax/gnu 长名
  上风险高。
- 【决定】引 `org.apache.commons:commons-compress`（成熟、GraalVM 元数据齐全）。
- 【风险/代价】[内部] 新依赖一枚（带传递 `commons-io`?——核实后选不带传递的
  配置；不行则 shade 边界内自写 ustar 子集并降格 D106 同类登记）。

**裁决⑧（pattern 匹配＝自写 MinimatchLite）**
- 【场景】pi 用 `minimatch`；java 侧 `java.nio` glob 语义不同（dot 规则、
  `{a,b}` 在文件名形态），ai 模块 `ModelGlob` 为模型 id 定制不宜挪用。
- 【决定】pkg 包内自写 `MinimatchLite`（翻译到 regex）：`*`/`**`/`?`/`{a,b}`/
  dot:false 默认。冷僻形不支持登 D110。
- 【风险/代价】[内部] 与 minimatch 全量语法有差距（D110）；常用资源过滤场景
  （`*.test.md`、`skills/foo` 等）覆盖。

**裁决⑨（会话资源装配改由 resolve() 统辖＝优先级反向修正）**
- 【场景】java 现状 USER→PROJECT 先到先得（SkillDiscovery.discoverAll），pi 五档
  优先级是 **project 赢 user**（rank 0/1 < 2/3）；settings 四类数组键空转。
- 【决定】T6 把技能/模板/主题/扩展四类的会话装配改消费 `resolve()` 输出；
  旧直扫臂退役；settings 四类数组自此生效。
- 【风险/代价】[模型可见] **行为变化**：同名资源 project 从此压过 user；用户
  settings.json 里已有的 `extensions/skills/prompts/themes` 数组从 dormant 变
  生效。[内部] 测试继承坑（memory: settings-json-test-inheritance）放大——
  夹具必须重定向 home 或显式置空四类键；本批全部新测试走临时 home。

**裁决⑩（git 包 npm 依赖安装臂裁掉）**
- 【场景】pi clone/更新 git 包后对有 package.json 的跑 `npm install --omit=dev`
  （:1914-1917 等三处），供 TS 扩展的运行时依赖。
- 【决定】java 不跑（扩展是 JAR，无 node 依赖概念），登 D103。
- 【风险/代价】[内部] 无影响；将来若支持混合包（JAR＋数据），JAR 自带依赖
  （fat jar 或 SPI）由打包侧保证。

**裁决⑪（E6-9 翻转口径＝发射面齐）**
- 【场景】pi 的 onProgress 消费在 CLI/TUI 进度显示（批 19 面），本批只落机制。
- 【决定】`ProgressCallback` 接口＋start/complete/error 三型发射点全接即翻 E6-9
  （对齐），消费面挂批 19——沿用批 11 裁决⑧「声明面/发射面齐计入」先例。
- 【风险/代价】[内部] 无。

---

## §7 测试计划

| 任务 | 测试 | 要点 |
|---|---|---|
| T1 | `PackageSourceJsonTest`、`SettingsPackagesKeyTest` | union 两形互转（串⇄对象）、写回不胀形、npmCommand 防御性拷贝、首项空报错文案逐字 |
| T2 | `ParsedSourceTest`、`GitUrlParserTest` | parseSource 分派表（npm:/git:/https/ssh/scp-like/本地/UNC 相对）；`@ref` 拆分；`..`/`\0`/`\`/前导 `/` 拒绝；identity 去重（SSH⇄HTTPS 同身份）；dedupe project 赢＋delta 双留 |
| T3 | `SemverTest`、`SemverRangeTest`、`HttpNpmResolverTest` | semver 排序（含 prerelease §11 序）；range 六形 satisfies 真值表；resolver 走 JDK `HttpServer` 夹具（packument JSON＋自造 .tgz）：pinned 直拉、range maxSatisfying、integrity 校验过/炸、镜像键优先级、离线短路、安装目录 package.json 回读 |
| T4 | `GitPackageStoreTest` | 本地 `file://` 裸仓夹具（git init --bare＋提交夹具内容）：clone/checkout ref/ensureGitRef 幂等/marker 中断恢复/updateGit upstream 与 origin/HEAD 两臂/removeGit 空父目录修剪/`GIT_TERMINAL_PROMPT=0` 断言；**无 git 环境** skip 守卫（`Assumptions`） |
| T5 | `ResourcePatternsTest`、`PackageIgnoreTest`、`MinimatchLiteTest`、`PiManifestTest`、`PackageManagerResolveTest` | 四符代数真值表（!/+/-/精确 vs pattern、SKILL.md 父目录形态）；五档 rank 排序＋canonicalize 去重；拍平前缀忽略三文件名；manifest 条目/override/约定目录/四目录皆空回 false；onMissing 三臂；temporary 检出命名 sha256-8 |
| T6 | `ResolveWiringTest`（SessionSetup 层） | settings.packages 里的本地包 ⇒ 技能进会话；project 同名技能压过 user（行为变化钉死）；`!pattern` 关停包内指定技能；`--no-skills` 裁剪；`-e` 本地目录走 temporary resolve |
| 横切 | 全量 `mvn clean verify` | spotbugs 在 verify 阶段（批 4 教训）；验证落盘日志看退出码（批 17 教训：mvn 接 head 被 SIGPIPE 掐断＝假绿坑） |

测试环境守则：全部新测试重定向 home（临时目录），不继承真 home 的
settings.json（批 15 教训＋裁决⑨放大了该坑）；git 夹具全部本地 `file://`，
不触网；HTTP 夹具全部 `HttpServer` 起在 loopback。

---

## §8 对账表（判定列：前态→后态）

| 行 | 前 | 后 | 证据（实现后回填 commit） |
|---|---|---|---|
| E6-3 npm 源（版本/range/pinning） | 缺失 | 对齐 | T3：HttpNpmResolver＋SemverRange＋pinning 门 |
| E6-4 git 源 | 缺失 | 对齐 | T4：GitPackageStore 命令表逐字 |
| E6-5 作用域 user/project/temporary | 存疑 | 对齐 | T2 SourceScope 三档＋信任门＋temporary 哈希检出 |
| E6-6 资源过滤 | 缺失 | 对齐 | T5：ResourcePatterns 四符代数＋PackageFilter 三支 |
| E6-7 资源优先级（5 档） | 缺失 | 对齐 | T5 resourcePrecedenceRank＋toResolvedPaths＋T6 消费 |
| E6-8 忽略文件 | 缺失 | 对齐 | T5 PackageIgnore 三文件名拍平前缀 |
| E6-9 onProgress | 缺失 | 对齐 | T5 ProgressCallback＋三型发射（裁决⑪口径） |
| E6-15 pi manifest 读取 | 缺失 | 存疑（改注） | T5 PiManifest 机制落地；行终判随批 19 消费面 |
| E2-20 `npmCommand` | 缺失 | 对齐 | T1 声明＋解析＋校验；消费窄差 D104 |
| E2-26 `packages` | 缺失 | 对齐 | T1 声明＋解析；T5 resolve 消费 |

E6 域后态：对齐 7／存疑 4（E6-1/E6-2/E6-13/E6-15）／缺失 5（E6-10/11/12/14/16 全随
批 19 CLI/持久化面）＝17.0/31 ≈ 54.8%。

---

## §9 闭环回填

**闭环日 2026-10-24；commits `7ab7b772..7bd9ab50`（T1–T6＋spotbugs 回补＋评审回补）。**

### 9.1 实际翻动（对账 §0/§8：全中，+16.0）

| 指标 | 前 | 后 |
|---|---|---|
| 模块账面（Σ 485） | 354.0 ≈ 73.0% | **370.0 ≈ 76.3%**（+16.0） |
| E6 域（Σ 31） | 5.0 ≈ 16.1%（对齐 0） | **17.0 ≈ 54.8%**（对齐 7/存疑 4/缺失 5） |
| E2 域（Σ 84） | 76.5 ≈ 91.1% | **80.5 ≈ 95.8%**（E2-20/E2-26 翻，缺失清零） |
| 全仓（Σ 1379） | 1,035.5 ≈ 75.1% | **1,051.5 ≈ 76.3%**（+16.0） |

E6-15 缺失→存疑（改注：机制落地 PiManifest，消费面随批 19）；E6-16 存疑→缺失
（降级注记：T6 后旧 `ExtensionPackageManager` 目录面与装配脱钩，CLI 批 19 重裁）
——两动互抵存疑数 5→4，与 §8 尾部口径一致。

### 9.2 评审回补（fresh-context 全批评审：2 Important＋7 Minor）

- **I-1（修真）**：prompts 同名优先级在消费点整体反转——注册表 `put` 后注册
  胜出 × pi 序构表 ⇒ user 压 project（裁决⑨ 对 prompts 失效且较批 18 前回归）。
  修：`loadPromptTemplates` 落表前按名 first-wins 去重（pi `dedupePrompts`
  resource-loader.ts:1160-1184 对位）；钉测两枚（project 压 user／显式 CLI 撞名
  必输）。
- **I-2（修真）**：Windows 下 settings 四类数组含冒号条目（裸 `builtin:mcp`）
  在 `Path.of` 抛 `InvalidPathException` 炸穿启动（四类键此前空转，本批新暴露）。
  修：`resolvePathInternal` 非法路径回退原串＋消费点 `parsePathOrNull`——
  pi `existsSync=false` 静默跳过同结局；钉测一枚。
- **M-1（随修）**：MinimatchLite `**` 两语义差（尾段须 ≥1 段〔`a/**` 不中 `a`〕＋
  不跨 dot 段）——pi minimatch 10.2.6 实测对拍后修，钉测两枚；D110 注记范围收窄。
- **M-2（随修）**：`NpmCommand.resolve` 校验面接进 `PackageManager` 构造
  （T1.4 承诺兑现；此前仅测试调用＝死代码）；钉测一枚。
- **M-3（随修）**：协议臂 host 小写归一（JS `URL.hostname` 对位；身份键/
  安装路径不再因大小写分叉 pi）；钉测一枚。
- **M-4（随修）**：会话装配 4 遍 resolve ⇒ `ResolvedAssembly` 单遍三元组
  （初装/fork/reload 各自一份，技能/模板/主题/扩展四消费面共享）——temporary
  未 pin git 源的重复 `git pull` 副作用与不一致窗口消除（pi 每 reload 单遍对位）。
- **M-5（登记）**：`-builtin:name` 关停算出没人消费（ServiceLoader 恒全装）——
  生产零内置扩展，纯理论缺口 ⇒ 窄差 D112。
- **M-6**：install 双拉 packument——评审到达前已修真（1b69c6b9），不重复计。
- **M-7（随修）**：`packageManagerFor(settings, homeDir)` 注入缝，夹具不再继承
  真 `HOME` 的 `~/.agents/skills`（pi getHomeDir 是 HOME-first，守则对齐）。

### 9.3 过程修真与排障

- **pi 同形怪癖钉死**：`git://host/path` 被 `git:` 前缀门吞噬（前缀剥光后
  `//host/path` 进简写臂空 host 拒）⇒ pi 同返 null，测试钉此对齐。
- **scp-like 裸写**（无 `git:` 前缀）在 pi 亦被拒（`isLocalPath` 判本地在先）
  ——测试初版期望写错，取证 pi `git-ssh-url.test.ts` 后改钉。
- **过滤面 Windows 化**：`ResourcePatterns` 的 basename/dirname/relative 改纯串
  运算（`builtin:x` 形态在 Windows `Path.of` 即炸；pi 全程纯串）。
- **spotbugs 再撞**（批 4 教训第 N 次验证——spotbugs 在 verify 阶段）：12 枚
  NP_NULL（`getParent()`/`getFileName()` 可空面），全收。
- **测试期望两错**：`+`/`-` 精确形用例漏算「无 include 默认全收」臂；`-e` 本地
  目录经 resolve 落「目录本身作扩展路径」（pi `resolveLocalExtensionSource`
  同形），非 index.jar 直收。
- **环境事故**：评审子代理的 `-am` 测试跑与主会话后台 `clean verify` 互撞
  target（双向 NCDFE 污染；评审期 ai 模块红为污染产物，不可信）；孤儿 surefire
  JVM 残留已杀（46656），50280 权限拒杀残留（无锁无害，用户可自清）。
  **规则存量再验证**：并行会话时代后台构建与同仓前台 mvn 互斥。

### 9.4 行为变化注记（changelog 级）

- **`settings.json` 的 `extensions/skills/prompts/themes` 四数组与 `packages`
  键自此生效**（此前 dormant）——同名资源 project 压 user（pi 五档优先级）；
  包内资源按 pi manifest/约定目录/过滤 pattern 装载。
- **`packages` 里的 npm:/git: 源真实安装**（HTTP registry／系统 git clone 到
  managed 安装根）；缺包直装（pi 无回调同形），`PI_OFFLINE` 跳过。
- **`-e` 接受 npm:/git: 源**（temporary 检出，不写 settings）；`--no-skills`/
  `--no-prompt-templates` 只裁 resolve 臂，CLI 显式臂仍收（pi 合并臂对位）。
- **`-ne` 下项目扩展不再无条件加载**：project 资源全过信任门（pi 同）。

### 9.5 三处回填核对

- docs/04：窄差 D102–D112 登记＋批 18 条目（见台账）。
- docs/05：总表 coding-agent 行 370.0/485 ≈ 76.3%、全仓 1,051.5/1379 ≈ 76.3%、
  §2.4 批 18 条目。
- map/04：E6 域 9 行翻动＋E2-20/E2-26＋E2/E6 汇总行＋加权/未加权汇总表
  （批 17 在尾表的 D/E5 滞后随本批一并收平）。
- docs/20：§1 读数、§2 批 18 行、§3 序 17 划销＋序 18 注记、§3.3 变量② 拍板划销。
