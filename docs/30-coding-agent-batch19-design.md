# coding-agent 批 19 设计稿：E6 包管理器第二批——CLI 子命令面＋settings.packages 写回链＋update 机制

【背景摘要】本批是 coding-agent 对齐批次的第 19 批（总账 docs/20 §3 序 18），收编
批 18（docs/29）留白的三块：① CLI 子命令面重裁（旧 `PackageCommand`/
`ExtensionPackageManager` 接新 `pkg/PackageManager`，pi `package-manager-cli.ts`
1,141 行对位）；② `settings.packages` 写回链（`installAndPersist`/
`removeAndPersist`/`addSourceToSettings`/`removeSourceFromSettings`，E6-14）；
③ update 机制（`updateConfiguredSources`/`checkForAvailableUpdates`/并发 4 闸）。
批 18 闭环后模块账面 370.0/485 ≈ 76.3%。

**取证颠覆一处计划假设**：docs/20 序 18 列了「onMissing TUI 三选询问」，但全仓
grep 证实 **pi 本锚点（ea448f454）不存在该 UI**——唯一非空 `onMissing` 回调是
`startup-ui.ts:81` 的恒 `"skip"`（启动加载主题臂），其余 `resolve()` 调用
（resource-loader.ts:529/681）均不传参＝缺包直装；`MissingSourceAction` 的
`"error"` 臂无任何调用方返回。按「pi 没有的不造」原则本批**不做**三选询问，
登取证结论（§6 裁决⑤）。

术语（首现释义）：

- **写回链**：`install`/`remove` CLI 命令把源追加/移出 `settings.json` 的
  `packages` 数组的链路（先动文件系统，后写 settings）。
- **字段级 merge 写回**：pi `persistScopedSettings`（settings-manager.ts:596-625）
  的写盘语义——文件锁内读当前文件，只覆盖 modified 字段，**保留未知键与
  其他字段**，非整本覆盖。
- **并发 4 闸**：pi 自研 worker-pool `runWithConcurrency`（:1691-1713），
  update 检查与 git 更新各限 4 并发；**首错即整体抛、其余任务后台继续**
  （非 p-limit、无 AggregateError）。
- **三态循环**：pi config TUI project 模式的 inherit/load/unload 覆写语义
  （§3 T6-7）。

---

## §0 基线与预期翻动

| 指标 | 前（批 18 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485，去重） | 370.0 ≈ 76.3% | **388.0 ≈ 80.0%**（+18.0） |
| E6 域（Σ 31） | 17.0 ≈ 54.8%（对齐 7/存疑 4/缺失 5） | **30.0 ≈ 96.8%**（对齐 15/存疑 1/缺失 0） |
| B 域（Σ 15） | 9.0 ≈ 60.0% | **14.0 ≈ 93.3%**（#4 update 存疑，余全对齐） |
| 全仓（Σ 1379） | 1,051.5 ≈ 76.3% | **1,069.5 ≈ 77.6%**（+18.0） |

翻动行（14 行，+18.0）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|---|
| E6-1 `install` 本地源 | 2 | 存疑 | 对齐 | T1+T4（install() local 臂＝校验存在，pi :1049-1053） |
| E6-2 `install` http(s) URL | 2 | 存疑 | 对齐 | T1+T4（pi 语义＝https URL 走 git 源；旧 JAR 下载臂删，裁决④） |
| E6-10 `update self`/`update pi` | 2 | 缺失 | 存疑 | T4（java 无安装形态 ⇒ 恒走 pi unknown 不可用臂，裁决②） |
| E6-11 `update --extensions` | 2 | 缺失 | 对齐 | T3+T4 |
| E6-12 `update --models` | 1 | 缺失 | 对齐 | T4（ModelCatalogRefresh 手动触发缝，15s 超时） |
| E6-14 持久化到 `settings.packages` | 3 | 缺失 | 对齐 | T1+T2 |
| E6-15 pi manifest 读取 | 2 | 存疑 | 对齐 | 终判（机制批 18 已落；list 标记＝`filtered` 对象形判定，pi 无独立 manifest 标记——取证 §1.5） |
| E6-16 `remove`/`uninstall` | 2 | 缺失 | 对齐 | T1+T4（含 npm 臂删除补齐） |
| B-1 `install <source> [-l]` | 2 | 存疑 | 对齐 | T4 |
| B-2 `remove <source> [-l]` | 2 | 存疑 | 对齐 | T4 |
| B-4 `update [source\|self\|pi]` | 2 | 缺失 | 存疑 | T4（extensions/models 落地，self 恒不可用臂——三面缺其一） |
| B-5 `list` | 2 | 存疑 | 对齐 | T4（分组＋installedPath＋`(filtered)` 逐字） |
| E6-13 `config` TUI | 2 | 存疑 | 对齐 | T5+T6（裁决①用户拍板全做） |
| B-6 `config [-l]` | 2 | 存疑 | 对齐 | T5+T6 |

不动行：无（E6 域缺失清零；B 域仅 #4 存疑）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`）

pi 侧两面：`core/package-manager.ts`（2,760 行，写回链＋update 机制）＋
`package-manager-cli.ts`（1,141 行，CLI 面，**自写手写参数解析器**，非
commander/yargs）。

### 1.1 写回链（package-manager.ts）

`install`（:1036-1058）：npm→installNpm／git→installGit／**local→仅校验路径存在**
（`Path does not exist: <resolved>` 逐字，模型可见）；project scope 前过
`assertProjectTrustedForScope`（:1775-1779，文案批 18 已逐字对位）。全程包
`withProgress("install", source, `Installing ${source}...`, …)`。

```ts
async installAndPersist(source: string, options?: { local?: boolean }): Promise<void> {
    await this.install(source, options);
    this.addSourceToSettings(source, options);
}
async removeAndPersist(source: string, options?: { local?: boolean }): Promise<boolean> {
    await this.remove(source, options);
    return this.removeSourceFromSettings(source, options);
}
```

`remove`（:1064-1079）：npm→`uninstallNpm`／git→`removeGit`／**local→无文件系统
动作**（只写 settings）；`withProgress("remove", …, `Removing ${source}...`, …)`。

