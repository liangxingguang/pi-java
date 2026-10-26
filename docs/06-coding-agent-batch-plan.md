# coding-agent 对齐批次总账（活文档）

【背景摘要】本文档是 coding-agent 层（`docs/map/04-coding-agent.md` 管辖域＋绑定
的 TUI 行）对齐工作的**批次级总账**：一共计划多少批、每批干什么、完成到哪批、
账面走到多少。设立原因：此前批次进度只散落在各批设计稿的闭环回填与记忆里，
没有单一权威入口，且闭环时不强制更新。本文档自设立起进入闭环回填清单（见
§4 维护规则）。

术语（首现释义）：

- **批（batch）**：一次「设计稿 → 用户审核 → 写码 → 闭环回填」的完整循环，
  对应一份 `docs/NN-coding-agent-batchN-design.md`。
- **账面**：加权完成度读数。判定三档——对齐＝1.0／存疑＝0.5／缺失＝0；
  权重 3＝每轮对话都走，2＝每次会话走，1＝低频/内部。逐行实算＋map/04 域表
  是权威，汇总行会滞后。
- **窄差（D 编号）**：与 pi 无法完全对齐、登记在册的差异，不阻断批次闭环。
- **工单（B 编号）**：`docs/04-open-items-register.md` 的开放项条目。
- **R5 面外**：不进 `bin pi` 主流发布版的子系统（codemode/durable 等），
  不做对齐、不进分母。

---

## §1 进度总览（2026-10-25 实算，批 23 闭环后）

| 指标 | 读数 |
|---|---|
| 已闭环批次 | **23**（批 1–23，设计稿原 docs/11–34，2026-10-26 第六次清理删除，历史在 git） |
| 设计待审批次 | **0**（下一包号 docs/35） |
| 计划批次（批 24 起） | **1 候选行**（批 24＝内置 MCP 扩展本体，批 23 裁决①立项；其余＝§3.3 未拍板变量） |
| **总批次估计** | **23＋变量**（批 24 候选 1 批＋§3.3 三变量每个摆动 1–6 批） |
| 模块账面（Σ 485，去重） | 416.0 ≈ **85.8%** |
| 满分域 | **13 个**（批 23 新增一：D——缺失清零） |
| D 域（扩展事件，Σ 83） | 83.0 ＝ **100%**（批 23 满分） |
| E2 域（settings 键，Σ 84） | 81.5 ≈ **97.0%**（余 5 存疑全是窄差登记项） |
| 全仓账面（Σ 1379） | 1,103.5 ≈ **80.0%** |

> ⚠️ 总批次是**估计值**，不是承诺。三个拍板变量（§3.3）每个能摆动 1–3 批。
> 每批闭环时按当时真实欠账重排 §3，允许增删批次。

---

## §2 已闭环批次

