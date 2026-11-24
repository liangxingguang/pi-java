# L7 context footer 任务清单（B293）

> 依赖设计：[`31-l7-context-footer-design.md`](31-l7-context-footer-design.md)。批准后按序执行。

| 任务 | 内容 | 验收 |
|---|---|---|
| [x] T1 | agent-core：新增 `ContextUsage`；`SessionSnapshot` 追加 `contextUsage`/`autoCompact` | 编译通过；record 形状与设计 D1/D2 一致 |
| [x] T2 | agent-core：`SnapshotService.buildContextUsage()` 按 pi `getContextUsage` 实现；`HarnessBootstrap` 接线 | 新单测四分支全绿（空会话/正常/压缩后 null/压缩后正常） |
| [x] T3 | tui：`StatusBar` context 段＋`formatTokens`＋`?`/`0.0`＋颜色阈值 | 新单测通过；`42.2%/272k (auto)` 命中 pi oracle 正则 |
| [x] T4 | 测试更新：两处 `new SessionSnapshot`；新增 StatusBar/SnapshotService 测试 | `mvn test -pl pi-java-agent-core,pi-java-tui -am` 全绿 |
| [x] T5 | 文档：B293 文案修正＋销号；map/06 #20 改判；docs/05、docs/09、ANCHOR 读数回填 | 四处读数一致：L6 49/49、全仓 1,369.5/1,389＝98.6% |
| [x] T6 | 全仓验证与提交 | `mvn clean verify "-Dfrontend.skip=true"` 零失败零告警；按用户指示提交推送 |

## 完成判据

- 内置状态行可见 `percent%/window (auto)`（越界 `?`、无 usage `0.0%/0`），与 pi footer 同形。
- `docs/04` B293 根因表述准确：缺的是内置 context footer 状态行，`SessionSnapshot` 字段只是必要管道之一。
- 全仓发布读数 98.6%，L6 100%；无新增 B/D 号（D183/D189 保留）。