`addSourceToSettings`（:832-861，逐字要点）：scope 由 `options.local` 定；去重键＝
**包身份**（`packageSourcesMatch`，npm `npm:<name>` 忽略版本／git `git:<host>/<path>`
SSH⇄HTTPS 归一／local 解析后绝对路径）；同身份同串 ⇒ false；同身份不同写法 ⇒
**原位替换**（对象形保留 filter 字段只换 source）；否则追加。写 user 走
`setPackages`，写 project 走 `setProjectPackages`。

`removeSourceFromSettings`（:863-879）：按身份过滤；无变化 ⇒ false。

`normalizePackageSourceForSettings`（:1469-1478）：**只归一化 local 源**（转相对
baseDir 的相对路径，空 ⇒ `"."`），npm/git 原样返回。**java 批 18 已有对位钩子**
（`SourceResolver.normalizePackageSourceForSettings`:143）。

settings-manager 写盘（settings-manager.ts）：
- `setPackages`（:1081-1085）：改 globalSettings.packages → markModified → save()。
- `setProjectPackages`（:1087-1090）：updateProjectSettings →
  assertProjectTrustedForWrite → saveProjectSettings。
- 落盘＝**字段级 merge**（`persistScopedSettings` :596-625：withLock 内读当前
  文件、migrate、只覆盖 modified 字段、`JSON.stringify(merged, null, 2)`）＋
  proper-lockfile 文件锁＋串行写队列。

### 1.2 update 机制（package-manager.ts）

`update(source?)`（:1090-1120）：枚举 global+project settings 的 packages
（**temporary 不参与**），按身份过滤指定 source；指定却无匹配 ⇒ 抛
`buildNoMatchingPackageMessage`（:1430-1436，带 `Did you mean ...?` 建议
:1438-1461）→ `updateConfiguredSources`。

`updateConfiguredSources`（:1122-1179，逐字骨架）：

```ts
if (isOfflineModeEnabled() || sources.length === 0) { return; }
// npm pinned（精确版本）→ 跳过；git pinned ref → 仍参与（对齐已有 clone 到新配置的 ref）
const npmCheckResults = await this.runWithConcurrency(npmCheckTasks, UPDATE_CHECK_CONCURRENCY); // =4
// npm 更新按 scope 分两批（user/project），每批一次 npm install 多 spec（updateNpmBatch :1197-1209）
// git 每源一个任务：withProgress("update", …, `Updating ${source}...`) → updateGit；组内并发 4
// user-npm 批、project-npm 批、git 任务组三者 Promise.all 并行
```

`runWithConcurrency`（:1691-1713）：共享 `nextIndex` 游标的 worker-pool；
**无错误聚合**——任一 task 拒绝 ⇒ `Promise.all` 以首错整体拒绝，其余 worker
后台继续（detached）。

`shouldUpdateNpmSource`（:1181-1195）：未安装 ⇒ true；查询失败 ⇒ **true（保守
更新）**；否则 `gt(latestSatisfying, installedVersion)`。**java HTTP 臂对位**：
`NpmResolver.latestSatisfying`（批 18 seam 现成）。

git 无 shouldUpdate 前置——候选无条件进 `updateGit`（批 18 已逐字对位
`GitPackageStore.updateGit`:89）。

`uninstallNpm`（:1874-1890，pi 走 npm CLI `uninstall <name> --prefix <root>`）：
**java HTTP 臂对位＝删 `<installRoot>/node_modules/<name>` 目录**（无 npm CLI，
窄差 D113）。

`updateNpmBatch` 多 spec 一次 install ⇒ **java 逐包顺装**（`HttpNpmResolver.install`
循环，窄差 D113 同条）；进度消息逐字：单源 `Updating <source>...`／多源
`Updating <scope> npm packages...`。

`checkForAvailableUpdates`（:1217-1280）：离线 ⇒ []；dedupe 后**排除
temporary/local/pinned**；未安装跳过；`npmHasAvailableUpdate`（:1515-1531，
查询失败 ⇒ **false**，与 shouldUpdate 相反）；`gitHasAvailableUpdate`
（:1566-1581，本地 HEAD vs `getRemoteGitHead` 不等即有更新，异常 ⇒ false）；
并发 4；返回 `PackageUpdate { source, displayName, type, scope }`（:100-105）。
**唯一消费方**：interactive-mode.ts:1263-1279（启动 fire-and-forget）→
`showPackageUpdateNotification`（:4667-4684，文案见 §1.4）。

### 1.3 CLI 面（package-manager-cli.ts）

命令识别（:397-407）：`install/remove/update/list`＋`uninstall`→`remove` 别名；
`config` 独立入口（`handleConfigCommand` :813）。Usage 串（:287-300）：

```ts
install: `${APP_NAME} install <source> [-l] [--approve|--no-approve]`
remove:  `${APP_NAME} remove <source> [-l] [--approve|--no-approve]`
update:  `${APP_NAME} update [source|self|pi] [--self|--extensions|--models|--all] [--extension <source>] [--approve|--no-approve] [--force]`
list:    `${APP_NAME} list [--approve|--no-approve]`
config:  `${APP_NAME} config [-l] [--approve|--no-approve]`
```

flag 表（:424-524）：`-h/--help`；`-l/--local`（仅 install/remove）；
`--self/--extensions/--models/--all/--force/--extension <src>`（仅 update）；
`-a/--approve`/`-na/--no-approve`（全部）。`--extension` 给两次 ⇒
`--extension can only be provided once`（:505，逐字）。

**update 目标归约**（:526-579）：`--all` 与任何其他目标冲突；`--models` 全冲突
单列；`--extension <src>` 与 self/extensions/all/位置 source 冲突；位置 source
为 `self`/`pi` ⇒ self（同带 `--extensions` 升格 all）；无 source 时
`--self --extensions` 同给 ⇒ all；**全无 flag ⇒ 默认 self 且
`showExtensionsSkippedNote=true`**（:575-578）。