| 批 | 设计稿 | 闭环日 | 范围 | 账面 Δ（模块加权） |
|---|---|---|---|---|
| 1 | docs/11 | 2026-10-19 | B236 工具选择三层语义（默认激活 7→4、`mcp__` 策略） | 221.0/496 ≈ 44.6% |
| 2 | docs/12 | 2026-10-19 | B244 `skill:` 命令注册面三层 | 226.0/496 ≈ 45.6%（+5.0） |
| 3 | docs/13 | 2026-10-19 | B247 模板注册面＋RPC `get_commands` 收平 | 账面不变（缺陷闭环） |
| 4 | docs/14 | 2026-10-20 | D 域地基：`on()` 订阅面＋EngineHookBridge 三适配器＋SessionEventBridge 14 事件 | 261.5/496 ≈ 52.7%（+35.5） |
| 5 | docs/15 | 2026-10-20 | 结果臂事件族（message_end/input/boundary/before_compact）＋生命周期两事件（B249+B250） | 283.5/496 ≈ 57.2%（+22.0） |
| 6 | docs/16 | 2026-10-21 | fork 扩展装配（wireSession）＋会话关闭治理（B251+B252） | 账面不变（缺陷闭环） |
| 7 | docs/17 | 2026-10-21 | provider 请求面扩展事件四件（B248；D 域 w3 清零） | 289.0/496 ≈ 58.3%（+5.5） |
| 8 | docs/18 | 2026-10-21 | tree 两扩展事件＋TUI 摘要导航流（B245） | 73.0/83 D 域 88.0%；模块去重口径 283.5/487 ≈ 58.2% |
| 9 | docs/19 | 2026-10-22 | 树选择器本体（D37 收编＝map/05 S12+S13；RPC/Web `get_tree` 修真；label 时间戳面；评审三修 C1/I1/I2） | 286.0/487 ≈ **58.7%**（+2.5）；全仓 958.0/1381 ≈ 69.4% |
| 10 | docs/21 | 2026-10-22 | `/fork`＝user-message 选择器（B254）＋S20 dequeue 链＋D41 重裁两臂；`TreeSelectorScreen` 删；B66 核账 6→4 | 288.0/487 ≈ **59.1%**（+2.0：#15＋E3-8 同翻）；全仓 962.0/1381 ≈ 69.7%（+4.0：另 S20＋K11） |
| 11 | docs/22 | 2026-10-22 | settings 键批量第一批（E2 域：核销×2〔E2-38/E2-11 账面滞后〕＋消费链×5〔modelThinkingLevels/httpProxy/httpIdleTimeoutMs/deviceId/blockImages，B15-残留-5 销号〕＋声明面×14；codemode 出分母裁决②） | 303.5/485 ≈ **62.6%**（+15.5；分母 487→485＝E2-45 出分母）；E2 域 52.0→67.5/84 ≈ **80.4%**；全仓 977.5/1379 ≈ **70.9%** |
| 12 | docs/23 | 2026-10-22 | settings 消费点批（E2 域：核销×3〔E2-37 账面滞后裁决②＋E2-23/E2-24 pi 零消费裁决③口径先例〕＋TUI 行为键×5〔双击 Esc/外部编辑器/编辑器留白/补全行数默认 8⇒5/outputPad 烘焙〕＋声明面消费点×7〔changelog 流/硬件光标/退出两臂/滚动条三态/滚轮数字臂/订阅警告〕＋`setModelThinkingLevel` TUI 入口；**裁决① E2 口径修订〔TUI 消费点计入〕**；B66 核账 4→3；窄差 D55–D64） | 311.0/485 ≈ **64.1%**（+7.5）；E2 域 67.5→75.0/84 ≈ **89.3%**（对齐 29→44）；全仓 985.0/1379 ≈ **71.4%** |
| 13 | docs/24 | 2026-10-23 | cache-warmer 子系统批（CacheWarmer 状态机＋CacheStats miss 统计＋裁决①分包门装配＋会话四面接线＋扩展 decide 钩＋暖路径请求面＋TUI 三门〔暖用量行/miss 通知双臂/设置页两行〕＋/session Cache Warming 段；评审三修〔C1 isCurrent 快照化/I1 即时臂推进 prev/I2 拆经济学〕；窄差 D65–D68） | 315.0/485 ≈ **64.9%**（+4.0：D#16＋E2-49＋E2-14＋R24）；D 域 73.0→75.0/83 ≈ **90.4%**；E2 域 75.0→76.5/84 ≈ **91.1%**；全仓 989.0/1379 ≈ **71.7%** |
| 14 | docs/25 | 2026-10-23 | CLI 参数尾（A 域）＋`-r` 会话选择器（FuzzyMatch/SessionSelectorSearch/选择器七件/SPI＋启动壳；会话解析 pi 全序＋SessionResolutionExit＋校验两臂＋-c 空臂新建〔B255 即登即销〕＋全局臂/session-id/fork 查重；`--`/`--export`/`--use-theme`/`-e`/web no-session〔B54〕；A-17 核销＋裁决④ offline 判对齐；回归两修〔/hotkeys 空绑定 NPE/-c 旧夹具〕＋auth 三存储静态 home 修真〔批 13 环境性红真因〕；窄差 D69–D78） | 334.0/485 ≈ **68.9%**（+19.0：A 域 +10.5〔60.5/62 ≈ 97.6%〕＋E3 域 +8.5〔37.0/40 ≈ 92.5%，含 E3-19/E3-24 漏列同翻〕）；map/05 +3.5（K16＋K15＋S11，tui/web 116.5/245 ≈ **47.6%**）；全仓 1,011.5/1379 ≈ **73.3%** |
| 15 | docs/26 | 2026-10-23 | slash 命令尾（C 域）：`/bug` 故障上报全链（三屏 `BugReportFlow`＋脱敏/收集/摘要缝/上传/ZIP）＋crash-log 子系统（`CrashLog`＋启动提示＋未捕获钩＋`/import` fatal 修真〔B256 即登即销〕＋`/bug` 建议）＋存疑修（`/thinking`〔D84〕＋`/model` 直设臂＋`/export` 默认臂＋`/session` 全段〔D68 销号〕＋`/changelog` `/compact` 核销）；**B79 销号**；窄差 D79–D88；回归回补三钉＋前存红修真（TreeNavigationFlowTest 时序缺陷）；R29 挂起（裁决④，随序 21） | 341.0/485 ≈ **70.3%**（+7.0：C 域 27.0→34.0/38 ≈ **89.5%**，余 6 行在选择器/Radius/批 16）；map/05 +2.0（S29/S30，tui/web 118.5/245 ≈ **48.4%**）；全仓 1,020.5/1379 ≈ **74.0%** |
| 16 | docs/27 | 2026-10-23 | E5 扩展 API 面第一批：激活门三件套＋装配后注册即激活（B221②）＋提示分节接线（B221⑤）＋registerToolRenderer 责任链＋内置 MCP resolver＋TUI 消费缝（**B204 销号**）＋扩展 CLI flag 面（registerFlag/getFlag＋绑定＋--help 段；A-43/E5-7/E5-30）＋`/reload` 七步序＋defaultTools diff 三分支（**B246 销号**）＋`model_select` 两发射点（D#36）；评审回补修真（工具执行查找面接活跃集＋四夹具修形；责任链 null 直返语义自纠）；B221 收编 ②④⑤（余 ①③⑥）；窄差 D89–D94；D#11 不动（裁决⑤随 E5-23 批） | 350.5/485 ≈ **72.3%**（+9.5：E5 域 16.0→22.0/58 ≈ **37.9%**、A 域 61.5/62 ≈ **99.2%**、C 域 34.5/38 ≈ **90.7%**、D 域 77.0/83 ≈ **92.8%**）；全仓 1,030.0/1379 ≈ **74.7%** |
| 17 | docs/28 | 2026-10-24 | E5 扩展 API 面第二批（扩展 UI 注入层）：`ExtensionUI` 28 成员全面化＋晚绑定修真（裁决②）＋`ui_prompt_start/end` 事件对（D#26/#27）＋`RpcExtensionUI` 九方法通道（E5-32，参数平铺＝pi spread）＋TUI 对话四件套/custom 挂载面（S22，B221③ 收编）＋widget/footer/header/状态段注入层（S23）＋装配与生命周期（create(ui,mode) 重载＋onSessionInvalidated 三路径＋reload 首步前）；**评审回补**：RPC 应答按 id 路由（I-1）＋会话替换重绑扩展 UI（I-2）＋Minor×2 随修；窄差 D95–D101；B214 改注不销号（载荷随 MCP 扩展工厂批） | 354.0/485 ≈ **73.0%**（+3.5：E5 域 22.0→23.5/58 ≈ **40.5%**、D 域 77.0→79.0/83 ≈ **95.2%**）；map/05 S22/S23（tui/web 118.5→120.5/245 ≈ **49.2%**）；全仓 1,035.5/1379 ≈ **75.1%** |
| 18 | docs/29 | 2026-10-24 | E6 包管理器第一批（resolve 内核，裁决①②③＝方案 D）：T1 settings `packages`/`npmCommand` 两键（E2-20/E2-26）＋T2 源解析三型＋GitUrlParser/identity/dedupe＋Semvers（semver4j 引库，D106 改注销号）＋T3 npm HTTP registry（NpmResolver seam/SRI/tar 解包/InstallLayout）＋T4 git spawn 命令表（marker 恢复/update 双臂/刷新静默）＋T5 resolve()（五档优先级/四符过滤/忽略拍平/PiManifest/扫描族/自动发现信任门/onMissing 三臂/withProgress）＋T6 会话装配四类接 resolve（裁决⑨：project 压 user 行为变化＋settings 四数组生效＋-e 收 npm:/git:）；**评审回补**：I-1 prompts 同名 first-wins 修真＋I-2 Windows 冒号条目启动崩修真＋M-1/2/3/4/7 随修；窄差 D102–D105/D107–D112；E6-16 降级缺失（CLI 批 19 重裁） | 370.0/485 ≈ **76.3%**（+16.0：**E6 域 5.0→17.0/31 ≈ 54.8%**〔零对齐域消除〕＋E2 域 76.5→80.5/84 ≈ **95.8%**〔缺失清零〕）；全仓 1,051.5/1379 ≈ **76.3%** |
| 19 | docs/30 | 2026-10-24 | E6 包管理器第二批（CLI 面＋写回链＋update 机制＋config TUI，裁决①②④用户拍板）：T2 `SettingsManager` 写回面（字段级 merge 落盘〔裁决③〕＋**`setProjectTrusted(false)` 抹盘修真**）→ T1 写回链＋公开安装面（`PackageConfigWriter` 身份去重/原位替换）→ T3 update 机制（`PackageUpdater` 并发 4 闸〔裁决⑥ detached 首错抛〕＋Did-you-mean 逐字）→ T4 CLI 面重裁（pi parsePackageCommand 逐字＋--models 短路臂＋self 恒不可用臂〔裁决②〕；**裁决④删 `ExtensionPackageManager`**，诊断/--help 枚举源迁 resolve）→ T5 config enable/disable 改 pi pattern 代数 → T6 config TUI 本体（`ConfigResourceWriteBack` 四函数＋三态循环＋tui `ConfigSelectorModel/Component`＋SPI 壳）→ T7 启动包更新通知；**onMissing 三选取证否定**（pi 无此 UI）；E6-15 终判对齐；窄差 D113–D116；spotbugs 回补 1 钉 | 388.0/485 ≈ **80.0%**（+18.0：**E6 域 17.0→30.0/31 ≈ 96.8%**〔缺失清零，余 E6-10 存疑挂 R29〕＋**B 域 9.0→14.0/15 ≈ 93.3%**〔余 B-4 存疑〕）；w3 73.9%→**87.0%**（批 18 漏入 w3 表两翻收平）；全仓 1,069.5/1379 ≈ **77.6%** |
| 20 | docs/31 | 2026-10-25 | RPC/协议尾合包（序 19）：T1 B237 disposition 三态全链（CommandRegistry EXTENSION 标签＋撞名旁表＋processPrompt 扩展命令臂〔pi :1978-1984，出错也是 handled〕＋steer/followUp 全序〔throwIfExtensionCommand 错文逐字→input 事件→disposition 返回〕＋RPC disposition/images 透传＋InteractiveMode 重路由〔裁决①②〕）→ T2 get_state 三字段（`RpcModel` 全对象〔B31；裁决③④⑥〕＋sessionName stored 缺席化＋--name 写 session_info〔B32；裁决⑧〕＋messageCount 工作副本〔B33〕）→ T5 new_session 修形（**B257 即登即销**：`{cancelled}` 响应形＋parentSession 落 header＋before_switch 取消臂）→ T6 attribution 头层（B253：`ProviderAttribution` 逐字四家头＋`InstallTelemetry` 门＋恒执行接线、钩见合并全集、暖路径同；裁决⑤ reportInstallTelemetry 不移植）→ T3/T4 核销（E7-2/E7-4 parity 测试翻行）；**B237/B253/B31/B32/B33 销号**；D33 改注（余两层）；窄差 D117–D122；**评审回补 1C＋4I**（I-1 扩展命令独立虚拟线程〔同步 join 死锁 RPC 读线程 Critical〕＋I-2 --name 限定启动链＋I-3 input behavior 流式门＋I-4 装配目录快照＋I-5 重放命令臂；M-1 E1 拆分挂起） | 392.0/485 ≈ **80.8%**（+4.0：**E7 域 8.0→12.0/12＝100%**〔存疑清零，**第六个满分域**〕）；全仓 1,073.5/1379 ≈ **77.8%** |
| 21 | docs/32 | 2026-10-25 | E1/E3/E4 尾＋JSON 主题系统 2,417 行域（序 20）：T4 色数学（`ThemeColor` ADT＋`Oklab`＋`ThemeColorMath`——pi-tui colors.ts/oklab.ts 逐字，golden 经锚点 tsx 实跑钉死 okhsl(295 50% 67%)⇒#a798d7）→ T5 主题模型（`ThemeToken`×56＋手写恒装校验〔裁决②〕＋`Theme`）→ T6 `SystemTheme` 生成器（14 族/15 曲线/RULES/SOLVE_ORDER 全表逐字；裁决③ 无 OSC 面恒 tier-3）→ T7 `Themes` 注册表＋`ThemeWatcher` 热重载（WatchService＋100ms 防抖）＋`ThemeController`（裁终端查询面）→ T8 发现接线（`ThemeLoader`＋`AgentSession.reloadThemes`＋SessionReload ⑤ 主题臂〔批 16 裁决⑨挂账落地〕＋reload 监听者模式层收尾〔裁决⑩〕）→ T9 A-29 `--theme` 重裁纯路径来源（`cliThemePaths`＝pi resolveCliPaths 逐字；删 .tcss 选型〔裁决⑦〕；`Main` headless initTheme）→ T10 TUI 适配（`TuiThemeBridge`/`ThemeApplicator` 生成 TCSS〔.Screen/.StatusBar 底色移除，裁决⑥〕/`SemanticColors` 委托活跃主题〔裁决⑨，130 调用点机械改写〕）→ T11 map/05 S9 `ThemeSubmenuScreen`（single/automatic 两模式＋预览＋Esc 还原＋对形持久化）→ T12 HTML 导出主题变量（deriveExportColors 逐字含 0.03928 阈值）＋T2 auth.json/models-store.json 读改同锁修真（pi withLock 对位）＋T3 SYSTEM.md/APPEND_SYSTEM.md 发现＋resolvePromptInput file-or-literal＋T1 四核销（E1-5/E3-15/E3-23/E1-4——F6 症状过期）；**B163 销号**；窄差 D123–D133 | 404.5/485 ≈ **83.4%**（+12.5：**E1/E3/E4/A 四域同批满分**＝满分域第七至第十＋E2-9 翻 97.0%）；map/05 S9 缺失→对齐（tui/web 120.5→**121.5/245 ≈ 49.6%**）；全仓 1,087.0/1379 ≈ **78.8%** |
| 22 | docs/33 | 2026-10-25 | 会话管理尾＋自更新终裁（序 21，收尾批）：T1 W15 web 渐进会话列表（`sessionsProgress`/`cancelSessions` 协议帧＋`AgentSession.listSessionsSummary` 进度/信号重载＋WebDispatcher 虚拟线程富列表＋代次闸〔裁决④〕＋前端渐进渲染/取消）→ T2 自更新终裁（裁决①：pi-java 无 npm/managed 分发形态 ⇒ pi 未知形态臂＝终局，E6-10/B#4 翻对齐，D115 保留，pruneManagedReleases 同覆盖）→ T3 R29 终裁不做（裁决②：C 类登记＋map/05 出分母 245→244）；**E6/B 两域同批满分**（满分域第十一、十二——存疑全清零）；**§3 计划表清空**；无新 B/窄差 | 406.5/485 ≈ **83.8%**（+2.0：E6/B 各 +1.0）；map/05 122.5/244 ≈ **50.2%**（W15 +1.0＋R29 出分母）；全仓 1,090.0/1379 ≈ **79.0%** |
| 23 | docs/34 | 2026-10-25 | 触发条件型欠账首批立项（扩展 MCP API 面＋`project_trust`/`mcp_servers_change`＋RPC `get_commands` 扩展臂）：T1 扩展 MCP 三件（三守卫逐字；注册表安家 `DefaultExtensionContext`；`forExtension(name,path)`；**B218 销号**）→ T2 `mcp_servers_change`（装配尾 changeListener 接线＋无处理器臂逐字错文）→ T4 RPC 扩展臂（登记目＋`:N` 撞名编号＋buildCommands 第一臂〔**批 3 窄差 D5 销号**〕＋E5-20 三源序＋`ExtensionSourceInfos`〔E5-34 翻〕；**B221 收编 ⑥，余 ①＝批 24 候选**）→ T3 `project_trust` 全链（first-wins firer＋`ProjectTrustStore`〔trust.json 逐字节线格式/FileChannel 锁——删 `TrustManager`，裁决②〕＋敏感资源门＋七级解析＋预信任装配＋TUI 信任弹窗；**行为变化：ask 默认从「当信任」变 pi 逐字语义**）；窄差 D134–D137；评审无独立轮（用户指令直实施）；verify 收口 5,179 测 0 失败 spotbugs 零钉 | 416.0/485 ≈ **85.8%**（+9.5：**D 域 79.0→83.0/83＝100%**〔满分域第十三，缺失清零〕＋E5 域 23.5→29.0/58 ≈ **50.0%**）；map/07 +4.0（#87＋#81/#84 批 16 回填，79.1%）；全仓 1,103.5/1379 ≈ **80.0%** |

