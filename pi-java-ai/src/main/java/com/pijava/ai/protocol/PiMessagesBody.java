package com.pijava.ai.protocol;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * docs/17 结构拆分：pi-messages 的请求 body 构建（原在 {@link PiMessagesApi} 内）。
 *
 * <p>从车道类移出，只为守住 ≤500 行；行为零变化。body 形状是 pi
 * {@code pi-messages.ts:374-390} 的 {@code {model, context:{messages}, options}}。</p>
 */
final class PiMessagesBody {

    private PiMessagesBody() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * docs/17：序列化前移到调用点（{@code onPayload} 钩要拿 envelope 本体）——
     * 本方法只构建，返回 {@link ObjectNode}。
     */
    static ObjectNode buildBody(StreamRequest request) {
        try {
            // pi api/pi-messages.ts:374-390 —— body 是 `{model, context, options}`，而
            // `context` **就是** TranscriptContext：只有 `messages`，系统提示与工具声明都在
            // 消息数组里（`9e05370b2` 起形参从 `Context` 改成 `TranscriptContext`）。
            // ⚠️ 旧实现发的是 `{systemPrompt?, messages, tools?}`（＝重构**前**的 `Context`）
            // —— 包 A2 的 R2 把它对齐（原 docs/49 §4.2 F2）。本车道不折叠、不切头：对端自己读
            // 数组里的系统消息。
            var context = JSON.createObjectNode();
            var messages = context.putArray("messages");
            for (var msg : request.transcript().messages()) {
                messages.add(toWireMessage(msg));
            }
            var options = JSON.createObjectNode();
            if (request.temperature() >= 0) {
                options.put("temperature", request.temperature());
            }
            if (request.maxTokens() > 0) {
                options.put("maxTokens", request.maxTokens());
            }
            var body = JSON.createObjectNode();
            body.put("model", request.modelId().modelName());
            body.set("context", context);
            body.set("options", options);
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build pi-messages body", e);
        }
    }

    private static ObjectNode toWireMessage(Message msg) {
        if (msg instanceof Message.UserMessage u) {
            return wireMessage("user", u.content());
        }
        if (msg instanceof Message.AssistantMessage a) {
            return wireMessage("assistant", a.content());
        }
        if (msg instanceof Message.ToolResultMessage t) {
            var node = wireMessage("toolResult", t.content());
            node.put("toolCallId", t.toolUseId());
            node.put("toolName", t.toolName());
            return node;
        }
        if (msg instanceof Message.SystemMessage s) {
            var node = wireMessage("system", s.content());
            // 缺席纪律同 SessionJson 的 A7 规则：pi 写的是 `...(x ? {x} : {})`，
            // 空集合在线上没有对应键，而 Jackson 会把它们照样写出来 ⇒ 主动省略。
            if (s.timestamp() != null) {
                node.put("timestamp", s.timestamp().toEpochMilli());
            }
            if (!s.sections().isEmpty()) {
                // 逐项写，不走 valueToTree：值为 null 的段是 pi 的「删掉这一段」
                // （types.ts:501），而 valueToTree 的 null 处理**取决于映射器的包含策略**
                // —— 本类今天的默认策略保留 null，但把这件事绑在映射器的默认值上是脆的
                // （SessionJson 的映射器带 NON_NULL，同样的写法在那里会把删除项丢掉）。
                var sectionsNode = JSON.createObjectNode();
                for (var entry : s.sections().entrySet()) {
                    if (entry.getValue() == null) {
                        sectionsNode.putNull(entry.getKey());
                    } else {
                        sectionsNode.put(entry.getKey(), entry.getValue());
                    }
                }
                node.set("sections", sectionsNode);
            }
            if (!s.toolsAdded().isEmpty()) {
                node.set("toolsAdded", toolDeclarations(s.toolsAdded()));
            }
            if (!s.toolsRemoved().isEmpty()) {
                node.set("toolsRemoved", JSON.valueToTree(s.toolsRemoved()));
            }
            return node;
        }
        throw new IllegalArgumentException("Unknown message type: " + msg);
    }

    /**
     * pi 的 ai 层 {@code Tool} 形状（{@code types.ts:600-605}）：**三个**字段
     * {@code {name, description, parameters}}。
     *
     * <p>投影本身在 {@link Transcripts#toToolDeclaration} —— 包 B87② 之前这里是**第一个**
     * 按 pi 形状写它的地方，而 {@code SessionJson} 的系统消息落线仍写
     * {@link ToolDefinition} 全形（{@code docs/05} B87② 登记的形状未定问题）。两处现在
     * 共用同一份投影，线上字节因此一致。</p>
     */
    private static ArrayNode toolDeclarations(List<ToolDefinition> tools) {
        var arr = JSON.createArrayNode();
        for (var td : tools) {
            var declaration = Transcripts.toToolDeclaration(td);
            // 逐项写：constrainedSampling 缺席（null）时**不发键** —— 本 mapper 是默认
            // ALWAYS 包含，直接 valueToTree 会写出 null 值（与 pi 的 defined-only 不一致）。
            var node = arr.addObject();
            node.put("name", declaration.name());
            if (declaration.description() != null) {
                node.put("description", declaration.description());
            }
            node.set("parameters", JSON.valueToTree(declaration.parameters()));
            if (declaration.constrainedSampling() != null) {
                node.set("constrainedSampling",
                    JSON.valueToTree(declaration.constrainedSampling()));
            }
        }
        return arr;
    }

    private static ObjectNode wireMessage(String role, List<ContentBlock> blocks) {
        var node = JSON.createObjectNode();
        node.put("role", role);
        node.set("content", toWireContent(blocks));
        return node;
    }

    private static ArrayNode toWireContent(List<ContentBlock> blocks) {
        var arr = JSON.createArrayNode();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) {
                arr.addObject().put("type", "text").put("text", tc.text());
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                arr.addObject().put("type", "thinking").put("thinking", th.text());
            } else if (block instanceof ContentBlock.ToolUseContent tool) {
                arr.addObject().put("type", "toolCall")
                    .put("id", tool.id()).put("name", tool.name())
                    .set("arguments", JSON.valueToTree(tool.arguments()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                arr.addObject().put("type", "image")
                    .put("mediaType", img.mediaType())
                    .put("data", img.data());
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                arr.addObject().put("type", "image_url").put("url", url.url());
            }
        }
        return arr;
    }
}