handler 要点：
- 公共前置（:894-976）：parse → 错误检查 → install/remove 缺 source 检查 →
  **models 短路分支（:940-949，在 settings/trust 加载之前）** →
  createCommandSettingsManager（含 ProjectTrustStore 交互信任询问；update 用
  `useSavedProjectTrustOnly: true` :958 跳过询问）→ trust 检查
  （`Project is not trusted. Use --approve to modify local package config.` :963）→
  `new DefaultPackageManager(...)` → 注册进度回调。
- install（:980-983）：`installAndPersist` → `Installed ${source}`（chalk.green）。
- remove（:985-994）：`removeAndPersist` ⇒ false 时 `No matching package found for
  ${source}`（console.error＋exitCode 1）；成功 `Removed ${source}`。
- update（:1032-1133）：`showExtensionsSkippedNote` ⇒
  `Extensions are skipped. Run ${APP_NAME} update --extensions to update extensions.`
  （dim，:1034-1038）；含 extensions ⇒ `packageManager.update(source?)` →
  `Updated ${updateSource}`／`Updated packages`；含 self ⇒ 三分叉（managed
  install／Windows 仅 npm+pnpm／其他全局安装器），java 全无安装形态对位
  （裁决②）。
- list（:996-1030）：`listConfiguredPackages`（:1008-1034——user/project 两组，
  `filtered = typeof pkg === "object"`，`installedPath = getInstalledPath`）；
  空 ⇒ `No packages installed.`（dim）；分组标题 `User packages:`／
  `Project packages:`（bold，组间空行）；每包两行：`  ${source}${filtered ?
  " (filtered)" : ""}`＋有 installedPath 时 dim 四格缩进路径。

**onProgress CLI 消费**（:972-976，逐字）：

```ts
packageManager.setProgressCallback((event) => {
    if (event.type === "start") {
        process.stdout.write(chalk.dim(`${event.message}\n`));
    }
});
```

只消费 `start`，其余三型忽略；无 spinner/进度条。

**`--models` 短路臂**（:605-631）：`ModelRuntime.create({allowModelNetwork:false})`
→ `refresh({allowNetwork:true, force:true})`；15s AbortController 超时
（`Model catalog refresh timed out.` :621）；错误聚合
`Could not refresh model catalogs: ${provider}: ${msg}; …`（:624-625）；成功
`Model catalogs refreshed`（:630）。本质是逐 provider 强制重拉远程目录
（绕过 4h TTL），非整包 models.json。**java 对位缝现成**：
`ModelCatalogRefresh.refresh(providers, true, true)`（批 15 先例
ModelCommands.java:39-60，15s 超时＋pi 逐字警告文案）。

### 1.4 启动更新通知（interactive-mode.ts）

`checkForPackageUpdates`（:1263-1279）：`PI_OFFLINE` ⇒ []；异常吞掉 ⇒ []；
启动 fire-and-forget（:1166-1178）。通知块（:4667-4684）文案逐字：

```
Package Updates Available            （bold + warning 色）
Package updates are available. Run pi update --extensions   （muted＋accent）
Packages:                            （muted）
- <displayName>                      （每包一行）
```

聊天区插入 warning 色边框通知块；**不在 doctor/diagnostics 展示**。

### 1.5 E6-15 终判证据

pi `list` 输出**无 manifest 标记**——唯一标记是 `(filtered)`（对象形 PackageSource
即带过滤）。pi manifest 的消费在 resolve 收集臂（批 18 已落地
`PiManifest`＋`PackageResourceCollector` manifest 臂）。故 E6-15 终判＝**对齐**
（机制＋收集消费全落；展示面无独立标记可移植）。

---

## §2 java 侧现状（取证 2026-10-24，main 2dcc7bf0）

1. **旧 CLI 面**：`SubcommandHandler`（子命令闭集 8 个，Main.java:37-39 先
   ArgsParser 分派）→ `PackageCommand`（switch install/remove/update/list；
   URL 前缀 ⇒ http 下载 JAR；update 只打 `SELF_UPDATE_NOTE`，无 --models 臂）→
   `ExtensionPackageManager`（JAR 拷贝式 install/remove/update/list，200 行，
   **批 18 后会话装配已不读其目录**——CLI 与装配脱钩）。
2. **新 pkg 面缺口**（批 18 产物）：`PackageManager.installParsedSource`/
   `withProgress` 是包私有且 `withProgress` **无调用点**；无公开
   install/remove/update/listConfiguredPackages；npm 臂无删除方法
   （`HttpNpmResolver` 只装不删）；`ProgressCallback` 全仓零消费者。
3. **SettingsManager 无写回面**：公开持久化仅 `flush()`（整本写 global，**丢
   未知键**）＋`setProjectTrusted`。真正写盘在 `FileSettingsStorage`
   （`writeGlobal/writeProject` 整本写；**`withLock(scope, Function<String,String>)`
   读-改-写串回调现成**，:86-102——字段级 merge 的落点）。ConfigCommand 现直
   new FileSettingsStorage 整本写（ConfigCommand.java:96-98，同丢未知键）。
4. **`PackageSource` 不胀形写回已备**（批 18 T1：`@JsonValue` 裸串来写回裸串、
   对象形只写非 null 键）；`SourceResolver.normalizePackageSourceForSettings`
   现成。
5. **ConfigCommand 语义差**：enable/disable 读写四类数组的**裸串**（enable＝加
   裸 value、disable＝删条目），无 `+`/`-` 前缀代数——自动发现条目（不在
   settings 里）**disable 不掉**；pi 语义是加 `-pattern`（config-selector
   toggleTopLevelResource :538-584）。
6. **onMissing**：SessionSetup.java:184 `pm.resolve(null)` 恒 null——与 pi
   resource-loader 同形（直装）；pi 的恒 skip 主题臂（startup-ui.ts:81）java
   无对位场景（java 启动主题加载不经 resolve 单独通道——核实时若有不造成立，
   见 §3 T6）。
7. **update --models 缝现成**：`ModelCatalogRefresh.refresh(providers,
   allowNetwork, force)`（ModelCatalogRefresh.java:46-55）＋
   `FileModelsStore.defaultStore()`；ModelCommands.java:39-60 有 15s 超时＋pi
   逐字文案先例。
8. **TUI 通知通道现成**：批 17 起 ChatScreen 有 notify/showStatus 通道与启动
   提示先例（crash-log 启动提示，批 15）；包更新通知块可直接落。

