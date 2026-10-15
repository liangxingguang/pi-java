# 08 ai 层对齐路线图（批 0 裁决 ＋ 批 1–7 规划）

> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）／pi-java `edc8749d`。
> pi 证据一律 `git show ea448f454:<path>`；判据＝`bin pi` 主流发布版行为。
> 现状：map/02 加权 **85.0%**（230/195.5，128 单位），权重 3 全齐，欠账在 w1/w2 广度。
> 本文是**路线图**；每批开工前另出文件级详稿（批 1＝[`09`](09-ai-batch1-stream-semantics-design.md)）。

---

## 1. 用户裁决口径（2026-10-12）

1. **bedrock / vertex / azure / radius / codex 五家维持 w0 排除**。取证修正：五家在 bin pi `/login` 里技术上全部可见（`interactive-mode.ts:5788-5818` 枚举全部注册 provider 无白名单，`providers/all.ts:134-176` 无条件注册），w0 依据是「主流 7 家」**权重口径**而非技术不可达；azure（responses 线已有）、radius（pi-messages 线＋OAuth 工厂已有）边际成本最低，口径若松动优先这两家。
2. **模型目录数据＝锚点 npm 包数据转 Java 资源**（批 3）。
3. **做** ChatGPT 订阅登录（#95）；Anthropic workload identity federation（#98，L）**后排**。

## 2. 批 0：裁决翻案（纯文档，先于代码）

### 2.1 R5／出分母（11 个计分母单位，Σw 230→219）

| 单元 | 结论 | 关键证据（消费者） |
|---|---|---|
| #94 结构化分类 API＋4 分类器适配器 | R5 | `classify(` 消费方仅 codemode（`extensions/codemode/execute.ts:715-717`，默认关）、llama 扩展、示例；`docs/models.md:146` 自承不进 `/model` |
| #92 统一模型类型系统 | 随 #33/#94 R5 | 主流只用「是不是 chat」过滤（`provider-composer.ts:565-568`）；java 图像 provider 独立注册已获同等行为，独立行为差≈0 |
| #33 per-provider 图像目录/注册表 | R5 | `generateImages` 调用方仅 codemode/扩展 ctx；无 CLI/TUI 入口；内建实现仅 openrouter 一家（java 已对齐，两侧同状悬空） |
| #11 openai-codex-responses 车道（1699 行含 WebSocket） | w0（用户口径） | `/login` 可见标 "(legacy)"；#74/#78/responseId/endTurn/B126 全部只在该车道内有意义，**一并出分母** |
| #9 bedrock（1417 行）、#10 vertex（554 行） | w0（用户口径） | 企业版另案 |
| #12 cloudflare-ai-binding（90 行） | **作废（死代码）** | 全仓唯一引用是自身测试；不导出、无 lazy、无 importer；cf 网关实际复用 anthropic/openai 车道 |
| #74 transport sse/websocket | 随 #11 R5 | 唯一读点 `openai-codex-responses.ts:294-301` |
| #78 session-resources 注册表 | 随 #11 R5 | 唯一注册者是 codex WS 会话（`api/open-codex-responses.ts:965`），24 行通用件空转 |
| #93 nestedCalls | R5（B162 族） | 仅扩展工具 `ctx.executeTool()` 产生（`extensions/types.ts:394,503`），内置工具零调用 |
| #100 onProviderStreamEvent | R5 | 订阅方仅扩展运行器，唯一订阅者是示例 `examples/extensions/debug-provider.ts:70` |

台账号随附：**B230 azure 改名＋Foundry、B226 Radius** 维持 w0（用户裁决，见 §1）；**B126/B127**：B126 随 codex 出分母，B127 取证已对齐仅补优先级夹具。

### 2.2 零代码销号／关单（形状保留）

