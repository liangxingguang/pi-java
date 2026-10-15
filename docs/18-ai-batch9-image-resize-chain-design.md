# 批 9 设计：B85/#90 — 图片缩放/注记消费链

- 日期：2026-10-16
- 锚点：pi `ea448f454`（v1.1.0-22），取证全部走 `git show <sha>:<path>`
- 条目：**B85**（`docs/04` §compat：read.ts 图片自动缩放 ＋ 非视觉模型注记）＋ **map/02 #90**
  （`Model.inputLimits`：批 3a 元数据已落、消费链在 coding tools 另案；本批即该另案）
- 模块：`pi-java-agent-core`（管线主体 ＋ read 工具 ＋ 工具结果收口点）、`pi-java-coding-agent`（策略装配）
- 规模：M（无第三方依赖，JDK ImageIO 替代 photon/WASM）

## 1. 要对齐的行为（一句话）

图片进入会话历史**之前**，按**当前模型**的 `inputLimits.images.resize` 档位统一做
「MIME 归一 → 异类转 PNG → 限内直通/超限缩放 → 字节阶梯」处理；处理失败时
read 工具改成省略文本，其它工具保留原图；非视觉模型时 read 加注记。
`images.autoResize` 设置（默认 true）总闸。

批 3a 已把数据面铺完（17 分片普遍带 `"resize":{"maxWidth":2000,"maxHeight":2000,"maxBytes":4718592,"jpegQuality":80}`），
但 ai 层**零消费者**——本批补消费者，map #90 存疑→对齐（+0.5 ⇒ 206/219＝94.1%）。

## 2. pi 逐字取证

### 2.1 read 工具（`packages/coding-agent/src/core/tools/read.ts`，224 行）

非视觉注记（`:88-94`）：

```ts
function getNonVisionImageNote(model: Model<Api> | undefined): string | undefined {
	if (!model || model.input.includes("image")) {
		return undefined;
	}
	return "[Current model does not support images. The image will be omitted from this request.]";
}
```

图片分支（`:128-153`）：

```ts
const nonVisionImageNote = getNonVisionImageNote(ctx?.model);
if (mimeType) {
	// Read image as binary.
	const buffer = await ops.readFile(absolutePath);
	const processed = await processImage(buffer, mimeType, {
		autoResizeImages,
		resizeOptions: ctx?.model?.inputLimits?.images?.resize ?? fallbackResizeOptions,
	});
	if (!processed.ok) {
		let textNote = `Read image file [${mimeType}]\n${processed.message}`;
		if (nonVisionImageNote) textNote += `\n${nonVisionImageNote}`;
		content = [{ type: "text", text: textNote }];
	} else {
		let textNote = `Read image file [${processed.mimeType}]`;
		if (processed.hints.length > 0) textNote += `\n${processed.hints.join("\n")}`;
		if (nonVisionImageNote) textNote += `\n${nonVisionImageNote}`;
		content = [
			{ type: "text", text: textNote },
			{ type: "image", data: processed.data, mimeType: processed.mimeType },
		];
	}
}
```

要点：

- 失败头用**原始** mime，成功头用**处理后** mime（BMP→PNG）。
- 注记只是**文本**；真正的图片省略发生在 ai 层 `transform-messages.ts:36` 降级闸
  （Java 侧 `TransformMessages.java:122` 已在，`ModelInfo.supportsImageInput()` 三态语义）。
- 构造选项（`:63-70`）：`autoResizeImages ?? true`、`fallbackResizeOptions`（无模型元数据时）。
- 描述含一句 `Images are sent as attachments.`（`:104`）。

### 2.2 MIME 嗅探（`packages/coding-agent/src/utils/mime.ts`）

文件只嗅前 **4100** 字节（`:5`），逐字判定（`:7-27`）：