---

## §3 任务分解

> 包放置：机制落 `com.pijava.coding.agent.pkg`（批 18 既定）；CLI 面改
> `subcommand/PackageCommand.java`；settings 写回面进 `core`。每文件 ≤500 行。

### T1：写回链＋公开安装面（E6-1/E6-2/E6-14/E6-16 机制半，~380 行＋测试）

1. `PackageManager` 扩公开面（pi 接口 :114-130 对位）：
   - `void install(String source, boolean local)`：分派逐字（§1.1）；local 臂
     校验存在（`Path does not exist: <resolved>` 逐字）；`withProgress` 改
     public 并在此处接调用点（消除批 18 遗留死代码）。
   - `void remove(String source, boolean local)`：npm→`uninstallNpm`（新）／
     git→`gitStore.removeGit`（现成）／local→无动作。
   - `void installAndPersist(String source, boolean local)`／
     `boolean removeAndPersist(String source, boolean local)`（§1.1 逐字：
     先文件系统后 settings）。
   - `boolean addSourceToSettings(String source, boolean local)`／
     `boolean removeSourceFromSettings(String source, boolean local)`：
     身份去重（`SourceResolver.packageSourcesMatch` 现成）＋原位替换
     （对象形保 filter 只换 source——`PackageSource` 加 `withSource(String)`
     派生）＋追加；写回走 T2 的 setPackages/setProjectPackages。
   - `List<ConfiguredPackage> listConfiguredPackages()`（pi :1008-1034 逐字）：
     新 record `ConfiguredPackage(String source, SourceScope scope,
     boolean filtered, String installedPath)`；`filtered`＝对象形（java 对位
     `!pkg.stringForm()`）。
2. npm 删除补齐：`HttpNpmResolver` 加 `void uninstall(String name,
   Path installRoot)`——删 `<root>/node_modules/<name>`（pi 走 npm CLI，窄差
   D113）；root 不存在 ⇒ no-op（pi `existsSync` 门对位）。接口法：
   `NpmResolver` 加 default 方法或接口扩（取接口扩＋HttpNpmResolver 实现，
   seam 注释同步）。
3. scope⇔local 映射与信任门复用批 18 件（`assertProjectTrustedForScope` 已逐字）。

### T2：SettingsManager 写回面（E6-14 落盘半＋config TUI 写回底座，~200 行＋测试）

1. `SettingsManager` 加 `setPackages(List<PackageSource>)`／
   `setProjectPackages(List<PackageSource>)`（pi settings-manager.ts:1081-1090
   对位；project 臂过 `assertProjectTrustedForWrite` 对位门）。
2. 四类数组同面：`setExtensions/setSkills/setPrompts/setThemes`（及 project 版，
   pi :1096-1154 对位）——config TUI（T6）与 ConfigCommand（T5）的写回落点。
3. 落盘＝**字段级 merge**（裁决③）：走 `FileSettingsStorage.withLock` 读-改-写
   ——读原文 → Jackson `ObjectMapper.readTree` → 只替换目标键
   （`PackageSource` 的不胀形 `@JsonValue` 序列化保证写回形态）→ 写回。
   **保留未知键与其余字段格式**，消除「整本写丢未知键」坑（ConfigCommand
   现状同坑，T5 一并迁）。
4. `flush()` 整本写路径不动（既有语义）；新写回不经过它。

### T3：update 机制（E6-11，~420 行＋测试）

1. `PackageManager.update(String sourceOrNull)`（pi :1090-1120 对位）：枚举
   user+project settings packages（temporary 不参与）；身份过滤；
   `buildNoMatchingPackageMessage`＋`Did you mean ...?` 建议（:1430-1461 逐字，
   模型可见）。
2. `updateConfiguredSources`（pi :1122-1179 对位）：离线/空 ⇒ return；npm
   pinned 跳过、git pinned 参与；npm 检查并发 4；npm 更新按 scope 分批
   （**java 逐包顺装**，D113）＋git 每源一任务并发 4（`updateGit` 现成）；
   三组并行。
3. 并发闸 `runWithConcurrency`：游标 worker-pool 语义对位（虚拟线程＋
   AtomicInteger 游标）；**首错即整体抛、其余后台继续**——java 对位：收集
   首个异常抛之，剩余任务不取消不等待（pi detached 语义）。注意
   `StructuredTaskScope` 的默认取消语义不符，自写小池。
4. `shouldUpdateNpmSource`（pi :1181-1195 对位）：未安装 ⇒ true；
   `NpmResolver.latestSatisfying` 查询失败 ⇒ true（保守）；`Semvers.gt` 比较
   （批 18 semver4j 适配现成）；已装版本读取复用批 18 的安装目录
   package.json 回读面。
5. `checkForAvailableUpdates()`（pi :1217-1280 对位）：temporary/local/pinned/
   未安装排除；npm 查询失败 ⇒ false（与 shouldUpdate **相反**，钉死）；
   git `getRemoteGitHead` 对比（现成）；返回 `List<PackageUpdate>`——新 record
   `PackageUpdate(String source, String displayName, Type type, SourceScope scope)`，
   `enum Type { NPM, GIT }`（纯常量闭集 ⇒ enum）。
6. 离线门复用 `ModelCatalogRefresh.offlineMode` 同款判定（批 18 惯例）。

### T4：CLI 面重裁（B-1/B-2/B-4/B-5＋E6-1/2/10/11/12/16 的命令半，~480 行＋测试）

1. `PackageCommand` 重写为 pi `parsePackageCommand`（:397-595）对位的手写
   解析器：flag 表逐字（§1.3）、`-l` 限 install/remove、update 目标归约
   全冲突表（含 `--extension` 单次门与默认 self＋skippedNote 臂）、
   `uninstall`→`remove` 别名。错误文案逐字（`Unknown option … for "…".`／
   `Missing value for …`／`Unexpected argument …`／`Missing … source.`）。
2. 四 handler：install（`Installed <source>`）／remove（`No matching package
   found for <source>`＋exit 1／`Removed <source>`）／list（分组＋`(filtered)`
   ＋installedPath 两行式，`No packages installed.`）／update（skippedNote
   提示→extensions 臂 `Updated <source>`/`Updated packages`→self 臂裁决②）。