| 条目 | 结论 | 证据 |
|---|---|---|
| #87 MistralConversationsCompat | **销号对齐** | 唯一独有字段＝五车道共享 `supportsMidConvoSystemMessages`；mistral 内建模型从不挂 compat；java 行为已在（`Transcripts.java:211`、`MistralConversationsApi.java:318`）。同 #88 类型层先例 |
| B115 终局帧形状 | 关单 | pi `agent-loop.ts:412-460` done/error 只发 message_end；java `PiLoopRunner.java:258-291` 同；print 裸流刻意 proxy 形状已登记 |
| B117 终局 reason 两权威 | 关单 | `StreamPartialBuilder.emitDone` reason 与消息同源（`StreamPartialBuilder.java:506-508`），构造上不可能分叉 |
| #20 StreamError 载 Throwable | 关单（内部形状，线格式已投影） | partial 即 pi 的 error 消息；栈不落线（`JsonEventMapper.terminalWireNode`） |
| #21 UsageInfo 独立事件 | 关单（java 方言，宿主线不可观察） | JSON 线 message_update 顶层 usage 与 pi 同（`JsonEventMapper.java:64-68`） |
| #38 ToolResultMessage.usage | 关单 | 两侧主流同无生产者，details 半已接 |
| B124 maxTokens:0/缺席 | 关单（无行为差） | pi 全仓无 `maxTokens:0` 生产者；java 已 OptionalInt＋`>0` 门控（`AnthropicRequestBuilder.java:94`） |
| B100 compat 探测 baseUrl | 降架构备注 | 仅 models.json baseUrl 与 `--base-url` 并存时可观察，内置模型同值 |
| B147 Responses 回放分组序 | 形状保留＋补序夹具 | java 三桶收集为刻意偏差（`ResponsesMessageConverter.java:426-428` 有注释）；改单循环影响 rs_ id 配对，M 收益不值 |
| B156 ModelCompat builder 化 | backlog | 全仓 13 构造点集中 4 文件且有逐字段注释，无事故记录 |

### 2.3 跨模块另案（ai 包不做，台账挂指针）

- **B94（8 工具 promptSnippet/Guidelines）、B95（docs 段）**：落点 agent-core `ContextAssembler`/`SystemPrompts`＋coding-agent 工具声明（pi `system-prompt.ts:128-166`，消费者 `agent-session.ts:3533-3541`）。
- **B85＋#90 图像缩放管线**：inputLimits **元数据字段批 3a 已补（2026-10-14，#90 转存疑）**；缩放/注记消费链（sharp 替代、`tools/read.ts:138`、`utils/image-process.ts`）在 coding tools 另案（L），落地后 #90 转对齐。
- **#86 promptCache**：ai 侧字段**批 3a 已补**（17 条 anthropic 分片 300/3600）；**CacheWarmer 453 行在 coding-agent 另案**（默认 `cacheWarming:"streaming"` 开、只对直连 Anthropic；字段单做无消费者）。
- **#8 ToolUseContent.namespace**：随 MCP 接线 B203（ai 侧仅 responses 车道 round-trip，`openai-responses-shared.ts` 六处稀疏透传）。
- **B235 agent_settled.aborted**：coding-agent 面，ai 零触点。
- **B148 的消费侧**（/session 费用分组、/bug diagnostics.json、cache-warmer）随各自模块另案，ai 只做字段＋流内触点（批 5）。
- **#98 federation**：后排（L；pi 借官方 SDK 白拿 OIDC 交换，java 要逆协议）。

### 2.4 账面推演（2026-10-12 复核）

- **批 0 后**：分母 230→**219**（11 个 w1 单位移出：#9/#10/#11/#12/#33/#74/#78/#92/#93/#94/#100）；
  wc 195.5→**196.5**（仅 #87 缺失→对齐 +1；#20/#21/#38 是「保留形状」**仍计存疑 0.5**，不加分）⇒ **89.7%**。
- **批 1–7 全部完成后**：wc 再 +9.0 ⇒ **205.5/219 ≈ 93.8%**：
  批 1：#23、#101 存疑→对齐（+1）；批 3：#34(+2)、#86(+1)、#91(+1)、#90 字段半成转存疑(+0.5)；
  批 5：#37 四字段落（responseId/endTurn 随 codex R5）转对齐(+1)；批 7：#95(+1)、#96(+1)、#97(+0.5)。
- **批 3a 后（2026-10-14 实际值）**：wc 197.5→**202.0/219＝92.2%**——#34/+2、#86/+1、#91/+1 已实现，
  #90 缺失→存疑 +0.5（元数据齐、B85 消费链另案）；剩余推演增量：批 5 #37(+1)、
  批 7 #95/#96/#97(+2.5)，全完工仍指向 **205.5（#90 消费链若随 coding tools 落地再 +0.5 → 206/219＝94.1%）**。
  **批 4（B128/B99/B110）2026-10-14 闭环后仍 202.0/219＝92.2%**：三条都是行为台账项，不占加权单元。
  **批 5（B148/B234/消息级 thinkingLevel）2026-10-15 闭环后 → 203.0/219＝92.7%**：#37 转对齐 +1。
  **批 6（B81/B231）2026-10-15 闭环后仍 203.0/219＝92.7%**：台账行为项不占单元。
  **批 7（#95/#96/#97）2026-10-16 闭环后 → 205.5/219＝93.8%**：#95/+1、#96/+1、#97 存疑→对齐 +0.5；
  剩余推演增量：仅 #90 消费链（若随 coding tools 落地再 +0.5 → 206/219＝94.1%）。
