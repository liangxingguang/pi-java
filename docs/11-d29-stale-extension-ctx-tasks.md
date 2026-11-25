# D29 对齐包任务清单

> 配套设计稿：[`11-d29-stale-extension-ctx-design.md`](11-d29-stale-extension-ctx-design.md)（待审后才执行）
> 锚点：pi `a00a5048d`｜目标：`map/04` D29 销号

## 任务

- [x] **T1 stale 状态**（`DefaultExtensionContext`／`ExtensionContextStaleness`）
  - `volatile String staleMessage`；`invalidate(String)` 首条 wins＋默认文案逐字＋`surface.invalidateChannels()`；`assertActive()` 抛 `IllegalStateException`。
- [x] **T2 守卫点**
  - action 面经 `surface()` 统一守卫；getter 面（ui/mode/hasUi/sessionManager/modelRegistry）显式守卫；
  - `requireToolControl()` 单点覆盖工具/命令面；`ExtensionHostOperations.requireHost()` 覆盖 10 个宿主成员。
- [x] **T3 失效接线**（`SessionDiscoveryFork`）
  - `closeWith` 在 `fireSessionShutdown` 后 `invalidate(null)`；
  - 删除 `closeForReplacement`/`closeForFork` 的通道半块；
  - `switchTo` 保留 `invalidateChannels()`（原地 attach 不失效）。
- [x] **T4 测试**（`ExtensionStaleContextTest`＋现状回归）
  - close/newSession/fork 后旧 ctx 抛错；首条文案 wins；会话内 ctx 正常；通道退订与 switchTo 不误伤（7 例全绿）。
- [x] **T5 验证**
  - `mvn -q -pl pi-java-coding-agent -am test -Dtest=ExtensionStaleContextTest` 通过（7 例）；
  - 全仓 `mvn -q verify` 退出码 0（2026-11-25）。
- [x] **T6 文档回填**
  - `docs/map/04` 删 D29；`docs/04` §0 补销号；`docs/05` 确认读数（457.0/459＝99.6% 不变）；设计/任务稿暂留工作区。
