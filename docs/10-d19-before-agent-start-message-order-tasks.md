# D19 对齐包任务清单

> 配套设计稿：[`10-d19-before-agent-start-message-order-design.md`](10-d19-before-agent-start-message-order-design.md)（已审）
> 锚点：pi `a00a5048d`｜目标：`map/04` D19 销号

## 任务

- [x] **T1 prompt 臂并入 customs**（`RunStarter.start`）
  - 删除 prompt 臂起跑前的 `appendCustomDraft` 循环；
  - prompt 分支构造 `List<LaneInfo.QueuedItem.Custom>`：nextTurn → handler drafts，交给 `SessionRunner.drive`；
  - 新增私有 `toQueued(CustomMessageDraft)`（复用 `QueuedCustomMessage` 归一）。
- [x] **T2 custom 臂统一落盘路径**（`RunStarter.start`）
  - handler drafts 改走 `appendCustomMessageNow`；
  - 删除 `SessionMessagingLifecycle.appendCustomDraft`（无调用者）。
- [x] **T3 测试**
  - `RunStartWindowTest#promptArmOrdersHookMessagesAfterUserAndNextTurn`（序＋上下文可见）；
  - 增强 `customTriggerFiresCustomHookArmAndOrdersMessages`（工作副本含 draft）；
  - 持久会话断言：`SessionLifecycleEventsTest#beforeAgentStartInjectsCustomMessageAndOverridesSystemPrompt` 补位置断言；
  - `SessionLifecycleEventsTest#messageEndReplacementReachesTranscriptExactlyOnce` 按新事件数更新（多一条 custom 投影的 `message_end`）；
  - `PlanModeExtensionTest#planContextIsInjectedOnlyForUserMessages` 先等运行收口再断言（条目改在 `message_end` 落盘，pi 同）。
- [x] **T4 验证**
  - `mvn -w -pl pi-java-coding-agent -am test -Dtest=RunStartWindowTest,SessionLifecycleEventsTest`（JDK 25，通过）；
  - `mvn verify`（退出码 0）。
- [x] **T5 文档回填**
  - `docs/map/04-coding-agent.md` 删 D19、登记 D322；
  - `docs/04` §0 补 D19 销号与 D322 编号；
  - `docs/05` 读数确认不变（457.0/459＝99.6%）；
  - 设计稿/任务稿暂留工作区（未提交，随下次批量清理）。