```ts
if (startsWith(buffer, [0xff, 0xd8, 0xff])) {
	return buffer[3] === 0xf7 ? null : "image/jpeg";
}
if (startsWith(buffer, PNG_SIGNATURE)) {
	return isPng(buffer) && !isAnimatedPng(buffer) ? "image/png" : null;
}
if (startsWithAscii(buffer, 0, "GIF87a") || startsWithAscii(buffer, 0, "GIF89a")) return "image/gif";
if (startsWithAscii(buffer, 0, "RIFF") && startsWithAscii(buffer, 8, "WEBP")) return "image/webp";
if (startsWithAscii(buffer, 0, "BM") && isBmp(buffer)) return "image/bmp";
return null;
```

- JPEG 第 4 字节 `0xF7`（JPG 保留标记）拒（`:9-11`）。
- PNG 要 **8 字节全签**＋偏移 8 BE32==13 且偏移 12 为 `"IHDR"`（`isPng`），
  且 IDAT 前不得出现 `acTL`（aPNG 拒，`:39-51`）。
- BMP 要过结构校验：声明文件大小、像素数据偏移、DIB 头 12 或 40..124、
  colorPlanes==1、bitsPerPixel ∈ {1,4,8,16,24,32}（`:53-86`）。
- 嗅不出来 ⇒ 当**文本**读（read.ts 走文本分支）。

### 2.3 processImage（`utils/image-process.ts`，119 行）

归一集合只有四种（`:30-42`）：`image/png`、`image/jpeg`（jpg 同义归一）、`image/gif`、`image/webp`；
mime 参数取 `;` 前 trim lower-case（`baseMimeType`）。

其它一切（含 BMP）走 `convertImageBytesToPng`（photon 解码→PNG 重编码），
失败 ⇒ `{ ok:false, message:"[Image omitted: could not be converted to a supported inline image format.]" }`。

`autoResizeImages`（默认 true）时（`:84-110`）：

1. `resizeImage` 返回 null ⇒ `"[Image omitted: could not be resized below the inline image size limit.]"`；
2. 成功 ⇒ hints 依次追加：
   - 转换提示 `[Image converted from ${from} to ${to}.]`（`:62-66`）；
   - 尺寸注记（见 2.5）。
3. **关闭时仍做异类转换**（BMP→PNG 不依赖缩放开关，pi 夹具钉死），只跳过 resize。

### 2.4 resize 核心（`utils/image-resize-core.ts`，174 行）

默认档（`:24-34`）：maxWidth 2000、maxHeight 2000、maxBytes **4.5×1024×1024 base64 字节**、jpegQuality 80。

算法（`:56-167`）：

1. `inputBase64Size = ceil(byteLength/3)*4`；先解码取原始宽高（同时应用 EXIF 朝向）；
2. 宽高≤限**且** base64 体积 `< maxBytes` ⇒ **原字节**直通，`wasResized:false`
   （EXIF 变换在此分支被丢弃——只有真重编码才应用朝向）；
3. 初始目标边：超宽则按比收宽，再超高收高（四舍五入）；
4. 质量阶梯 `[jpegQuality, 85, 70, 55, 40]` 去重保序；每档尺寸：Lanczos3 缩放一次，
   候选顺序 **PNG 先**，再各质量 JPEG，取首个 `encodedSize < maxBytes`；
5. 仍超 ⇒ 宽高各 `floor(*0.75)`（≥1），直到 1×1；不可推进 ⇒ null；任何解码异常 ⇒ null。

### 2.5 尺寸注记（`utils/image-resize.ts:110-121`）

```ts
const scale = result.originalWidth / result.width;
return `[Image: original ${result.originalWidth}x${result.originalHeight}, displayed at ${result.width}x${result.height}. Multiply coordinates by ${scale.toFixed(2)} to map to original image.]`;
```

`wasResized:false` ⇒ 无注记。worker 包装（`image-resize.ts`/`image-resize-worker.ts`）只是
把 WASM 挪出 TUI 事件循环 ＋ Bun 回退；Bun 字符串路径等不移植。

### 2.6 工具结果统一收口（`utils/tool-result-images.ts`，67 行 ＋ `agent-session.ts:699-723`）

`_afterToolCall` 在**扩展钩子之后**对最终 content 调 `normalizeToolResultImages`（`:705-712`）：

