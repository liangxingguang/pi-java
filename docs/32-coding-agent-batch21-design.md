# coding-agent 批 21 设计稿：E1/E3/E4 尾＋JSON 主题系统（2,417 行域）＋A-29＋主题运行期重载

【背景摘要】本批是 coding-agent 对齐批次的第 21 批（总账 docs/20 §3 序 20），
也是**主题域总攻**：pi 的主题/色彩系统 2,417 行（`theme.ts` 1,086＋
`system-theme.ts` 655＋`theme-controller.ts` 253＋`theme-schema.ts` 146＋
`theme-tokens.ts` 121＋dark/light.json 156；色数学在 pi-tui `colors.ts` 367＋
`oklab.ts` 233，合计 3,017 行域）整体移植，顶替 java 侧 74 行的 `.tcss` 近似
（B163）。收编六块：① **E4-4**——JSON 主题发现（schema 校验＋目录扫描＋
碰撞诊断）与主题子系统本体；② **E2-9＋A-29**——`theme` 键语义换成 pi 的
「JSON 主题名/`light/dark` 对」、`--theme` 重裁为纯路径来源（删 `.tcss` 选型）；
③ **主题运行期重载**——`/reload` 主题臂（批 16 撞出挂账，裁决⑨归本批）＋
活跃自定义主题的文件监视热重载；④ **map/05 S9**——设置页 Theme 子菜单
（single/automatic 两模式＋预览）；⑤ **E1 尾**——E1-3 auth.json 读改同锁
修真、E1-4/E1-5 核销；⑥ **E3 尾**——E3-15/E3-23 核销（台账滞后嫌疑，§1.12
取证）＋E4-6 系统提示词文件发现。批 20 闭环后模块账面 392.0/485 ≈ 80.8%。

术语（首现释义）：

- **主题令牌（theme token）**：pi 主题的 57 个命名色槽（`accent`/`muted`/
  `userMessageBg`/…），每令牌属 `foreground` 或 `background` 槽位；5 个可选
  令牌缺席时回落另一令牌（`scrollbarTrack→muted` 等，themes.md:120-128）。
- **terminal default**：色值写 `""`＝终端自己的默认前景/背景色（SGR 39/49）；
  槽位决定 `""` 指前景还是背景。
- **OKHSL/OKLCH**：感知均匀色彩空间；pi 内建主题全部用 `okhsl(...)` 书写，
  色数学（Björn Ottosson 参考实现移植）在 pi-tui `oklab.ts`/`colors.ts`。
- **system 主题**：保留名 `system`，无文件，由终端上报色（OSC 10/11/4）
  实时生成；终端什么都不报时退化为 ANSI 索引色＋dim 令牌（tier-3）。
- **自动模式**：`theme` 设置值形如 `"light/dark"`（一斜杠两名），按终端
  外观二选一；`/` 因此是主题名保留字符。
- **TCSS**：TamboUI 的样式表格式（java 自有层，pi 无对应物）。
- **RMW**：read-modify-write（读-改-写）——凭证文件的原子更新链路。

---

## §0 基线与预期翻动

| 指标 | 前（批 20 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485，去重） | 392.0 ≈ 80.8% | **404.5 ≈ 83.4%**（+12.5） |
| E1 域（Σ 10） | 6.0 ≈ 60.0% | **10.0 = 100%**（满分域之七） |
| E3 域（Σ 40） | 37.0 ≈ 92.5% | **40.0 = 100%**（满分域之八） |
| E4 域（Σ 14） | 10.0 ≈ 71.4% | **14.0 = 100%**（满分域之九） |
| A 域（Σ 62） | 61.5 ≈ 99.2% | **62.0 = 100%**（满分域之十） |
| E2 域（Σ 84） | 80.5 ≈ 95.8% | 81.5 ≈ **97.0%**（E2-9） |
| map/05 tui/web（Σ 245） | 120.5 ≈ 49.2% | 121.5 ≈ **49.6%**（S9） |
| 全仓（Σ 1379） | 1,073.5 ≈ 77.8% | **1,087.0 ≈ 78.8%**（+13.5） |

翻动行（9 行，+13.5；存疑计半 c=0.5）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|---|
| E1-3 `auth.json` | 2 | 存疑 | 对齐 | T2（读改同锁修真＋窄差登记） |
| E1-4 `models.json` | 2 | 存疑 | 对齐 | T1d（核销：F6 症状已过期，§1.12 验证） |
| E1-5 pi manifest | 2 | 缺失 | 对齐 | T1a（核销：批 18 已落 `PiManifest`） |
| E2-9 `theme` 键 | 2 | 存疑 | 对齐 | T7/T9/T11（值语义＝主题名/对） |
| E3-15 分支摘要 | 2 | 缺失 | 对齐 | T1b（核销：B1 族 agent-core 批 4 已落） |
| E3-23 会话元数据变更事件 | 1 | 缺失 | 对齐 | T1c（核销：生产者已在 `AgentSession:1237`） |
| E4-4 theme 发现 | 2 | 缺失 | 对齐 | T4–T8（主题子系统本体） |
| E4-6 系统提示词文件发现 | 2 | 缺失 | 对齐 | T3（SYSTEM.md/APPEND_SYSTEM.md＋file-or-literal） |
| A-29 `--theme` | 1 | 存疑 | 对齐 | T9（重裁为路径来源） |
| map/05 S9 theme 选择器 | 1 | 缺失 | 对齐 | T11（设置页 Theme 子菜单） |

台账销号预期：**B163** 销号（主题/色彩系统差距）；E1-4 的 **F6** 注记核销
（症状过期）。无新 B 预期（若核销验证翻车则即登即销先例 B257）。

不动行：E2 其余、E5/E6/B/C/D 域全部、map/05 其余屏行。E3-11（导出 HTML）
判定不动——主题变量注入是 B163 范围内的保真提升，不翻行。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`）

### 1.1 主题文件格式与校验（theme-tokens.ts / theme-schema.ts）

**令牌表**（theme-tokens.ts:24-101）：57 令牌，每令牌 `{slot, description,
fallback?}`；`defineThemeTokens` 校验 fallback 存在且同槽位。5 个可选令牌
（有 fallback）：`scrollbarTrack→muted`、`scrollbarThumb→text`、
`searchMatchBg→selectedBg`、`searchMatchText→text`、`thinkingMax→thinkingXhigh`。
槽位分布：`background` 槽 8 个（`selectedBg/searchMatchBg/userMessageBg/
customMessageBg/toolPendingBg/toolSuccessBg/toolErrorBg`＋不算……实为 7＋
`searchMatchBg`），其余全 foreground。**实现时从锚点文件逐字抄表**
（`git show ea448f454:packages/coding-agent/src/modes/interactive/theme/theme-tokens.ts`），
不在本稿重复 57 行字面量。

**色值六形**（themes.md:83-94）：`#rgb`/`#rrggbb`、`oklch(L% C H)`、
`okhsl(H S% L%)`、256 索引整数 0-255、变量引用（`vars` 键名）、`""` 终端默认。