3. **onProgress CLI 消费**（E6-9 消费面）：`setProgressCallback` 注册——仅
   `START` 型 `System.out.println(event.message())`（pi chalk.dim 的色java
   CLI 无对位 ⇒ plain，窄差 D116）。
4. **self 臂**（裁决②）：java 无安装形态 ⇒ 恒走 pi「unknown install method」
   不可用臂：打印 `pi-java cannot self-update this installation.` 系说明
   （pi `printSelfUpdateUnavailable` :633-645 对位，文案按 java 安装形态改写
   为「走 Maven Central 发布管线手动更新」——现 `SELF_UPDATE_NOTE` 语义保留
   升级）；exitCode 1。managed/npm/pnpm 三通道不移植（D115）。
5. **--models 短路臂**：在 settings/trust 加载**之前**（pi :940-949 序对位）；
   `new ModelCatalogRefresh(FileModelsStore.defaultStore()).refresh(providers,
   true, true)`；15s 超时（`Model catalog refresh timed out.` 逐字）；错误聚合
   `Could not refresh model catalogs: …`（逐字）；成功 `Model catalogs
   refreshed`（逐字）。
6. **信任面**（窄差 D114）：pi CLI 有交互信任询问（resolveProjectTrusted）；
   java CLI 非交互 ⇒ 直接按已存信任判定，未信任且未 `--approve` ⇒ 报错文案
   逐字（`Project is not trusted. Use --approve to modify local package
   config.`）；`-a/--approve` ⇒ `setProjectTrusted(true)` 后走 project 臂；
   `-na` ⇒ 按未信任。
7. **帮助文本**：`printPackageCommandHelp`（pi :317-395）对位——install 帮助
   6 源形态示例（`npm:@foo/bar`／`git:github.com/user/repo`／
   `git:git@github.com:user/repo`／`https://github.com/user/repo`／
   `ssh://git@github.com/user/repo`／`./local/path`）逐字；remove 帮助含
   `Alias: pi uninstall` 行；update 帮助列 flags 与 `update pi` 短形说明。
8. **旧面删除**（裁决④）：`ExtensionPackageManager`＋`ExtensionPackageManagerTest`
   删除（pi 无 JAR 拷贝安装面；装配批 18 已脱钩，CLI 是其最后消费者）；
   `SubcommandHandler` 分派表不动（签名兼容）。

### T5：config 命令非交互对齐（~120 行＋测试）

1. enable/disable 写回改 **pi pattern 代数**：enable ⇒ 推 `+<pattern>`、
   disable ⇒ 推 `-<pattern>`（pi toggleTopLevelResource :538-584 对位——先剥
   旧前缀去重再推新条目）；**disable 不再删裸串**（自动发现条目自此可关停，
   修 §2-5 语义差）。enable/disable 子命令为 java 独有便利面（pi 只有 TUI），
   保留但改 pi 写回语义。
2. 写回走 T2 字段级 merge（弃直 new FileSettingsStorage 整本写）。
3. **无参 `config` 改开 TUI**（pi `pi config` 对位，行为变化注记）；旧无参
   只读列表面退役（pi 无此面）；`config -l` 初始写作用域 project；
   `config enable|disable` 子命令保留（java 独有，不计分母）。

### T6：config TUI 本体（E6-13/B-6 主体，裁决①用户拍板全做，~800 行＋测试）

> pi 证据：`modes/interactive/components/config-selector.ts`（951 行组件）＋
> `cli/config-selector.ts`（61 行壳）＋ `handleConfigCommand`
> （package-manager-cli.ts:813-888）。

1. **入口壳**（pi :849-887 逐字序）：cwd/agentDir → createCommandSettingsManager
   （含信任）→ `-l && !trusted` ⇒ `Project is not trusted. Use --approve to
   modify local resource config.`（逐字，exit 1）→ 收集内置扩展名表 →
   **双份 resolve**：global 份＝`SettingsManager.create(cwd, agentDir,
   {projectTrusted:false})` 构造的 PackageManager.resolve()（信任门恒拒 ⇒
   project 资源全剔除，:866-872）；project 份＝真信任 settingsManager 的
   resolve()，**未信任 ⇒ 复用 global 副本**且 `projectModeAvailable=false`
   （:873-876）→ 开 TUI → 关闭后 `System.exit(0)`（pi `process.exit(0)` :887
   对位）。java 落点：`ConfigCommand` 无参臂 → tui 模块新屏，启动壳先例＝
   批 14 会话选择器（SPI＋启动壳）。
2. **组件**（pi-java-tui 新 `screen/ConfigSelectorScreen`，pi 组件对位）：
   布局＝DynamicBorder 夹 Header＋ResourceList（搜索 Input＋分组列表）；
   Header 两行逐字——标题 `Global Resources`/`Project Local Resources`（:209）、
   键位提示 `{tab} switch mode · space toggle · esc close`（global）/
   `space cycle inherit/+/-`（project）（:211-214）、路径行
   `~/.pi-java/agent/settings.json`／`.pi-java/settings.json · inherited global
   resources are dimmed`（:216-219，CONFIG_DIR_NAME 既定翻译）。
3. **列表模型**：Group（来源分组）→ Subgroup（四类固定序
   extensions→skills→prompts→themes，标签逐字 :35-40）→ Item；group 标签规则
   `getGroupLabel`（:84-101：包 `{source} ({scope})`／`Built-in`／
   `Built-in (project override)`／`User (~/…/)`／`Project (…/)`／
   `User settings`／`Project settings`）；排序 package 先于 top-level、user
   先于 project（:164-174）；显示名规则（:135-146：builtin 去 `builtin:` 前缀、
   extensions `父目录/文件名`、SKILL.md 显示父目录名、其余文件名）；
   空态 `  No resources found`（:406）；滚动指示 `  ({current}/{total})`（:454）。
   元数据来源＝批 18 `ResolvedResource.metadata`（source/scope/origin/baseDir/
   packageRoot 全备，不需 resourceMetadataByPath 表）。