```ts
const resizeOptions = this._limitsModel()?.inputLimits?.images?.resize;
const normalizedContent = await normalizeToolResultImages(content, {
	autoResizeImages: this.settingsManager.getImageAutoResize(),
	...(resizeOptions ? { resizeOptions } : {}),
});
```

`_limitsModel()`＝`routedModel?.model ?? this.model`（`:640-642`）。

normalize 语义（tool-result-images.ts）：

- 无 image 块 ⇒ **原数组同一引用**返回；
- 每块跑 processImage；失败 ⇒ **保留原块**（与 read 的省略相反，注释：工具已产出图，
  后端不可用不该静默删输出）；
- 产物 data/mime 全等且无 hints ⇒ 留原块；否则换图，hints 合并成一块 **text 追加在该图之后**；
- 周围文本与顺序保留（pi 夹具：`[text, 大图, text] → [text, 图, text(提示), text]`）。

### 2.7 设置与装配

- `settings-manager.ts:1298-1300`：`settings.images?.autoResize ?? true`；
  默认 `settings-defaults.ts:40-43`：`images: { autoResize: true, blockImages: false }`。
- `_buildRuntime`（`:3611-3623`）每次建工具集时把 `{ read: { autoResizeImages } }` 传进去。
- 提示图片另有 `_normalizePromptImages`（`:1942-1961`）与 CLI `file-processor.ts`
  （`main.ts:223` 以 `autoResizeImages:false` 调，延迟到 prompt 派发再缩）。

### 2.8 元数据 schema（`packages/ai/src/providers/model-schema.ts:47-82`）

`ModelImageResizeOptions`（maxWidth/maxHeight/maxBytes/jpegQuality，皆 optional int）、
`images.resize/maxPerMessage/maxPerRequest`、`maxRequestBytes`。
**后三者 pi 全局零消费者**（grep 仅命中 schema 自身）——批 3a 已落数据，本批不造消费。

## 3. Java 现状盘点

| 面 | 现状 | 差距 |
|---|---|---|
| `ReadTool.java`（agent-core，149 行） | 嗅出图片即 `[text("Read image file [mime]"), ImageContent(原字节)]` | 无归一/转换/缩放/失败省略/尺寸注记/非视觉注记；描述少 `Images are sent as attachments.` |
| `PathUtils.detectImageMimeType` | 4 字节 PNG 签、JPEG 不查 0xF7、不查 IHDR/aPNG、BMP 只看 `BM` | 按 §2.2 逐条加严 |
| `InputLimits`/`ResizeOptions`（ai，批 3a） | 三层 record 齐，17 分片有值 | **零消费者** |
| `ModelInfo.supportsImageInput()` | 三态（未知按支持），`TransformMessages:122` 降级闸已在 | 直接用作非视觉判据 |
| `ToolContext` | cwd/env/shell/fs，**无模型面** | 加图片策略晚绑定口（§4.3） |
| `PiToolRunner` | after_tool 钩子后 `toOutcome` 入历史 | 钩子之后缺 normalize（§4.4） |
| `Settings.Image.autoResize` | JSON 边界已解析 | 无读取口；装配不接线 |
| `PromptImage`/`fileArgs` | **零生产者**（fileArgs 仅 parser 测试引用） | 真空臂，见 §6 |
| MCP 工具出图 | `McpResultContent.java:196` 产 `ImageContent` | 收口点接上即被覆盖（真实消费者） |

## 4. 设计

### 4.1 新类（`pi-java-agent-core`，包 `com.pijava.agent.tool.image`）