**严格校验**（theme-schema.ts）：typebox `additionalProperties: false`——顶层只收
`$schema/name/appearance/vars/colors/export`，`colors` 只收已知令牌，`export`
只收 `pageBg/cardBg/infoBg`。java 无 typebox（17MB 模块图是 pi 的懒装理由，
对 java 不成立 ⇒ **裁决②**：Jackson 手写校验**恒装**，`setThemeJsonValidator`
缝不移植）。校验错误文案逐字（theme-schema.ts:104-142）：

```ts
// 名字含 "/" 快检（先于 schema）：
throw new Error(
    `Invalid theme name "${json.name}": theme names cannot contain "/" because it is reserved for automatic light/dark theme settings.`);

// schema 不过时的错误组装：
let errorMessage = `Invalid theme "${label}":\n`;
if (missingColors.size > 0) {
    errorMessage += "\nMissing required color tokens:\n";
    errorMessage += Array.from(missingColors).sort().map((color) => `  - ${color}`).join("\n");
    errorMessage += '\n\nPlease add these colors to your theme\'s "colors" object.';
    errorMessage += "\nSee the built-in themes (dark.json, light.json) for reference values.";
}
if (otherErrors.length > 0) {
    errorMessage += `\n\nOther errors:\n${otherErrors.join("\n")}`;   // 每条 `  - ${path}: ${message}`
}
```

`missingColors` 集 = `colors` 对象缺的**必填**令牌（52 个）；`otherErrors` 每条
形如 `  - /colors/accent: ...`。java 手写校验产出同构文案（逐条 path 措辞与
typebox 不同 ⇒ 窄差 D125）。

**validator 缺席时的兜底**（theme.ts:469-475）：无校验器只查「对象且有 colors
键」：`Invalid theme "${label}": expected an object with a "colors" map.`——java
恒装校验器，此臂只剩名字快检之前的形状检查（保留同文案）。

### 1.2 Theme 类与色彩数学（theme.ts / pi-tui colors.ts / oklab.ts）

**变量引用解析**（theme.ts:79-106，逐字）：

```ts
function resolveVarRefs(value: ColorValue, vars: Record<string, ColorValue>,
                        visited = new Set<string>()): string | number {
    if (typeof value === "number" || value === "" || value.startsWith("#") || /^ok(lch|hsl)\(/i.test(value)) {
        return value;
    }
    if (visited.has(value)) throw new Error(`Circular variable reference detected: ${value}`);
    if (!(value in vars)) throw new Error(`Variable reference not found: ${value}`);
    visited.add(value);
    return resolveVarRefs(vars[value], vars, visited);
}
```

**fallback 填平**（theme.ts:108-122）：缺席可选令牌取 fallback 令牌的**已填**
值；fallback 也未解析 ⇒ `Theme token ${rawName} has unresolved fallback
${descriptor.fallback}`。

**Theme 类**（theme.ts:185-373）要点：

- 构造：填平 → 按槽位分 fg/bg 两表 → 每令牌预算 ANSI 逃逸串缓存
  （`""` ⇒ `\x1b[39m`/`\x1b[49m` 且记入 defaultForeground/BackgroundTokens；
  否则 `parseColor` 进 `concreteColors`）。
- `appearance`：声明值 ?? `detectAppearance`（前景/背景平均 OKLab 亮度比较，
  :164-179）?? 终端外观。
- `colors` getter：terminal default 令牌用终端上报默认色（未上报 ⇒ 按
  appearance 猜 `#e5e5e7`/`#000000` 或 `#000000`/`#ffffff`）；dim 令牌色向
  背景混 0.4（`mixColors(color, background, 0.4)`）；按 terminalColors 身份缓存。
- `fg(token, text)`：`${ansi}${text}\x1b[39m`，dim 令牌 `${ansi}\x1b[2m${text}\x1b[22;39m`；
  `bg` 同理 `\x1b[49m`；未知令牌抛 `Unknown theme color: ${token}`。
- `getThinkingBorderColor(level)`：off/minimal/low/medium/high/xhigh/max →
  `thinkingOff…thinkingMax` 七令牌；`getBashModeBorderColor()` → `bashMode`。

**色数学**（colors.ts 367 行＋oklab.ts 233 行，**全量逐字移植**）：

- `Color = IndexedColor(index) | RgbColorValue(r,g,b) | OklchColorValue(l,c,h)`——
  java 落 `sealed interface ThemeColor`＋三 record（**Erasable Java 形态裁决**：
  变体携带不同字段 ⇒ sealed+record，不用 enum）。
- `parseColor`（:121-148）：hex 三位扩六位；OKLCH/OKHSL 正则
  （`NUMBER_PATTERN = [+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?`，`%`/`deg`
  可选，大小写不敏感）；`Invalid color value: ${value}`。
- `oklchToRgb` 色域映射（:190-216）：超色域沿色相固定方向二分降 chroma
  20 轮，锚定无色点为回落。
- `rgbToAnsi256`（:279-297）：立方格最近邻 vs 灰阶梯最近邻（加权距离
  0.299/0.587/0.114，spread<10 且灰更近才取灰）。
- `styleTextWithAnsi`（:330-367，逐字——属性开闭序：fg/bg→bold/dim→italic→
  underline→inverse→strikethrough，关闭码逆序前置 `39/49/22/23/24/27/29`）。
- oklab.ts：OKHSL↔sRGB（cusp/maxChroma/chromaStops/okhslToRgb/rgbToOkhsl）、
  OKLab↔线性 sRGB 矩阵。**逐字移植含全部常量表**；MIT 头注保留。

### 1.3 system 主题生成器（system-theme.ts 655 行）

**全量逐字移植**。结构：14 色族（`FAMILIES`：hue/saturation{min,max}/ANSI slot）、
`TOKEN_FAMILIES`（57 令牌→族）、`TOKEN_SLOTS`（3 令牌特判槽位）、13 条对比度
曲线 `LEVELS`（每条 dark/light 各 6 系数＋reachable 区间）、`RULES`（令牌×
面×等级）、`SOLVE_ORDER`（拓扑序）、`generateSystemThemeColors`（松弛二分
20 轮）、`terminalAppearance`（WCAG 对比＋前景方向）、`withTextContrast`
（4.5:1 下限）、`indexedColors`（tier-3：面板无底色、非 neutral 且
saturation>0 给 ANSI 槽位索引、neutral 非 FOREGROUND_TOKENS 进 dim 表）。

**java 侧终端输入恒空**（裁决③：TamboUI 无 OSC 10/11/4 查询面）⇒ 实际永远
走 `indexedColors(saturation=1, appearanceHint=detectTerminalTheme({}))` 臂；
`saturation` 灰度窗（100ms pending）不存在。生成器结构仍全留——终端色
注入缝（`Themes.setTerminalColors`）做成公开静态口，未来后端支持即通。

### 1.4 全局注册表／监视器／控制器（theme.ts:643-819＋theme-controller.ts）

**全局单例**（pi 用 globalThis Symbol 跨模块加载器；java 落 `Themes` 静态
持有者——模块内唯一类加载器，天然同义）：