- **残余 13.5 权重**（为什么不是 100%）：
  - 缺失 2：**#8 namespace**（随 MCP 包 B203）、**#98 federation**（用户裁决后排）；
  - 半成品 0.5：**#90** 元数据落、图像缩放消费链在 coding tools 另案（L）；
  - 存疑方言 11：w1×10（#4/#5 java 独有 ContentBlock、#7/#63 pi-messages 自造线格、#24/#28 形状等价、#38、#46 java 扩展、#75/#76/#88）＋w2×6（#18 ollama、#20/#21 内部方言线上已扁化、#47 models.json、#68 跨 web/protocol）。
    其中 java **独有扩展**（#4/#5/#18/#46）与**行为等价形状**（#24/#28/#88/#75/#76）可另行逐条裁决——认「行为相同」即翻对齐，不认就永久挂存疑；它们不是「pi 有 java 无」的缺口。
- 跨模块消费者不影响 ai 模块分，但用户可感知面在那些包里闭环：CacheWarmer、B94/B95 系统提示段、B148 的 /session 费用与 /bug、B234 的 "Took" 卡、B235。

## 3. 批次路线

| 批 | 内容 | 条目 | 估时 | 详稿 |
|---|---|---|---|---|
| 1 | ✅ **已闭环 2026-10-13**（3371 测全绿；加权 89.7%→90.2%） | B119/B121/B118、#23、B222、B125、#101；另补 B127 夹具、B147 分组序夹具 | M | **docs/09** |
| 2 | ✅ **已闭环 2026-10-13**（ai 1561 测全绿；60→71 Mistral 夹具） | B80 数组 content 三支＋请求面 thinking 重放、B233 x-affinity＋prompt_cache_key；**B239 即登即销**（assistant tool_calls/prefix 重放） | M | **docs/10** |
| 3a | ✅ **已闭环 2026-10-14**（全量 clean verify 绿：ai 1542＋coding-agent 522 等 11 模块；加权 90.2%→**92.2%**；两 commit e38c3ab9/86465196） | npm `pi-ai@1.1.0` 17 分片 588 chat 逐字节入资源（391KB）＋manifest sha256 锚；promptCache/inputLimits/sptl 三字段；B97/B232 销号；`DefaultModelIds` 默认模型表（B137 防回归切片）；#90 转存疑（消费链 B85 另案） | M | **docs/11** |
| 3b | ✅ **已闭环 2026-10-14**（全量 clean verify 绿：11 模块；ai 1589＋coding-agent 536＋tui 213；加权不变 **92.2%**；两 commit `6f6f6ac3`/`90ae98f7`） | minimatch `*?[]` 子集（tarball 静态取证）、子串/id/name、alias 优先 dated 码点序降序、`:level` 严格/宽容两套、两码诊断逐字、`--models ?? enabledModels` 接通初始模型＋Ctrl+P/RPC scope 轮转；六条窄差裁决见 [`docs/12`](12-ai-batch3b-model-scope-design.md) §4 | M | **[docs/12](12-ai-batch3b-model-scope-design.md)** |
| 4 | ✅ **已闭环 2026-10-14**（全量 clean verify 绿：11 模块 3468 测；ai 1614＋coding-agent 542＋agent-core 661；加权不变 **92.2%**；四 commit `1206eaf2`/`9368bfe1`/`941e6898`/`b1c38a0f`/`aa29df66`/`ae9fc831`，设计稿 `1206eaf2`） | B128 thinkingBudgets 三层通道（settings→harness→StreamRequest，三消费点）；B99 managed effort（raw block_binding/两 beta **请求头**/温度第四门/历史＋队尾 effort marker）；B110 providerThinkingLevel 盖章＋JSONL＋终局帧＋pi-messages 透传。探针附带修正四个旧 beta 从未上头的隐患；bedrock/vertex 与 agent proxy 透传为裁决残留 | M＋L | **[docs/13](13-ai-batch4-thinking-effort-design.md)** |
| 5 | 消息字段与终局计量 | B148 responseModel/diagnostics＋三流内触点；B234 durationMs（消息 S＋工具 M）；消息级 thinkingLevel | L | ✅ **已闭环 2026-10-15**（[`docs/14`](14-ai-batch5-message-fields-design.md)，三 commit；ai 1638／core 674／coding-agent 547／web 53 测；加权 92.2→92.7%，#37 +1）。**残留另案**：B148 消费侧（usage-totals/cache-warmer/bug-report）、B234 的 TUI「Took」卡 |
| 6 | ✅ **已闭环 2026-10-15**（ai 1651 测全绿；加权不变 **92.7%**，行为台账项不占单元；两 commit `2a4466c9`/`e7222774`，设计稿 `eadba279`） | B81 Claude Code 伪装（版本 2.1.280／系统前缀块／身份 beta 对且全序按 pi／工具名出站三映射＋入站 demap）；B231 刷新 15s 超时（PKCE＋device）＋取消安全（锁内读-刷-写跑在脱离调用方中断的虚拟线程上，轮转 token 必落盘）。窄差裁决见 [`docs/15`](15-ai-batch6-oauth-identity-design.md) §4 | M | **[docs/15](15-ai-batch6-oauth-identity-design.md)** |
| 7 | ✅ **已闭环 2026-10-16**（ai 测全绿；加权 92.7→**93.8%**，203.0→205.5；commits `55804990`/`073993f7`/`2f6d2e78`，设计稿 `44d05062`） | #97 共享回调服务器（固定端口/state/品牌页/竞速）→ #95 动态客户端注册；#96 copy-code 同面并入。窄差裁决见 [`docs/16`](16-ai-batch7-shared-callback-chatgpt-design.md) §4 | M-L＋M | **[docs/16](16-ai-batch7-shared-callback-chatgpt-design.md)** |
| 8 | ✅ **已闭环 2026-10-16**（ai **1698** 测全绿；加权不变 **93.8%**，台账行为项不占地图单元；commits `26995e90`/`efa66016`） | B240 销号：ChatGPT 订阅 token（provider=openai＋官方 baseUrl 严格相等＋非 `sk-`）裁 `prompt_cache_retention`/`prompt_cache_options`/`max_output_tokens`/`temperature` 四键（**`prompt_cache_key` 不裁**，锚点纠正台账旧文）；重试三码取证时已在 `RetryableError`；`filterAllModels` 真空（Java 无 classifier 面，随 R5） | S | **[docs/17](17-ai-b240-chatgpt-request-time-design.md)** |