> 口径注记：批 8 起模块分母按**去重** 487（原 496），读数以最新一批闭环回填为准。

---

## §3 计划批次（批 16 起，按依赖排序；闭环时按真实欠账重排）

每行：范围｜主要欠账（加权点）｜依赖/触发条件。

| 序 | 暂定主题 | 欠账 | 备注 |
|---|---|---:|---|
| ~~15~~ | ~~扩展 API 面第一批~~ | — | **批 16 已闭环**（docs/27）：三项＋并入项全落；D#36 随收翻行，**D#11 经裁决⑤改挂 E5-23 批**（发射链唯一生产者＝`registerMcpServer`，map/07 域） |
| ~~16~~ | ~~扩展 API 面第二批（E5：ctx.ui.custom/B214＋D′ 注入层 widget/footer/header）~~ | — | **批 17 已闭环**（docs/28）：序 16 全落＋D 域 #26/#27 随收；E5-31 余 15 成员 TUI 臂挂账（docs/28 §4）；B214 改注（挂载面落、载荷随 MCP 工厂批） |
| ~~17~~ | ~~包管理器第一批（E6：npm/git 源、资源优先级、忽略文件）~~ | — | **批 18 已闭环**（docs/29）：resolve 内核全落＋两 settings 键；窄差 D102–D111（D106 改注＝semver4j 覆盖 hyphen）；E6-16 降级注记（旧 CLI 目录面脱钩） |
| ~~18~~ | ~~包管理器第二批（E6：settings.packages 持久化、CLI 面）~~ | — | **批 19 已闭环**（docs/30）：写回链＋字段级 merge 落盘＋update 机制＋CLI 面重裁＋config TUI 全做（裁决①）；**onMissing TUI 三选取证否定**（pi 锚点不存在该 UI——唯一非空回调 startup-ui.ts:81 恒 `"skip"`，`"error"` 臂无调用方 ⇒ 不做不重排，本条修正序 18 原注记）；窄差 D113–D116；self 真实通道挂序 21（R29 联动） |
| ~~19~~ | ~~RPC/协议尾：B237（handled 三态）＋B253（attribution 头层）＋E7 存疑~~ | — | **批 20 已闭环**（docs/31）：序 19 全落＋**B257 即登即销**（new_session 三瑕疵——取证新发现）；E7 域 100%（第六个满分域）；窄差 D117–D122 |
| ~~20~~ | ~~E1/E3/E4 尾＋JSON 主题系统（2,417 行）~~ | — | **批 21 已闭环**（docs/32）：序 20 全落＋主题运行期重载（/reload 臂＋文件监视）＋A-29＋S9；**E1/E3/E4/A 四域同批满分**；B163 销号；窄差 D123–D133 |
| ~~21~~ | ~~会话管理尾（E3：listSessions onProgress、B54）＋自更新~~ | — | **批 22 已闭环**（docs/33）：W15 web 渐进会话列表（sessionsProgress/cancelSessions＋代次闸＋前端渐进渲染）＋自更新终裁（裁决①：恒不可用臂＝pi 未知形态臂终局——E6-10/B#4 翻对齐，E6/B 两域满分）＋R29 终裁不做（裁决②，C 类＋出分母）。**§3 计划表清空** |