| 类 | 对应 pi | 职责 |
|---|---|---|
| `ImagePolicy`（record，放 `com.pijava.agent.tool`） | read 选项＋`ctx.model` 两面合一 | `(boolean autoResize, InputLimits.ResizeOptions resize, boolean supportsImages)`；`DEFAULT = (true, null, true)` |
| `ImageProcessor` | image-process.ts | `process(byte[] bytes, String mime, ImagePolicy)` → sealed `ProcessedImage.Ok/Failure`；MIME 归一、BMP→PNG、hints |
| `ImageResizer` | image-resize-core.ts | 默认档、限内直通、PNG/JPEG 候选、质量阶梯、0.75 收边、1×1 终局；`Result` 携带 original/current 宽高 |
| `ExifOrientation` | exif-orientation.ts | JPEG（APP1/EXIF）＋WebP（RIFF EXIF chunk）TIFF 朝向解析，八向仿射变换 |
| `WebpDimensions`（并入 ImageResizer 同文件或独立小类） | pi 无（photon 直接解码） | RIFF `VP8 `/`VP8L`/`VP8X` 头取画布宽高（裁决 D1） |
| `ToolResultImages` | tool-result-images.ts | `normalize(List<ContentBlock>, ImagePolicy)`：引用恒等、失败留原图、hints 追文本 |

文件均 ≤500 行；`ImageIO` 为 JDK 自带，**不加依赖**（native-image 已判不现实，无约束）。

### 4.2 算法的 Java 映射

- 解码：`ImageIO.createImageInputStream` ＋ `ImageReader` 判格式/取宽高（轻量，不全量栅格化）；
  需要重编码时再 `reader.read(0)` 得 `BufferedImage`。GIF 取首帧（同 photon）。
- 缩放：`Graphics2D`，`VALUE_INTERPOLATION_BICUBIC` ＋ `VALUE_RENDER_QUALITY`
  （裁决 D2，非 Lanczos3）；每档尺寸都从**原图**重缩（同 pi 的 `photon.resize(image,…)`）。
- PNG：`ImageIO` png writer。JPEG：`ImageWriteParam.setCompressionQuality(q/100.0f)`；
  alpha 图先 flatten 到 `TYPE_INT_RGB` 白底（裁决 D3）。
- 候选与循环逐行照 §2.4：PNG 先、质量阶梯去重保序、base64 UTF-8 字节比较、floor×0.75、停在 1×1。
- 限内直通**不解码栅格**：jpeg/png/gif 用 ImageReader 头宽高（失败 ⇒ null，同 pi 解码失败语义）；
  webp 用自写头解析（D1）。EXIF 仅在重编码路径应用（Java 仿射对应 pi 八向）。
- 注记/省略/头文案**逐字**照 §2.1/§2.3/§2.5；scale 用 `%.2f`（Locale.ROOT）等价 `toFixed(2)`。

### 4.3 策略到执行点的通路（裁决 D8）

pi 是「构造期 `read:{autoResizeImages}` ＋ 执行期 `ctx.model`」双通道。Java 的
`ToolContext` 会话级构造、模型会 Ctrl+P 轮转，故合并成**一个晚绑定口**：

```java
// ToolContext 增加
private final Supplier<ImagePolicy> imagePolicy;
public ToolContext(..., )                 // 旧四参构造保留 ⇒ () -> ImagePolicy.DEFAULT
public ToolContext(..., Supplier<ImagePolicy>)
public ToolContext withImagePolicy(Supplier<ImagePolicy> s) // 复制 cwd/env/shell/fs
public ImagePolicy imagePolicy() { return imagePolicy.get(); }
```

`ReadTool` 执行时读 `context.imagePolicy()`：`resize()`→模型档位（null 走引擎默认档，
等价 pi 无模型时的 `fallbackResizeOptions` 缺席），`supportsImages()`→非视觉注记，
`autoResize()`→总闸。**不移植** `ReadToolOptions`/`fallbackResizeOptions` 构造参数
（Java 工具集无会话构造缝，策略全部 context 化；`ToolSetFactory` 签名不动）。

### 4.4 两个收口点

1. **`ReadTool.execute` 图片分支**：`ImageProcessor.process` → 成功两块（text＋image，
   mime 取处理后），失败单文本；非视觉注记两路径都追加（§2.1）。
2. **`PiToolRunner.execute`**：`fireAfterTool` 之后、`toOutcome` 之前，对
   `finalized.result().content()` 调 `ToolResultImages.normalize(...)`；返回不同列表才
   重建 `ToolResult`（details/usage/terminate/addToolNames/isError/structuredContent 原样）。
   无钩子路径同样经过（pi 的 normalize 在钩子系统外无条件跑）；immediate/error 路径只含
   文本，normalize 同引用直通，不另接。