4. **键位表**（pi :460-520）：up/down 跳 item（跳标题）／pageUp/pageDown
   ±maxVisible 吸附／esc 关闭／ctrl+c 退进程／tab 切写作用域（仅
   projectModeAvailable）／space/enter 开关或三态循环；
   maxVisible = max(5, 终端高 − 8)（:270-272）。
5. **复选框渲染**（:645-653 逐字）：global `[x]`/`[ ]`；project load `[+]`／
   unload `[-]`／inherit＝dim `[x]`/`[ ]`；project 后缀 `  project load`／
   `  project unload`；继承条目 dim＋后缀 `  inherited global`（:660），group
   头 ` · inherited global`（:423-426）。
6. **搜索**：未匹配按键落 Input；displayName/resourceType/path 子串过滤，
   保留含命中项的 group/subgroup 头（:326-375）。
7. **写回四函数**（全部走 T2 字段级 merge 面）：
   - `toggleTopLevelResource`（:538-584 逐字）：pattern 取值（builtin＝完整
     path；否则相对 `metadata.baseDir ?? 默认基目录`，:862-867）；先剥
     `!`/`+`/`-` 前缀去重再推 `+pattern`/`-pattern`。
   - `togglePackageResource`（:586-643）：`packages[]` 匹配串项升级为对象形
     推 `+/-pattern`（相对包根，:869-872）；四过滤键全空折叠回纯串（:628-636）。
   - project 三态循环 `getNextOverrideState`（:739-745 逐字）：
     inherit ⇒ inheritedEnabled ? unload : load；unload ⇒ inheritedEnabled ?
     load : inherit；load ⇒ inheritedEnabled ? inherit : unload。
   - `setProjectTopLevelOverride`（:677-695）：非 inherit 时先推**无前缀**
     pattern（声明覆写对象，builtin 除外）再推 `+/-`；inherit 时清条目。
   - `setProjectPackageOverride`（:704-737）：找不到包新建
     `{source, autoload:false}`（本地路径转相对 project 根，:811-816）；
     过滤全空且 autoload=false ⇒ 整条删除。
   - 继承态读取：`inheritedEnabledByKey`（key＝`{type}:{canonicalizePath}`，
     :850-852/:287-297）＋`getProjectOverrideState`（:747-765）／
     `getOverrideStateFromEntries`（:767-780：`!`/`-` 开头 ⇒ unload，否则 load）。

### T7：启动包更新通知（E6-9 消费面第二消费者，~150 行＋测试）

1. 交互模式启动 fire-and-forget：`checkForAvailableUpdates()` → 非空 ⇒ 聊天区
   插 warning 通知块（pi :4667-4684 文案逐字，`pi update --extensions` ⇒
   `pi-java update --extensions` 既定翻译）；`PI_OFFLINE`/异常 ⇒ 静默（pi
   吞错对位）。
2. 落点：`PiTuiApp` 启动序列（crash-log 启动提示先例，批 15）；虚拟线程
   fire-and-forget。
3. pi 恒 skip 主题臂（startup-ui.ts:81）核查：java 启动主题加载不经 resolve
   单独通道 ⇒ **无对位场景，不造**（与裁决⑤同原则），设计稿记录即销。

---

## §4 不做项与重排

- **self-update 真实通道**（managed install／npm ci 释放目录／Windows
  quarantine）：java 无 package.json 安装形态，docs/20 R29 挂起注记维持，
  随序 21「自更新」一并终裁；本批只落不可用说明臂（裁决②）。
- **`collectExtensionPackageWarnings` 包警告展示**：config TUI 的 group 标签
  与写回用批 18 `ResolvedResource.metadata` 已够；警告展示面（pi
  resource-loader 消费）仍挂后续。
- **SpawnNpmResolver**（npm CLI 臂）：裁决③ seam 维持，有 pnpm/bun 包装需求
  时再插。
- **onMissing TUI 三选**：pi 不存在（§背景摘要），不做不重排。

---

## §5 窄差登记（接续 D112；台账格式随闭环入 docs/04）

| 编号 | 条目 | 性质 |
|---|---|---|
| D113 | npm uninstall/update 的 HTTP 臂＝目录删除／逐包顺装（pi 走 npm CLI `uninstall --prefix` 与批量 `npm install` 多 spec，:1874-1890/:1211-1215） | 内部 |
| D114 | CLI 信任面无交互询问臂：pi install/remove/list/config 经 `resolveProjectTrusted` 交互询问（package-manager-cli.ts:765-811）；java CLI 非交互，直接按已存信任判定＋报错文案 | 模型可见（交互差） |
| D115 | self-update 无安装形态：managed/npm/pnpm 三通道不移植，恒走不可用说明臂（pi `detectInstallMethod` unknown 臂对位；R29 联动） | 模型可见 |
| D116 | onProgress CLI 输出 plain（pi `chalk.dim` 灰显无对位——java CLI 输出无 ANSI 色惯例） | 内部 |

---

## §6 裁决

> 工单版三段式（场景/决定/风险）。①②④ 已经用户拍板（2026-10-24 问答：
> ①全做、②恒不可用臂、④删除旧面）。

**裁决①（config TUI 本批全做——2026-10-24 用户拍板）**
- 【场景】pi `pi config` 是 951 行 TUI（写作用域 Tab／三态覆写／搜索）；并入
  本批则批次规模 ≈ 2,750 行＋测试。
- 【决定】**本批全做**（T6）；B-6/E6-13 翻对齐（+2.0）；批次规模接受。
- 【风险/代价】[内部] 批期拉长；TamboUI 侧的 DynamicBorder/Input/SelectList
  对位件缺口若撞出，随评审回补。[模型可见] `pi-java config` 无参行为从只读
  列表变为开 TUI（pi 对齐），旧列表面退役。

**裁决②（self 臂＝恒不可用说明——2026-10-24 用户拍板通过）**
- 【场景】pi update 默认目标是 self（自更新）；java 从 Maven Central 装，
  无 npm/managed 安装形态，自更新通道是 R29 挂起项（序 21）。