```ts
export function initTheme(themeName?: string, enableWatcher: boolean = false): void {
    const name = themeName ?? SYSTEM_THEME_NAME;
    currentThemeName = name;
    try {
        setGlobalTheme(loadTheme(name));
        if (enableWatcher) startThemeWatcher();
    } catch (_error) {
        // Theme is invalid - fall back to the system theme silently
        currentThemeName = SYSTEM_THEME_NAME;
        setGlobalTheme(loadTheme(SYSTEM_THEME_NAME));
    }
}
export function setTheme(name: string, enableWatcher = false): { success: boolean; error?: string } {
    currentThemeName = name;
    try {
        setGlobalTheme(loadTheme(name));
        if (enableWatcher) startThemeWatcher();
        if (onThemeChangeCallback) onThemeChangeCallback();
        return { success: true };
    } catch (error) { /* 回落 system；return { success:false, error } */ }
}
```

`loadTheme` 序（theme.ts:557-566）：`system` 保留名恒先生成（同名自定义被
压）→ `registeredThemes` 命中直返 → `loadThemeJson`（内建 → 注册表 sourcePath
重读 → `<agentDir>/themes/<name>.json`；缺 ⇒ `Theme not found: ${name}`）。
`setRegisteredThemes`（:672-680）：清表重灌，名前过 `assertThemeNameIsValid`。

**主题设置解析**（theme.ts:578-641，逐字）：

```ts
export function parseAutoThemeSetting(themeSetting) {
    if (!themeSetting) return undefined;
    const slashIndex = themeSetting.indexOf("/");
    if (slashIndex === -1 || themeSetting.indexOf("/", slashIndex + 1) !== -1) return undefined;
    const lightTheme = themeSetting.slice(0, slashIndex).trim();
    const darkTheme = themeSetting.slice(slashIndex + 1).trim();
    if (!lightTheme || !darkTheme) return undefined;
    return { lightTheme, darkTheme };
}
export function resolveThemeSetting(themeSetting, terminalTheme) {
    const autoTheme = parseAutoThemeSetting(themeSetting);
    if (autoTheme) return terminalTheme === "light" ? autoTheme.lightTheme : autoTheme.darkTheme;
    if (themeSetting?.includes("/")) return undefined;   // 畸形对 ⇒ 未定义
    if (typeof themeSetting === "string") return themeSetting;
    return undefined;
}
// COLORFGBG：末段 0-6/8 ⇒ dark，7/9-15 ⇒ light，其余/缺席 ⇒ undefined
// detectTerminalTheme：有 background ⇒ terminalAppearance(bg, fg)；否则
//   reportedScheme ?? detectColorFgBgTheme() ?? "dark"
```

**文件监视热重载**（theme.ts:734-819，逐字语义）：只监视**当前名**为自定义
（非 dark/light/system）且 `<agentDir>/themes/<name>.json` 存在时；100ms 防抖；
陈旧定时器（已切主题）丢弃；文件暂时消失保持旧主题；重载成功 ⇒ 注册表
缓存原位替换＋全局替换＋`onThemeChange`；解析失败静默（编辑中间态）。java
落 `WatchService`＋`ScheduledExecutorService` 防抖（虚拟线程单例调度器）。

**控制器**（theme-controller.ts 253 行）：java 裁掉终端查询面（裁决③）后剩：
`currentThemeSetting`（--use-theme 注入的 initialThemeSetting 优先于 settings）、
`applyFromSettings()`（解析设置→`setTheme(name, watcher=true)`）、
`setThemeName/setThemeSetting/setThemeInstance`、`preview(name)`（=setTheme＋
onChanged 回调，不持久）、`getThemeSelection()`（currentSetting ?? settings ??
activeName）、`getTerminalTheme()`。`autoSync`/2031 监听/`waitForTerminalColors`
全裁（窄差 D123）。

### 1.5 发现与装配（resource-loader.ts）

`updateThemesFromPaths`（:891-914）：`colorMode` 探测（跳过 hyperlink 探测）
→ `loadThemes(paths, false, colorMode)` → `dedupeThemes` → sourceInfo 回填。
`loadThemes`（:1043-1088）：路径不存在 ⇒ `warning "theme path does not exist"`；
目录 ⇒ 扫 `*.json`（符号链接跟随，破链跳过）；文件非 .json ⇒ `warning
"theme path is not a json file"`；加载失败 ⇒ `warning <message>`。
`dedupeThemes`（逐字）：同名（`t.name ?? "unnamed"`）先到先得，后到者落
`collision` 诊断 `name "${name}" collision`（winnerPath/loserPath 缺源路径时
`<builtin>`）。**默认目录**（`<agentDir>/themes`、`./.pi/themes`）不进
resource-loader 的 loadThemes——由 package-manager resolve 的
`collectAutoThemeEntries` 自动发现（批 18 java 已对齐：`PackageResourceScanner.
collectAutoThemeEntries`）；`<agentDir>/themes` 另被 theme.ts 的
`getCustomThemeInfos`/`loadThemeJson` 独立扫描（按名查找与可选列表）。

### 1.6 设置页 Theme 子菜单（settings-selector.ts:205-455）

- `themeItems`：label `✓ name`（当前）/`  name`，system 带描述
  `"Theme created from your terminal's colors"`。
- `singleModeThemeItems`：system 置顶 → `automatic` 项（value `"/"`，描述
  `"Use separate themes for light and dark terminal appearance"`）→ 其余。
- `ThemeSubmenu`：构造时 `parseAutoThemeSetting` 决定 single/automatic 模式；
  single 菜单选中即 `apply(value)`（持久＋退出）；选 `automatic` 切模式＋预览
  当前对；automatic 菜单含 `Light theme`/`Dark theme` 两子选择（选中回写对应
  半对＋预览）、`Apply`（持久 `"light/dark"` 串）、`Change mode`（切回 single
  并预览当前激活半对）；Esc/cancel ⇒ 预览**原设置**后退出。
- 预览钩：`onThemePreview(nameOrPair)` → controller.preview（不持久）。
- 设置页行（:749-756）：`{id:"theme", label:"Theme", description:"Color theme
  for the interface", currentValue: config.currentTheme, submenu: …}`。

### 1.7 `--theme`／`--use-theme`／settings `theme` 键

```ts
// cli/args.ts:199-209（逐字）
} else if (arg === "--theme" && i + 1 < args.length) {
    result.themes = result.themes ?? [];
    result.themes.push(args[++i]);          // 路径来源，不是选型
} else if (arg === "--use-theme") {
    const themeName = args[i + 1];
    if (themeName === undefined || themeName.startsWith("-")) {
        result.diagnostics.push({ type: "error", message: "--use-theme requires a theme name" });
    } else { result.useTheme = themeName; i++; }
}
// main.ts:681-683：interactive 且 useTheme 在场 ⇒ startupSettingsManager.applyOverrides({ theme })
// main.ts:905：initTheme(settingsManager.getTheme(), appMode === "interactive")
```

settings-manager.ts:741-757（逐字已在 §1 尾注）：`getThemeSetting()` 原样返
`settings.theme`（string 才返）；`getTheme()` 含 `/` ⇒ `undefined`（给 initTheme
用——对形不在启动直读，经 resolveThemeSetting 解析）；`setTheme(theme)` 写
全局域＋flush。java `SettingsAccessors:87-94` 已有同形两件（缺 `/` 特判拆分 ⇒ T9 补
`getThemeSetting`/`getTheme` 双访问器对位）。