**触发条件型欠账**（不占独立批次，随宿主批顺带收）：~~B246~~（批 16 已销号）、
B202/B203（codemode 消费者，R5）、~~D 域余 2 缺失（#1 project_trust／#11；
#26/#27 批 17 已收）~~（**批 23 全收——D 域满分**）、D41 残余（扩展 abortHandler
的 restore——批 10 已重裁两臂〔Esc 流式／树导航〕，扩展 `ctx.abort()` 两臂随
E5 批次）、D42（摘要窗口期 followUp 滞留压缩缓冲——批 10 给了手动取回通道，
自动重放时机差维持登记）、D67（E2-14 两门无载体——压缩成本通知随 B38 压缩重建
批、thinking-drop 通知随 diagnostics 面批）。批 16 撞出：~~主题运行期重载
（pi reload 的 themes 臂）归批 20（裁决⑨，JSON 主题系统依附）~~——**批 21
已落**（SessionReload ⑤ 主题臂＋`ThemeWatcher` 文件监视热重载＋模式层监听者
收尾，docs/32 T7/T8）。批 23 撞出：**批 24 候选＝内置 MCP 扩展本体**（B221①
剩余——pi `extensions/mcp/index.ts` 1,344 行工厂：六事件订阅＋连接编排＋工具
注册同步＋`/mcp` 命令＋管理器视图载荷〔B214〕＋startupWaitMs；收编 map/07 G 组
余 12 缺失、B188 shellPath/B189/B195、docs/11 D3/D4 `_pendingToolNames`；
docs/34 裁决①立项——面与本体分层，批 23 已把面全落）。