- 【决定】`update` 无参/`self`/`pi` ⇒ 打印 extensions-skipped 提示（逐字）＋
  不可用说明（pi unknown 臂对位，文案按 Maven Central 形态改写）＋exit 1；
  E6-10/B-4 翻存疑（三面缺其一）。
- 【风险/代价】[模型可见] `pi-java update` 裸跑恒失败提示——与 pi 在 unknown
  安装形态下同结局；R29 终裁时若落自更新通道，两臂再翻。

**裁决③（settings 写回＝字段级 merge）**
- 【场景】pi 写盘是字段级 merge（保留未知键）；java 现状 flush/ConfigCommand
  整本写会丢用户 settings.json 里的未知键（批 18 后 packages 键生效，写回
  频次上升，丢键变真实风险）。
- 【决定】T2 新写回面走 `FileSettingsStorage.withLock` 读-改-写 JSON tree，
  只替换 `packages`（及 T5 的四类数组）键；ConfigCommand 一并迁移。
- 【风险/代价】[内部] JSON tree 重写丢**注释与键序**（settings.json 标准 JSON
  无注释，pi `JSON.stringify(merged, null, 2)` 同样重排键序——pi 同结局，
  不登窄差）。

**裁决④（旧 ExtensionPackageManager 删除——2026-10-24 用户拍板通过）**
- 【场景】旧 JAR 拷贝安装面（http 下载 JAR／目录复制）在 pi 无对位——pi 的
  http(s) URL 走 git 源解析；批 18 后会话装配已不读旧目录，CLI 是最后消费者。
- 【决定】删除 `ExtensionPackageManager`＋其测试；`install <url>` 的 http 下载
  JAR 行为随之退役（pi 同行为＝https URL 按 git 源处理）。
- 【风险/代价】[模型可见] **行为变化**：`pi-java install https://…/x.jar` 不再
  下载 JAR（按 git URL 解析，非 git 地址报 `Path does not exist`——pi 同结局）；
  旧 `~/.pi-java/agent/extensions` 目录里的存量 JAR 不再被装配（批 18 已脱钩，
  本批只是删除残留面）。

**裁决⑤（onMissing TUI 三选不做——取证否定）**
- 【场景】docs/20 序 18 按 `MissingSourceAction` 三态类型推测了三选询问 UI；
  取证（全仓 grep）证实 pi 唯一非空回调是 startup-ui.ts:81 恒 `"skip"`，
  `"error"` 臂无调用方。
- 【决定】不做；`resolve(null)` 恒直装现状即 pi 主路径对齐；登取证结论于
  docs/20（序 18 注记修正）。
- 【风险/代价】[内部] 无。

**裁决⑥（并发闸错误语义＝pi detached 首错抛）**
- 【场景】pi `runWithConcurrency` 首错整体抛、其余 worker 后台继续；java
  `StructuredTaskScope` 默认 `ShutdownOnFailure` 会取消其余任务——语义不符。
- 【决定】自写游标 worker-pool（虚拟线程＋AtomicInteger）：收集首异常抛之，
  剩余任务跑到完但不等待不取消（pi detached 对位）。
- 【风险/代价】[内部] 失败时后台任务仍可能写安装目录——pi 同行为，钉死不修。

---

## §7 测试计划

| 任务 | 测试 | 要点 |
|---|---|---|
| T1 | `PackageManagerInstallTest`、`WriteBackChainTest` | install 三型分派＋local 校验文案逐字；remove npm/git/local 三臂；身份去重（npm 忽略版本/git SSH⇄HTTPS/local 相对⇄绝对）；原位替换保 filter；追加序；removeAndPersist 无匹配 ⇒ false；`ConfiguredPackage.filtered`＝对象形判定 |
| T2 | `SettingsWriteBackTest` | 字段级 merge：**未知键保留**（钉死）；裸串来写回裸串/对象形写回对象形（不胀形）；project 臂信任门；withLock 读-改-写并发两写不丢 |
| T3 | `PackageUpdateTest`、`CheckUpdatesTest` | 离线短路；temporary/local/pinned 排除；npm 未安装⇒true／查询失败⇒true（shouldUpdate）vs false（checkAvailable）**反向钉死**；git pinned 参与 update 但 checkAvailable 排除；并发 4 闸（任务数断言最大并行）；首错抛＋其余继续；`Did you mean` 建议文案；HttpServer 夹具＋本地裸仓 file:// 夹具 |
| T4 | `PackageCommandParseTest`、`PackageCommandRunTest` | 解析器真值表（flag 闭集/`-l` 限定/冲突表/默认 self＋skippedNote/`--extension` 单次门/别名）；四 handler 输出文案逐字（含 exitCode）；onProgress START 行输出；--models 短路在 trust 之前（无 settings 夹具可跑）；self 臂不可用说明＋exit 1；帮助文本关键行 |
| T5 | `ConfigCommandPatternTest` | enable ⇒ `+pattern`／disable ⇒ `-pattern` 写回；旧裸串条目剥前缀去重；自动发现条目可关停（resolve 重放验证 enabled=false）；未知键保留 |
| T6 | `ConfigSelectorScreenTest`、`ConfigWriteBackTest` | 双份 resolve（global 份信任恒拒）；group 标签/排序/显示名规则；键位表（跳标题/pageUp 吸附/tab 门）；复选框三态渲染与后缀逐字；搜索过滤保留 group 头；三态循环真值表逐字；toggleTopLevel/Package 写回（升级对象形/折叠回纯串/剥前缀去重）；project 覆写（无前缀声明条目/autoload:false 新建/整条删除）；继承态 dim |
| T7 | `PackageUpdateNoticeTest` | 有更新 ⇒ 通知块文案逐字；离线 ⇒ 静默；异常 ⇒ 静默；fire-and-forget 不阻塞启动 |
| 横切 | 全量 `mvn clean verify` | spotbugs 在 verify 阶段；验证落盘日志看退出码（SIGPIPE 假绿坑）；**评审/构建互斥**（批 18 撞车教训：评审子代理测试跑与后台 verify 互撞 target） |

测试环境守则：全部新测试重定向 home（临时目录）；git 夹具本地 `file://`
裸仓；HTTP 夹具 `HttpServer` loopback；无 git 环境 skip 守卫（批 18 惯例）。

---