### 1.8 `/reload` 主题臂（interactive-mode.ts:6486-6492）

```ts
await this.session.reload({ beforeSessionStart: restoreChatBeforeSessionStart });
restoreChatBeforeSessionStart();
this.keybindings.reload();
// …header…
setRegisteredThemes(this.session.resourceLoader.getThemes().themes);
this.applyRuntimeSettings();
this.themeController.applyFromSettings();
```

java 对位：`SessionReload` ⑤ 增主题重扫（重 resolve → `ThemeLoader` →
`Themes.setRegisteredThemes`＋诊断落 startupDiagnostics），TUI `/reload` 收尾调
`ThemeController.applyFromSettings()`＋重渲染。会话层不直接碰全局注册表——
**裁决⑩**：`SessionReload` 只重算 `owner.themes`，注册表写入与控制器应用在
模式层（PiTuiApp/RPC 各有收尾点），保持会话层 toolkit 中立。

### 1.9 HTML 导出主题变量（export-html/index.ts:81-127）

```ts
function generateThemeVars(themeName?: string): string {
    const colors = getResolvedThemeColors(themeName);         // 57 令牌 → hex
    // …每键一行 --token: hex;
    const themeExport = getThemeExportColors(themeName);       // export{pageBg,cardBg,infoBg}
    const userMessageBg = colors.userMessageBg || "#343541";
    const derivedColors = deriveExportColors(userMessageBg);   // 无 export 节时派生
    lines.push(`--exportPageBg: ${themeExport.pageBg ?? derivedColors.pageBg};`);
    // …cardBg/infoBg 同
}
// deriveExportColors：base 亮度 ≥0.5 ⇒ pageBg=0.96×/cardBg=base/infoBg=1.15×；
//   否则 0.7×/0.85×/1.3×（adjustBrightness 逐通道 clamp 255）
// getThemeExportColors（theme.ts:845-876）：system ⇒ {}；export 节 var 引用解析；
//   number ⇒ 索引色转 hex；"" ⇒ undefined；okhsl( ⇒ 转 hex（CSS 不支持 okhsl）
```

java `HtmlExporter` 的 `:root` 硬编码 10 变量改由活跃主题（或具名主题）解析
注入；模板 var 名保持 java 现状（`--page/--card/--user/--accent/--text/--muted/
--error/--success/--border/--code/--heading`）⇒ 建立 pi 令牌→java var 映射
（page←exportPageBg、card←exportCardBg、user←userMessageBg、accent←accent、
text←text、muted←muted、error←error、success←success、border←border、
code←mdCode、heading←mdHeading；`--infoBg` 新增进 system-note 背景?——不，
java 模板无 info 块背景消费点，只注变量不增样式）。

### 1.10 E4-6 系统提示词文件发现（resource-loader.ts:1213-1240）

```ts
private discoverSystemPromptFile(): string | undefined {
    const projectPath = join(this.cwd, CONFIG_DIR_NAME, "SYSTEM.md");   // .pi/SYSTEM.md
    if (this.settingsManager.isProjectTrusted() && existsSync(projectPath)) return projectPath;
    const globalPath = join(this.agentDir, "SYSTEM.md");
    if (existsSync(globalPath)) return globalPath;
    return undefined;
}
// discoverAppendSystemPromptFile 同形（APPEND_SYSTEM.md）
```

装配序（:656-671）：`systemPromptSource = CLI 值 ?? discoverSystemPromptFile()`；
`resolvePromptInput(source, "system prompt")`——**file-or-literal**：源是现存路径
⇒ 读文件内容（读失败告警后按字面量用）；否则按字面量。append 侧：CLI
`--append-system-prompt` 全缺席才走发现；多源各过 `resolvePromptInput`。
读失败告警文案（:176）：`Warning: Could not read ${description} file ${input}: ${error}`
（java 进 startupDiagnostics，stderr 通道与 pi print 模式同）。

**语义注意**：发现的 SYSTEM.md 进的是 `systemPromptSource`＝**替换型**
customPrompt（抑制 tools/rules/docs 三段），不是追加。java `CONFIG_DIR_NAME`
对位是 `.pi-java`（B182 族既定窄差）。

### 1.11 E1-3 auth.json（auth-storage.ts:1-160/440-510）

pi 的 RMW 全在锁内：`withLock(fn)`——lockfile 锁（同步重试 10×20ms；异步
stale 30s）→ 读当前内容 → `fn(current)` 返 `{result, next}` → 同锁写回。
另有 `getFileRevision`（`dev:ino:size:mtimeNs:ctimeNs`）做进程内读缓存失效
判定＋多实例共享读态（`sharedAuthFileReadState`）＋reload 合并（reader 计数）。
java `FileCredentialStore`：写侧有 `FileChannel.lock()`，但 **readAll 无锁且
与 writeAll 分两次调用** ⇒ 跨进程丢更新窗（storeApiKey：读-改-写非原子）。
**修真**（T2）：RMW 收进同一把 `FileChannel` 锁；读也过共享锁（`lock(0,
Long.MAX_VALUE, true)`）。revision 缓存/共享读态/stale 检测**不移植**（java 无
进程内读缓存，每次盘读即新鲜，pi 的缓存是性能优化；异步 API java 无对应面）
⇒ 窄差 D131。`OAuthCredentialStore` 同修真（auth-oauth.json）。

### 1.12 核销证据（E1-5／E3-15／E3-23／E1-4）

- **E1-5 PiManifest**：java `pkg/PiManifest.java` 已是 pi-manifest.ts 35 行逐字
  对位（四资源键/全字符串数组/非对象⇒null/BOM），批 18 接进 resolve 链
  （`PackageResourceScanner`/`PackageResourceCollector`）。map 行未翻＝台账滞后。
- **E3-15 分支摘要**：agent-core 批 4 已销 B1（生成器＋navigateTree＋设置三
  件，`CompactionService:363` 有 `Entry.BranchSummary` 消费，TUI 有
  `TreeNavigationFlow` skipPrompt 门）。pi 证据 `branch-summarization.ts:349-353`
  是 LLM 调用段——java 对位在 `LlmSummaryGenerator`。本批改注翻行。
- **E3-23 session_info_changed**：生产者在 `AgentSession.java:1237`
  （`emitSessionEvent(new SessionInfoChanged(normalized))`——命名写回链），
  扩展桥 `SessionEventBridge:123-124` 批 4 已接；map/04 台账纠错区 :587 已销
  B30。E3-23 行未翻＝台账滞后。
- **E1-4 models.json**：F6 注记「`Args.baseUrl` 在 coding-agent 侧不消费」已
  过期——`DefaultProviders.java:429` `firstNonBlank(args.baseUrl(),
  settings.defaultBaseUrl)`＋:225 per-model baseUrl 投影（批 20 D-P1）＋
  `ModelsJsonProvider.java:44-58` 自述贯通。三件对位（ModelsJsonProvider/
  RemoteCatalogProvider 326 行/ModelCatalogRefresh）俱在。T1d 复核
  models-store 的 revision/lock 面是否引残余窄差后翻行。