### §3.3 未拍板变量（进 F 类待裁决，每个摆动 1–3 批）

1. **examples/ 16,033 行可执行验收规格（w3）**：全做 ≈ +2 批；裁「代表子集」
   ≈ +1 批；裁「不做」−2 批（F 域行出分母）。
2. ~~**E6 npm 包生态完整度**~~：**已拍板（2026-10-24，批 18 裁决①②③）**——
   方案 D：resolve 机制逐字＋git spawn 真实落地＋npm 改 HTTP registry 实现。
   两批保持（批 18 内核／批 19 CLI＋持久化）。
3. **map/05 TUI/web 屏域是否并入本计划**：S2a–d、S6–S9、S11、S15–S25 等若
   全做再 +4–6 批；本表只含与 coding-agent 绑定的屏行（S12/S13/S20/S22/S23/
   S29/S30）。**批 12 补记（docs/23 裁决⑨）**：MarkdownRenderer 激活
   （map/05 R1，w3，pi 组件 1,025 行域＋R2–R6 联动，牵动 E2-43
   `markdown.codeBlockIndent` 与 D63 changelog 纯文本）计入本变量拍板。

---

## §4 维护规则（闭环回填清单的第三处）

每批闭环时，**在回填 docs/04 台账与 docs/05 地图的同时**，必须更新本文档：