### 4.5 coding-agent 装配

`AgentSession.assemble`：`sessionRef` 已在 harness 构造前声明（压缩观察者同款晚绑定），
把传入的 `toolContext` 换成

```java
toolContext.withImagePolicy(() -> {
    var s = sessionRef.get();
    return s == null ? ImagePolicy.DEFAULT() : s.currentImagePolicy();
})
```

`AgentSession.currentImagePolicy()`（包可见，便于单测）：

- `autoResize`：`services().settings().effective().images` 为 null 或字段 null ⇒ **true**
  （pi `?? true`）；
- `resize`：`modelCatalog.find(harness.getModel())` →
  `inputLimits().images().resize()`，缺席 null；
- `supportsImages`：命中 `ModelInfo.supportsImageInput()`；目录未命中 ⇒ true
  （沿用既有「未知按支持」裁决，与 pi 注记只在**模型确知无视觉**时出现同效）。

`harness.getModel()` 是当前车道模型，对应 `_limitsModel` 的「当前模型」语义
（routedModel 虚拟模型面 Java 无，不造）。

## 5. 窄差与裁决

| # | 差 | 裁决 |
|---|---|---|
| D1 | JDK 无 WebP 编解码器（photon 有） | 限内 webp：自写 RIFF 头解析取宽高 ＋ 体积达标 ⇒ **原字节直通**（与 pi 限内输出一致）；超限 webp 无法重编码 ⇒ 走 pi 同款 "could not be resized" 省略文本，等同 pi「photon 不可用」分支。不引第三方 ImageIO 插件（native 依赖，违反纯 JDK 线） |
| D2 | Lanczos3 vs Java 重采样 | BICUBIC＋RENDER_QUALITY；夹具只断言宽高/体积/文案，不断言像素 |
| D3 | JPEG 无 alpha | flatten 白底再编码 |
| D4 | worker 线程不移植 | Java 工具已在 harness 工具执行器（B176 虚拟线程）上跑，ImageIO 阻塞不占循环线程 |
| D5 | EXIF 面 | 移植 JPEG APP1 与 WebP RIFF EXIF 解析＋八向变换；WebP 因 D1 不会重编码故其 EXIF 实际不可达（留解析同 pi 形状）；PNG 无 EXIF（pi 也只查 jpeg/webp） |
| D6 | 嗅探 I/O | pi 先读 4100 字节再读全文；Java 已一次读全文件，嗅探只看头部字节，检测判定逐字等价 |
| D7 | GIF 动图 | pi 也不拒 GIF（只拒 aPNG），首帧重编码丢动画，两侧同 |
| D8 | 策略通道 | 见 §4.3：Supplier 晚绑定替代构造选项＋ctx.model |
| D9 | 非视觉省略 | read 只加注记；真正省略＝`TransformMessages` 既有降级闸，不新造 |
| D10 | 0xF7/IHDR/aPNG/BMP 加严 | 逐字照抄；既有 `ReadToolTest.readsImageFile` 的 20 字节假 PNG 桩（全零 IHDR）会变文本读取——换 ImageIO 写真 PNG，属对齐修正 |

## 6. 明确不做（真空臂 ＋ 其它面）

1. **@file 附件管线**（pi `cli/file-processor.ts`）：Java `Args.fileArgs()` 主代码零消费者
   （只 parser 夹具），`PromptImage` 零生产者 ⇒ `_normalizePromptImages` 无对象可处理，
   不造投机管线；待 @file/附件功能立项时本处理器直接复用（类已在 agent-core）。
2. **剪贴板粘贴**（`clipboard-image.ts`，含 BMP 转换）：TUI 输入面，另案。
3. **TUI Kitty 转码器**（`image-convert.ts` 的 `convertToPng`/`ensurePngTranscoder`）：
   终端渲染面，非入模管线。
4. **设置 UI 开关**（settings-selector 的 autoResize 项）：B221 TUI 面；本批只保证
   settings.json 字段生效（手改/`/set` 既有通道）。RPC setter 若不存在不补。