---

## §2 java 侧现状（取证 2026-10-25，main `5bb99efd`）

| 面 | 现状 |
|---|---|
| 主题 | `PiTheme`（tui，113 行）：两个内建 `.tcss`（各 37 行）＋`.tcss` 文件自定义加载；`resolveTheme` CLI→settings→扩展候选→dark |
| 语义色 | `SemanticColors`（tui）：10 个 `public static final` TamboUI 近似色常量（ACCENT=cyan…），**130 调用点**（tui 主源） |
| `--theme` | `ArgsParser` picocli `@Option List<String> themes`（形状已对）——但 `PiTuiLauncher.themeFrom:285-286` 把 `themes.get(0)` 当**选型**误用（pi 是纯路径） |
| `--use-theme` | 批 14 已落（诊断两同文＋themeFrom 优先＋选择器 SPI 覆盖） |
| `theme` 键 | `Settings.java:36` 字段＋`SettingsAccessors:87-94` get/set（无 `/` 对形拆分） |
| themePaths | `AgentSession:953-960` 已从 resolve 装配（CLI temporary∪resolved，noThemes 裁 resolve 臂）＋`:1319 themePaths()` 读面；**只有路径没有加载** |
| SessionReload | 七步序齐备，⑤ 注释「themes 随批 20（裁决⑨）」＝本批接 |
| 设置页 | `SettingsScreen:56-57` Theme 行循环 `dark/light`；子菜单先例 `modelThinkingRequested` 检出位 |
| HTML 导出 | `HtmlExporter` CSS `:root` 10 变量硬编码深色 |
| 凭证存储 | `FileCredentialStore`（写锁、RMW 非原子）＋`OAuthCredentialStore`（auth-oauth.json） |
| 系统提示 | `SessionSetup.customPromptFor/appendSystemPromptFor` 只吃 CLI 字面量，无文件发现、无 file-or-literal |
| 终端能力 | TamboUI 无 OSC 10/11/4 查询、无 2031 通知；`Color` 有 `indexed/rgb/hex/RESET`＋`indexedToRgb` |
| StyleEngine | `loadStylesheet(name, String content)` 可从生成文本装载＋`reloadStylesheet(name)`＋变更监听 |

模块归属约束：coding-agent **不依赖** TamboUI；tui → coding-agent 单向。
⇒ 主题核（toolkit 中立：自带 `ThemeColor` ADT＋ANSI/hex 产出）落
`pi-java-coding-agent` 新包 `com.pijava.coding.agent.theme`；TamboUI 适配
（令牌→`Style`/`Color`、TCSS 生成）落 `pi-java-tui` 的 `theme` 包。

---

## §3 任务分解

> 行数是 Java 侧估算（含 javadoc），测试另计。依赖序即执行序。

### T1：核销四行（E1-5/E3-15/E3-23/E1-4，~40 行测试）

- T1a E1-5：补 parity 测试（`PiManifest` 四键/非字符串键跳过/非对象⇒null/
  BOM）若缺 ⇒ 翻行。
- T1b E3-15：验证链（`LlmSummaryGenerator` 分支摘要生成＋`TreeNavigationFlow`
  消费＋`branchSummary` 设置门）已有测试覆盖 ⇒ 翻行改注。
- T1c E3-23：验证 `AgentSession:1237` 生产者＋RPC/Web 消费 ⇒ 翻行改注。
- T1d E1-4：复核 `models-store` 对位面（java 无 FileModelsStore 锁/revision——
  远端目录刷新走 `ModelCatalogRefresh` 原子写？验证；有残余 ⇒ 窄差登记，
  不挡翻行——F6 注记的症状已消）。

### T2：E1-3 auth.json 读改同锁（ai 模块，~80 行＋测试）

- `FileCredentialStore`：`readAll` 进共享锁；`storeApiKey/deleteApiKey`（含
  profile 形）改 RMW 在单一 `FileChannel` 独占锁内（开 READ+WRITE 通道，
  不 TRUNCATE 直到写时点）。`resolveApiKey` 读保持共享锁。
- `OAuthCredentialStore` 同构修真。
- 并发夹具：两 store 实例交错 store/delete 50 轮断言无丢更新。
- 窄差 D131（revision 缓存/stale/共享读态不移植）。

### T3：E4-6 系统提示词文件发现（~120 行＋测试）

- `SessionSetup` 增 `resolvePromptInput(String source, String description)`
  （pi :167-182 逐字：现存路径⇒读文件〔BOM 剥离〕，读失败 ⇒ 告警进
  诊断＋按字面量；否则字面量）。
- `customPromptFor(args)` 改：`--system-prompt` ?? `discoverSystemPromptFile()`
  （项目 `.pi-java/SYSTEM.md`〔`isProjectTrusted` 门〕→ `~/.pi-java/agent/SYSTEM.md`——
  目录取 java 既定 agentDir 缝）；结果过 `resolvePromptInput`。
- `appendSystemPromptFor(args)` 改：CLI 全缺席 ⇒ `discoverAppendSystemPromptFile()`
  单源；多源各过 `resolvePromptInput` 后 `join("\n\n")`。
- `isProjectTrusted` 消费点：`SettingsManager` 信任解析（批 18/19 已有
  `resolveTrust`/`setProjectTrusted` 面）读当前 cwd 项目信任。
- 发现须同时覆盖 `AgentSession` 初装与 `SessionReload`（pi resourceLoader
  reload 重跑发现）——`SessionSetup` 静态方法被两处调。

### T4：色彩数学地基（coding-agent `theme` 包，~700 行＋测试）

- `Oklab`（package-private，oklab.ts 233 行逐字，MIT 头注保留）：
  矩阵常量、`okhslToRgb`/`rgbToOkhsl`/`oklabToLinearSrgb`/`rgbToOklab`/
  `linearSrgbToRgb`/`oklabToOkhslLightness`/`cusp`/`maxChroma`/`chromaStops`。
- `ThemeColor`：`sealed interface`＋`Indexed(int)`/`Rgb(int,int,int)`（double
  中间值允许——`linearSrgbToRgb` 前通道是 double；record 用 double 存，
  产 hex/ANSI 时 round）/`Oklch(double l, double c, double h)` 三 record。
  构造校验逐字（`ANSI color index must be an integer from 0 to 255: %s` 等）。
- `ThemeColorMath`：`parseColor`（六形）、`colorToRgb`（含 oklch 色域二分）、
  `colorToOklch`、`colorToHex`、`mixColors`（oklch/srgb 两空间）、
  `indexedToRgb`（BASIC_COLORS/CUBE_VALUES/GRAY_VALUES 三表）、
  `rgbToAnsi256`、`foregroundAnsi`/`backgroundAnsi`、`styleTextWithAnsi`、
  `parseBooleanCapabilityOverride`＋`terminalColorMode()`（裁决④：
  `PI_TRUE_COLOR` env → settings `terminal.trueColor` 覆盖口 → `COLORTERM ∈
  {truecolor,24bit}` ⇒ truecolor → 默认 truecolor；窄差 D124）。