1. §2 表追加该批行（闭环日、范围一句话、账面 Δ 实算值）；
2. §2.5/§3 把该批行移除或改写为下一批的当前状态；
3. §1 总览四项读数全部按闭环后实算重填（含「总批次估计」若 §3 有重排）；
4. 新撞出的整块工作在 §3 插行并注明来源（批几取证撞出）。

> 设计稿 §9「闭环回填」小节照抄以上四处改动作为该批的闭环记录一部分。
> 下一包号（下一份设计稿编号）的权威记忆同步更新。

**2026-10-26 第六次清理＋重编**：本文档自 `docs/20` 重编为 `docs/06`（原 docs/06 ai 未对齐清单同日删除，其开放项全量在 docs/04 §3.2 与 map/02）；全部已闭环批次设计稿（docs/09–19、21–34）同日删除，正文与 §2 表中这些编号均为 git 历史指针（约定见 docs/04 §0/§7 规则 6）。下一包号维持 `docs/35` 不复用。

## §5 与其他权威文档的关系

| 文档 | 管什么 |
|---|---|
| 本文档 | **批次级**进度：多少批、到哪批、每批账面 |
| `docs/04-open-items-register.md` | **条目级**开放项：B 工单/D 窄差/E 结构债/F 待拍板 |
| `docs/05-module-alignment-map.md`＋`docs/map/04` | **行级**对齐判定：逐单元证据与权重 |
| 各批设计稿（原 docs/11–34…，闭环后随清理删除） | **批内**设计、裁决、测试计划；§9 是该批闭环记录；历史在 git |