顺序依据：批 1 纯 ai 内、便宜、收口一致性；批 3 是 B232/B97 数据正确性的大头且为 CacheWarmer/thinking 层供字段；批 4/5 依赖批 3 字段；批 6/7 同碰 OAuth 装配，连着做不拆两次。

## 4. 批 3 数据源（已取证，2026-10-12）

- 源：npm `@earendil-works/pi-ai@1.1.0` tarball（锚点 `packages/ai/package.json` version 同版）内 `package/dist/providers/data/*.json`——pi Model schema 原文（camelCase：compat/promptCache/inputLimits/thinkingLevelMap/cost.tiers/type），42 分片＋`.manifest.json`（schemaVersion 6，generatedAt **2026-10-07T22:01Z**，逐文件 sha256）。MIT，随 pi 分发，保留 attribution。
- 取 java 已注册 18 家分片＝**391KB**：anthropic 17 条/11.8KB、openai 44、google 22、mistral 40、xai 4、deepseek 2、openrouter 401 chat＋61 image＋14 classifier/281KB、国内 9 家共 ~50 条。已实测含 haiku-5-5/fable-5/opus-5-5（1M/128K，tier 价 100K 阶梯、promptCache 300/3600、inputLimits 32MB）、Kimi K3 钉价 {3,15,0.3,0}。
- 本地 `D:\workplaceForai\pi\packages\ai\src\providers\data` 是 **2026-10-04 旧锚产物**（旧文件名 azure-openai-responses.json、无 5.5 模型），不可用；该目录 gitignored，`git show` 取不到——这是必须取 npm 包的原因（固定版本、字节可复现）。
- 落地：`pi-java-ai/src/main/resources/catalog/*.json`（现无 resources 目录，新建）＋资源 sha256 对 manifest 的校验夹具；装载复用 `provider/ModelsJsonCompat.java`、`ModelJsonMerge.java` 既有解析链；`RemoteModelWire` 扩 compat/promptCache/inputLimits/tiers 字段（或资源走独立 wire record）。**generatedAt 同步 2026-10-7**，否则 `RemoteCatalogProvider` 的 lastModified≤generatedAt 守卫使 pi.dev overlay 永不生效。
- 逐日精确可选：pi.dev `models/v1/revisions/sha256-<64>/models.all.json` 不可变修订，实施时与 npm 1.1.0 比差集（预计仅新鲜度差）。

## 5. 闭环与验证（每批通用）

1. `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test`；批 1/5 触下游模块，收尾全量 `mvn clean verify`（~34 min）。
2. 每条行为有 wire 级夹具（RecordingHttpServer SSE、Responses*WireTest、SamplingParamsWireTest 先例）；错误文案逐字照 pi。
3. 闭环回填 docs/04（销号/新登）、docs/06、map/02 判定与加权、docs/05 总表；记忆追加。
4. commit `feat(ai): …`/`fix(ai): …`，显式 `git add <path>`，文件 ≤500 行，trailer Co-Authored-By: Claude Code。