5. **maxPerMessage/maxPerRequest/maxRequestBytes 消费**：pi 也零消费。
6. **read 的 outputSchema/structuredContent**（codemode `image()` 消费）：codemode 面。
7. **blockImages 设置**：独立特性（消息变换阻断），非 B85。
8. **image/classifier 模型面**（`model-runtime-images.test.ts` 那类）：R5。

## 7. 测试计划

新增/改写（agent-core，除非注明）：

- `PathUtilsImageMimeTest`（或并入既有 PathUtils 夹具）：0xF7 JPEG 拒、全签 PNG、
  IHDR 缺失拒、aPNG（acTL 在 IDAT 前）拒、GIF87a/89a、WEBP、BMP 24bpp 1×1 接受
  （移植 pi 手写 BMP 构造）、planes/bpp 非法拒、短缓冲安全。
- `ImageProcessorTest`：BMP→PNG（autoResize true/false 两路径，验 PNG magic＋转换 hint）、
  限内 PNG/JPEG 原 base64 恒等且无 hints、不可解码字节 ⇒ 两种省略文案、
  `image/jpg` 归一 `image/jpeg`、mime 带 `;charset=` 截取。
- `ImageResizerTest`：`ImageIO` 造 2400×4800 PNG ⇒ 两边 ≤2000、比例与注记
  （`original 2400x4800`、scale `%.2f`）；`maxBytes=1` ⇒ null；2×2 JPEG 限内
  `wasResized=false`；默认档常量 2000/2000/4.5MiB/80；质量阶梯顺序由小图不触发侧面钉。
- `WebpDimensionsTest`：手构 VP8/VP8L/VP8X 三Chunk字节验宽高（解析单测，不依赖解码器）。
- `ExifOrientationTest`：JPEG APP1 EXIF 八朝向（用 ImageIO 写图后手工塞 EXIF 段或
  资源字节；至少钉 orientation=6/3 的解析与变换后宽高互换）。
- `ToolResultImagesTest`：移植 pi 六案（无图同引用、限内同引用、超限换图＋尾文本、
  关闸同引用、BMP 关闸仍转换、不可解码留原图、前后文本顺序）。
- `ReadToolTest`：假 PNG 桩换真 PNG；新增 不可解码.png ⇒ 单文本含 "Image omitted"；
  policy(supportsImages=false) ⇒ 注记逐字出现且图块仍在；autoResize=false 大图原样。
- `PiToolRunnerTest`：假工具回超大 BufferedImage PNG ⇒ 入历史的 ToolResultMessage
  内容被换（≤2000＋尾文本），autoResize=false ⇒ 原样；验证钩子之后执行（顺序可借
  既有 HookTest 风格）。
- coding-agent：`ImagePolicyResolutionTest` 直调包可见策略构造
  （settings 缺省 true、`images.autoResize:false`、目录命中模型的 resize 档与非视觉位、
  未编目模型⇒supports=true），不起整会话。

## 8. 验证与闭环

- `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 D:/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-agent-core,pi-java-coding-agent -am test`；
  收尾 `mvn checkstyle:check`，必要时全量 verify。
- Commit 计划（显式 `git add <path>`、trailer `Co-Authored-By: Claude Code <noreply@anthropic.com>`）：
  1. `docs(agent-core): batch-9 design — image resize/annotation chain`（本稿）
  2. `feat(agent-core): resize and normalize inline images with model inputLimits (B85/#90)`
  3. `feat(coding-agent): wire image auto-resize setting and current model profile`
  4. `docs(ai): batch-9 closure — B85/#90 image chain aligned, weighted 93.8->94.1%`
- 闭环回填：`docs/04` B85 销号行、`docs/06` §3 #90 存疑→对齐与批次头、
  `docs/map/02` #90 判定翻转（＋证据列/加权边际表/元数据簇计数）、`docs/05` 总表
  （ai 93.8→94.1%＝206/219；总表按其算术更新）、`docs/08` 批 9 行；记忆追加。