- `TerminalColorMode` enum（`TRUECOLOR`/`COLOR_256`，`@JsonValue` 不需要——
  不序列化）＋`TerminalColors` record（foreground/background/palette 可空）。
- 测试向量从 pi 测试移植（`test/theme-*.test.ts`/`system-theme.test.ts` 的
  已知值：hex 解析、okhsl→hex 逐值、rgbToAnsi256 灰阶/立方取舍、
  wcagContrast 已知对、mixColors 中点）。

### T5：主题模型（~450 行＋测试）

- `ThemeToken` enum：57 常量，每常量带 `slot`（FG/BG）/`description`/
  `fallback`（5 个非空）；顺序＝pi 表序（`Object.entries` 序敏感——fallback
  填平与 `colors` 输出序）。**逐字抄锚点表**。
- `ThemeJson` record：`name/appearance/vars/colors/export`（colors 值 =
  `String|Integer` 联合 ⇒ `Object` 槽位＋校验器收敛）；`$schema` 键容忍。
- `ThemeJsonValidator.validate(label, JsonNode)`：名字 `/` 快检（文案 §1.1
  逐字）→ 严格校验（未知顶层键/未知 colors 键/未知 export 键/缺必填令牌/
  值型不符）⇒ 错误组装同 §1.1。
- `Theme` 类：§1.2 全件（fg/bg ANSI 缓存、concreteColors、dim 集、
  appearance 检测、`colors` 解析缓存按 `Themes.terminalColors` 身份失效、
  `style/fg/bg/bold/italic/underline/inverse/strikethrough/getFgAnsi/getBgAnsi/
  getThinkingBorderColor/getBashModeBorderColor`）。
  ThinkingLevel 映射：java `ThinkingLevel` enum → 七令牌 switch。

### T6：system 主题生成器（~550 行＋测试）

- `SystemTheme`：`SYSTEM_THEME_NAME = "system"`；FAMILIES/TOKEN_FAMILIES/
  TOKEN_SLOTS/LEVELS/RULES/THINKING_LEVELS/SOLVE_ORDER 全表逐字（从锚点
  文件抄，SOLVE_ORDER 用同样的 DFS 拓扑而非手抄序——构造期计算，结果
  可与 pi 打印比对一次后钉测试）。
- `generateSystemThemeColors(SystemThemeInput)`：`paint/anchored/target/
  limitPanel/solve/withTextContrast/indexedColors` 全件；`relativeLuminance/
  wcagContrast/terminalAppearance` 公开（测试与外观检测两用）。
- java 终端输入恒空 ⇒ 行为固定 tier-3；`Themes.setTerminalColors` 公开缝
  保留（D123 注记）。

### T7：注册表＋监视器＋控制器（~500 行＋测试）

- `Themes` 静态面：`initTheme(name, enableWatcher)`/`setTheme(name,
  enableWatcher)`（返 `record ThemeResult(boolean success, String error)`）/
  `setThemeInstance`/`current()`（pi `theme` proxy 对位——**裁决⑤**：未初始化
  时懒初始化内建 dark 而非抛错，5,000 存量测试不经主题初始化；
  D126 窄差）/`onThemeChange`/`setRegisteredThemes`/`getAvailableThemes(WithPaths)`/
  `getThemeByName`/`loadThemeFromPath`/`getResolvedThemeColors`/`isLightTheme`/
  `getThemeExportColors`/`setTerminalColors`（缝）/`terminalTheme()`。
- 内建主题：`pi-java-coding-agent/src/main/resources/themes/dark.json|light.json`
  **逐字拷贝锚点文件**（含 `$schema` 键——validator 容忍）；`getBuiltinThemes`
  从 classpath 读（pi 从包目录读的对位）。
- `ThemeSettings`：`parseAutoThemeSetting/resolveThemeSetting/
  detectColorFgBgTheme/detectTerminalTheme`（§1.4 逐字）。
- `ThemeWatcher`：WatchService 监听 `<agentDir>/themes` 目录（目录不存在不
  起 watcher——pi `fs.existsSync(themeFile)` 门对位）；100ms 防抖（调度器单
  虚拟线程）；陈旧/文件暂失/解析失败三臂逐字（§1.4）。`stopThemeWatcher`
  在会话结束/RPC 关闭链调用（pi main.ts:428/971/992 对位点：java
  `Main` 的退出钩＋`PiTuiApp` stop）。
- `ThemeController`：§1.4 裁剪版（无 queryTerminalColors/autoSync/2031）；
  `onChanged` 回调槽（TUI 注入重渲染＋SemanticColors rebind）。
- 测试：initTheme 未知名静默回落 system（currentThemeName 也回落）；
  setTheme 失败返 error 文案；`parseAutoThemeSetting` 五臂（无斜杠/双斜杠/
  空半对/空白 trim/正常）；`detectColorFgBgTheme` 边界（`0;15`/`15;0`/8/7/
  `default`）；watcher 防抖（临时目录真 WatchService，容忍慢 FS 用
  awaitility 式轮询）。

### T8：发现接线（coding-agent，~150 行＋测试）

- `ThemeLoader.loadThemes(List<String> paths)`：§1.5 逐字（不存在/非 .json/
  目录扫描/失败三诊断）＋`dedupeThemes`（碰撞诊断复用
  `skill/ResourceDiagnostic`——`resourceType "theme"`）；返回
  `record ThemeLoadResult(List<Theme> themes, List<ResourceDiagnostic> diagnostics)`。
- `AgentSession`：`loadExtensions` 主题段（:953-960）后接
  `ThemeLoader.loadThemes(themeStrings)` ⇒ `owner.themes`＋诊断进
  `startupDiagnostics`（warning 级原样，collision 级原样）；`themes()` 读面。
  `SessionReload` ⑤ 增同链重算（裁决⑩：只重算，注册表写在模式层）。
- 诊断文案逐字（`Theme path does not exist` 只在初始装配臂？——pi 的两处
  不同：`updateThemesFromPaths` 前的 mergePaths 段用 `"Theme path does not
  exist"`（error? 实为 themeDiagnostics push），loadThemes 内用 `"theme path
  does not exist"`（warning）。java 只落 loadThemes 臂（java 无 extension
  resources_discover 的 themePaths 元数据段——B221① 域，窄差注记）。)

### T9：A-29 `--theme` 重裁＋`theme` 键语义（~120 行＋测试）

- `PiTuiLauncher.themeFrom` 删除 `args.themes().get(0)` 选型误用——
  `--theme` 值只进发现路径（批 18 已通）；选型＝`--use-theme` ??
  settings `theme` ?? system。
- `PiTheme.resolveTheme` 删除（三元优先级函数无 pi 对位）；`PiTheme` 类
  重裁为 `ThemeApplicator`（T10）的入口壳，`engineFor(String)` 签名保留
  但语义＝「按 pi 名解析主题并生成 engine」（`DARK`/`LIGHT` 常量改指
  JSON 资源路径或删除——看调用点改造后定）。
- `SettingsAccessors`：`getThemeSetting()`（原样）＋`getTheme()`（含 `/` ⇒
  null，pi :747-749 对位）双件；旧 `getTheme` 调用点改 `getThemeSetting`。
