# 29 - L7 W8 任务清单

> 配套 [`29-w8-design.md`](29-w8-design.md)。改动不 commit。

## T1 K17 keybindings.json 用户覆盖

- [x] `KeybindingsManager`：legacy 迁移表、`create()`／`create(Path)`、`reload()`、`effectiveBindings()`
- [x] JSON 读取：跳过 `$schema`、字符串/数组校验、空数组解绑、解析失败静默
- [x] 键解析：`ctrl/alt/shift+key`，`escape`→`esc`，未知丢弃
- [x] 覆盖落位：全局表／overlay 表／双表同有；未知动作忽略
- [x] TUI 构造点改用 `create()`
- [x] `KeybindingsManagerTest` 九类用例
- 验收：`mvn test -pl pi-java-coding-agent -Dtest=KeybindingsManagerTest` — 通过

## T2 会话选择器渐进保选中＋搜索置顶

- [x] `SessionListModel`：`pendingSelectedPath`、4 参 `setSessions`、`touchSelection` 清 pending、`selectTopForSearch`
- [x] `SessionSelectorComponent`：`handleSearchInput`（query 变化门控＋非空置顶）、两处转发替换、progress partial=true
- [x] `SessionListModelTest`／`SessionSelectorComponentTest` 新用例
- 验收：定向测试通过

## T3 "to resume" 独立成行＋flush

- [x] `PiTuiLauncher.printResumeHint` 两行＋`flush`
- [x] `PiTuiLauncherExitTest` 同步断言
- 验收：定向测试通过

## T4 伙伴 provider `✦` 标记

- [x] `LoginProviderScreen.label` 追加 `" ✦"`（`PreferredProviders.isPreferredProvider`）
- [x] `LoginProviderScreenTest` 新用例
- 验收：定向测试通过

## T5 全量测试

- [x] `mvn -pl pi-java-coding-agent -am -DskipTests install`
- [x] `mvn test -pl pi-java-coding-agent,pi-java-tui`
- 验收：coding-agent 2,056／tui 904（1 跳过），0 失败；Checkstyle 0 error

## T6 复核与地图回填

- [x] `map/05`：K17 缺失→对齐；S27 存疑→对齐；S28/R28/W16 证据补窄差；品牌动画/彩蛋登记 N-W8-1／D-W8-2/3/4
- [x] `map/05` 汇总与未加权读数重算（232.5/252＝92.3%；133/153）
- [x] `docs/05` §1.1/§1.2、`docs/09` L7 收口、`docs/04` §0 追加 W8
- [x] `docs/29` §6 实施结果

## T7 收尾自查

- [x] 无未使用代码/导入；新增类均有 Javadoc 头
- [x] 文件行数 < 500（`KeybindingsManager` 464；`KeybindingsNames` 81）
- [x] 工作区 diff 覆盖设计稿全部条目