## §8 对账表（判定列：前态→后态）

| 行 | 前 | 后 | 证据（实现后回填 commit） |
|---|---|---|---|
| E6-1 `install` 本地源 | 存疑 | 对齐 | T1 install() local 臂＋T4 命令面 |
| E6-2 `install` http(s) URL | 存疑 | 对齐 | T4：https URL 走 git 源（pi parseSource 对位）；旧 JAR 下载臂删（裁决④） |
| E6-10 `update self`/`update pi` | 缺失 | 存疑 | T4 恒不可用臂（裁决②；真实通道挂 R29/序 21） |
| E6-11 `update --extensions` | 缺失 | 对齐 | T3 update 机制＋T4 命令面 |
| E6-12 `update --models` | 缺失 | 对齐 | T4 --models 短路臂（ModelCatalogRefresh 缝） |
| E6-14 持久化到 `settings.packages` | 缺失 | 对齐 | T1 写回链＋T2 字段级 merge 写盘 |
| E6-15 pi manifest 读取 | 存疑 | 对齐 | 终判（§1.5：机制批 18 已落，pi 无独立展示标记） |
| E6-16 `remove`/`uninstall` | 缺失 | 对齐 | T1 remove 三臂＋npm 删除补齐＋T4 命令面 |
| B-1 `install <source> [-l]` | 存疑 | 对齐 | T4 |
| B-2 `remove <source> [-l]` | 存疑 | 对齐 | T4 |
| B-4 `update [source\|self\|pi]` | 缺失 | 存疑 | T4（extensions/models 落、self 恒不可用——三面缺其一） |
| B-5 `list` | 存疑 | 对齐 | T4（分组/installedPath/`(filtered)` 逐字） |
| E6-13 `config` TUI | 存疑 | 对齐 | T5 非交互 +/- 语义＋T6 TUI 本体（裁决①） |
| B-6 `config [-l]` | 存疑 | 对齐 | 同上 |

E6 域后态：对齐 15／存疑 1（E6-10）／缺失 0＝**30.0/31 ≈ 96.8%**。
B 域后态：对齐 7／存疑 1（#4）／缺失 0＝**14.0/15 ≈ 93.3%**。

---

## §9 闭环回填

**闭环日 2026-10-24；commits**（main，序）：

1. `3ec1254d` T2 SettingsManager 写回面（字段级 merge）＋setProjectTrusted 不抹盘修真
2. `2d51a862` T1 写回链＋公开安装面
3. `ad82fc6f` T3 update 机制
4. `bcf2be5b` T4 CLI 面重裁＋旧 JAR 安装面删除
5. `40ec8599` T5 config pattern 代数
6. `32fda675` T6 config TUI 本体
7. `2e824f50` T7 启动包更新通知
8. `aef75931` 扫尾（SessionSetup 残留 import 清理）
9. `15889c5e` spotbugs 回补（toggleProject Optional）

**实际翻动（与 §0 预期一致，+18.0）**：E6-1/E6-2/E6-13/E6-15 存疑→对齐、
E6-11/E6-12/E6-14/E6-16 缺失→对齐、E6-10 缺失→存疑、B-1/B-2/B-5/B-6 存疑→对齐、
B-4 缺失→存疑。E6 域 17.0→**30.0/31 ≈ 96.8%**（缺失清零）、B 域 9.0→**14.0/15 ≈
93.3%**、模块 370.0→**388.0/485 ≈ 80.0%**、全仓 1,051.5→**1,069.5/1379 ≈ 77.6%**。
**w3 账面滞后修正**：批 18 的 w3 #18/#23（npm 源、包源复合）翻对齐当时漏入 w3 表，
连同本批 #19 ⇒ w3 73.9%（17/23）→ **87.0%（20/23）**。

**过程修真/回补**（无独立评审环节，verify 驱动）：

- **修真（设计外新发现）**：`SettingsManager.setProjectTrusted(false)` 旧实现
  `storage.writeProject(new Settings())` 整本抹项目 settings 文件——pi 对位
  （settings-manager.ts:473-496）纯内存清空＋信任恢复重读；未信任项目里跑
  `list`/`update` 曾数据丢失。注册进 docs/04 批 19 项。
- **spotbugs 回补 1 钉**：`toggleProject` Boolean 返 null ⇒ Optional
  （NP_BOOLEAN_RETURN_NULL，批 agent-core-4 坑再验证——verify 阶段才跑）。
- **测试断言平台化**：node `path.relative` 在 Windows 产反斜杠——pattern 断言改
  `rel()`/`Path.of` 形（pi 同形，行为无差）。
- **pi quirk 钉死**：toggle 臂恒推一枚 pattern ⇒ global 臂 `hasFilters=false`
  折叠不可达（project 臂可达，有测试）；`getOverrideStateFromEntries` 空表 unload
  仅在 `autoload !== false` 时（初版测试期望错，取证改钉）。
- **环境坑复现**：`mvn test -pl <下游>` 不带 `-am` 拾 .m2 旧构件假红
  （ChatScreen NCDFE）；`-Dtest` 跨模块要配 `-Dsurefire.failIfNoSpecifiedTests=false`。

**行为变化注记**（changelog 级）：见 docs/04 批 19 项【风险/代价】段
（install https URL 不下载 JAR／config 无参开 TUI／update 裸跑恒不可用臂／
enable/disable 写回 `+`/`-` 前缀／启动包更新通知块／--help flag 收集走 resolve／
未信任项目 settings 不再被抹空）。

**回填核对（四处）**：docs/04（批 19 项＋D113–D116＋E1/E6 行数）✔／
docs/05（总表行＋w3 注记＋§2.4 批 19 条目）✔／map/04（B 域 5 行＋E6 域 9 行＋
两汇总＋去重口径＋w3 单元表 #18/#19/#23）✔／docs/20（§1 总览＋§2 批 19 行＋
§3 序 18 闭环＋onMissing 取证修正注记）✔。

**留白**：self-update 真实通道（R29/序 21）；`SpawnNpmResolver` seam；
config TUI 包警告展示面（`collectExtensionPackageWarnings` 消费）；启动恒 skip
主题臂无对位场景（T7.3，核查销案）。