- `Main`（print/RPC 两臂）：会话装配后 `Themes.initTheme(
  ThemeSettings.resolveThemeSetting(useTheme ?? settings.getThemeSetting(),
  terminalTheme), false)`——HTML 导出与扩展 `getThemeByName` 在非交互
  模式也可用（pi main.ts:905 对位）。
- 行为变化注记：settings 里的旧 `.tcss` 路径值/裸 `dark|light` 之外的未知
  值 ⇒ 静默回落 system（pi 同）；默认主题 dark ⇒ **system**（pi 默认；
  因裁决③，java 的 system 恒 tier-3 索引色——面板无底色、neutral 令牌
  dim，外观变化显著，changelog 级注记＋窄差 D123 交叉引用）。

### T10：TUI 适配层（tui `theme` 包，~300 行＋130 调用点机械改写）

- `TuiThemeBridge`：`ThemeColor → dev.tamboui.style.Color`（Indexed⇒
  `Color.indexed`、Rgb⇒`Color.rgb(round)`、Oklch⇒转 Rgb）；`""`/default 令牌
  ⇒ `Color.RESET`；`Style fg(ThemeToken)`/`Style bg(ThemeToken)`（dim 令牌
  附加 `.dim()`）。
- `SemanticColors` 重造：常量字段 ⇒ **静态访问器方法**委托
  `Themes.current()`（`accent()`/`muted()`/`warning()`/`success()`/`error()`/
  `borderAccent()`/`customMessageLabel()`/`dim()`/`plain()`/`selectedBg()`/
  `withSelectedBg(Style)`）；130 调用点 `SemanticColors.ACCENT` →
  `SemanticColors.accent()` 机械改写（sed 级）。未知/缺席回退：主题未初始化
  ⇒ 裁决⑤懒 dark。
- `ThemeApplicator`：从活跃主题生成 TCSS 文本（`loadStylesheet("theme",
  content)` 恒在）＋`reloadStylesheet`＋`StyleEngine` 变更通知 ⇒ 重渲染。
  映射：scrollbar-thumb←`scrollbarThumb`、scrollbar-track←`scrollbarTrack`、
  EditorComponent border-color←`borderMuted`、Separator←`borderMuted`；
  **`.Screen`/`StatusBar` 的 background/foreground 键从生成表移除**（pi 无
  screen/statusbar 底色概念——终端默认背景，行为变化注记）；java-only 类
  （`-hover` 双臂）保留现硬编码值（窄差 D129 注记无 pi 对位）。
  ChatPanel padding 等非色键保留（生成表含固定 padding 段）。
- 旧 `pi-dark.tcss/pi-light.tcss` 资源删除（被生成取代）；`PiTuiLauncher`
  两臂（fullscreen/regular）改走 `ThemeApplicator`；`SessionSelectorRunner`/
  `ConfigSelectorRunner` 同步。
- `onThemeChange` ⇒ PiTuiApp 注册：`SemanticColors` 无须清缓存（访问器
  现读）＋`ThemeApplicator.reapply()`＋`runner` 全量 invalidate。
- 编辑器边框 thinking-level/bash-mode 着色（pi `getThinkingBorderColor`
  消费点 `updateEditorBorderColor`）：**调查 EditorElement 边框样式面**
  ——TamboUI TextArea 边框可程序化上色则接（`thinkingLevel` 变更点
  PiTuiApp 已有 setThinkingLevel 链）；不可 ⇒ 窄差 D127。

### T11：设置页 Theme 子菜单（map/05 S9，~250 行＋测试）

- `SettingsScreen` Theme 行改子菜单入口（`themeRequested` 检出位，
  `modelThinkingRequested` 先例）；currentValue 显示
  `controller.getThemeSelection()`。
- 新 `ThemeSubmenuScreen`（ScreenOverlay）：pi `ThemeSubmenu` 对位——
  single 列表（system 置顶＋automatic 项＋其余，`✓ ` 标当前，system 描述
  逐字）/automatic 四行（Light theme/Dark theme 两子选择＋Apply＋
  Change mode，文案 §1.6 逐字）；选择变更 ⇒ `controller.preview`（不持久）；
  Enter（single）/Apply（automatic）⇒ `setTheme` 持久＋关；Esc ⇒ 预览还原
  原设置＋关。
- 子选择屏复用 `SelectList` 系件（会话选择器批 14 件族）。
- 独立 `ThemeSelectorComponent`（pi 67 行，仅导出未自用）**不移植** ⇒
  窄差 D130（S9 行注记：选择器本体＝设置页子菜单）。

### T12：HTML 导出主题注入（~80 行＋测试）

- `HtmlExporter`：`:root` 变量块由 `Themes.getResolvedThemeColors(themeName)`
  ＋`getThemeExportColors`＋`deriveExportColors`（§1.9 逐字移植）生成；
  公开 `export(..., String themeName)` 重载（null ⇒ 活跃主题）；CLI `--export`
  臂与 `/export` 臂传 settings 解析名。
- 夹具：dark 主题导出的 `:root` 含 `--accent: #…`（与 dark.json 解析值一致
  ——okhsl→hex  golden）；带 `export` 节的自定义主题优先用显式值。

---

## §4 不做项与重排

| 项 | 裁决 |
|---|---|
| 终端 OSC 10/11/4 查询、mode 2031 通知、100ms 灰度窗、`waitForTerminalColors` | 裁决③：TamboUI 无面 ⇒ 不移植，窄差 D123；`Themes.setTerminalColors` 留缝 |
| `setThemeJsonValidator` 懒装缝 | 裁决②：java 恒装手写校验器 |
| 独立 `ThemeSelectorComponent`（pi 仅导出） | 不移植（D130） |
| `.tcss` 自定义主题文件（java 自造形） | 裁决⑦：删除（pi 无此形；`--theme` 归位纯路径） |
| 编辑器边框 thinking/bash 着色 | 视 EditorElement 面：可接则接，否则 D127 |
| 打印模式 chalk 着色面 | java print 本无色；不新造（pi print 的着色点另行取证才动） |
| `getLanguageFromPath`/`highlightCode`/`getMarkdownTheme`（theme.ts:951-1059） | MarkdownRenderer 死代码域（B65/R1 变量③），不随本批 |
| `themes` 设置的 TUI 写回（config enable/disable 的 THEMES 臂） | 批 19 `ConfigResourceWriteBack` 已落 THEMES case，本批不动 |

## §5 窄差登记（接续 D122；台账格式随闭环入 docs/04）

| # | 窄差 | 类别 |
|---|---|---|
| D123 | 无终端颜色查询/外观通知：system 主题恒 tier-3（ANSI 索引＋dim 令牌、面板无底色），外观判定＝COLORFGBG??dark；无灰度等待窗/late-reply | 用户可见·外观 |
| D124 | TerminalColorMode 探测＝PI_TRUE_COLOR/settings/COLORTERM 启发式（pi terminfo 探测） | 边缘·用户可见 |
| D125 | 主题校验手写：逐条错误 path 措辞与 typebox 不同（结构/missing 清单逐字） | 用户可见·文案 |
| D126 | `Themes.current()` 未初始化懒回落内建 dark（pi 抛 `Theme not initialized`）——测试生态承载 | 内部 |
| D127 | 编辑器边框 thinking-level/bash-mode 着色未接（视 EditorElement 面调查结果） | 用户可见·外观 |
| D128 | `resources_discover` 的 themePaths 元数据段无（B221① 域）⇒ 扩展贡献主题的发现期 sourceInfo 标签缺 | 内部 |
| D129 | TCSS `-hover` 双臂/java-only 类保留硬编码（无 pi 对位）；`.Screen`/`StatusBar` 底色移除＝pi 对齐 | 内部 |
| D130 | 独立 ThemeSelectorComponent 不移植（pi 仅导出未自用） | 内部 |
| D131 | auth.json revision 缓存/stale 检测/共享读态不移植（java 无进程内读缓存，盘读即新鲜） | 内部 |
| D132 | `systemPromptSourcePath` 记录/显示面无（pi 用于 loaded-resources 展示，java 无该面） | 内部 |

## §6 裁决

| # | 裁决 | 理由 |
|---|---|---|
| ① | 主题核落 `com.pijava.coding.agent.theme`（toolkit 中立），TamboUI 适配落 tui | 依赖方向 tui→coding-agent；HtmlExporter 在 coding-agent；pi 的 pi-tui 色数学在 java 无对应模块可放（TamboUI 第三方） |
| ② | 校验恒装（Jackson 手写），无 `setThemeJsonValidator` 缝 | pi 懒装理由＝typebox 17MB 模块图，java 无此成本 |
| ③ | 终端颜色查询面不移植；`setTerminalColors` 留公开缝 | TamboUI 无 OSC 面；结构全留、行为 tier-3 |
| ④ | TerminalColorMode＝PI_TRUE_COLOR→settings terminal.trueColor→COLORTERM→默认 truecolor | terminfo 探测无 java 对位；settings 覆盖口顺带给 E2-32 补一个消费点 |
| ⑤ | `Themes.current()` 未初始化懒 dark 回落（非抛错） | 5,046 存量测试不经主题初始化；pi 的 proxy 抛错在 java 测试生态不可行 |
| ⑥ | TCSS 由活跃主题生成；`.Screen`/`StatusBar` 底色移除 | pi 无 screen/statusbar 底色（终端默认背景）；行为变化注记 |
| ⑦ | 删 `.tcss` 自定义主题支持，`--theme` 归位纯路径来源 | pi 无 .tcss 形；java 自造形删除（pi-deleted-then-drop 同族先例） |
| ⑧ | 默认主题 dark ⇒ system | pi 默认（themes.md:38；initTheme name??system）；tier-3 外观差异随 D123 |
| ⑨ | SemanticColors 常量 ⇒ 静态访问器委托活跃主题 | 130 调用点机械改写；消除双源（近似色 vs 真主题） |
| ⑩ | SessionReload 只重算 `owner.themes`；注册表写＋控制器应用在模式层 | 会话层 toolkit 中立；pi 的 setRegisteredThemes 也在 InteractiveMode（:6490） |
| ⑪ | HTML 导出接主题变量（含 deriveExportColors 派生链） | B163 范围保真；E3-11 判定不动 |

## §7 测试计划

| 测试 | 覆盖 |
|---|---|
| `OklabMathTest` | okhsl→rgb 已知值（pi `test/test-theme-colors.ts`/system-theme 测试向量移植）、hex↔rgb、wcag 已知对、色域二分回落（`oklch(100% 0.3 150)`⇒白） |
| `ThemeColorMathTest` | parseColor 六形＋非法值文案、rgbToAnsi256 灰/立方取舍、styleTextWithAnsi 开闭序 |
| `ThemeJsonValidatorTest` | 缺必填令牌清单文案（排序）、未知键/未知令牌/名字带 `/`/值型错四族 |
| `ThemeTest` | var 链/环/缺引用三文案、5 fallback、dim 令牌 SGR 2 开闭、appearance 检测、colors 缓存身份失效 |
| `SystemThemeTest` | tier-3 `indexedColors` 全表钉死（面板=""、neutral dim 集）、terminalAppearance 五臂、生成器给定背景的 golden 色表（pi system-theme.test.ts 向量） |
| `ThemesRegistryTest` | init/setTheme/回落/registeredThemes 优先序/system 保留名压同名、getAvailableThemes 排序（system 首＋localeCompare） |
| `ThemeWatcherTest` | 临时目录真 WatchService：改文件 ⇒ 100ms 内热更；坏 JSON ⇒ 保持旧；切主题后旧定时器不生效 |
| `ThemeLoaderTest` | 三诊断臂、dedupe 碰撞（winner/loser/`<builtin>`） |
| `SessionSetupPromptFileTest` | SYSTEM.md 信任门（未信任不读）、agentDir 回落、APPEND_SYSTEM.md、file-or-literal、读失败告警 |
| `CredentialStoreConcurrencyTest` | 两实例交错写无丢更新 |
| `ThemeApplicatorTest` | 生成 TCSS 内容钉（四映射键＋无 .Screen background）、SemanticColors 访问器随主题切换变值 |
| `ThemeSubmenuScreenTest` | single/automatic 模式进出、预览回调序、Esc 还原原设置、Apply 持久 `"light/dark"` |
| `HtmlExporterThemeTest` | dark golden 变量块、export 节优先、派生链两臂 |
| 回归 | `mvn clean verify` 全绿（spotbugs 关注：Themes 静态可变态——holder 用 volatile 引用＋不可变快照） |

## §8 对账表（判定列：前态→后态）

| 行 | 前 | 后 | 证据（实施后回填） |
|---|---|---|---|
| E1-3 | 存疑 | 对齐 | T2 |
| E1-4 | 存疑 | 对齐 | T1d |
| E1-5 | 缺失 | 对齐 | T1a |
| E2-9 | 存疑 | 对齐 | T7/T9/T11 |
| E3-15 | 缺失 | 对齐 | T1b |
| E3-23 | 缺失 | 对齐 | T1c |
| E4-4 | 缺失 | 对齐 | T4–T8 |
| E4-6 | 缺失 | 对齐 | T3 |
| A-29 | 存疑 | 对齐 | T9 |
| map/05 S9 | 缺失 | 对齐 | T11 |

## §9 闭环回填

（闭环时填写：docs/04 批 21 段＋docs/05 批 21 条目＋map/04 十行翻动＋map/05
S9＋docs/20 §1/§2/§3 五处＋记忆。）

| 回填点 | 内容 |
|---|---|
| docs/04 §4 | 批 21 段（范围/决定/风险三段式＋窄差 D123–D132＋行为变化注记） |
| docs/05 | 模块历史批 21 条目 |
| map/04 | E1×3/E2-9/E3×2/E4×2/A-29 九行翻动＋四域汇总行（E1/E3/E4/A 满分）＋总计 |
| map/05 | S9 缺失→对齐＋汇总 |
| docs/20 | §1 读数、§2 批 21 行、§3 序 20 划线 |
| 记忆 | coding-agent-batch21-closed.md |
