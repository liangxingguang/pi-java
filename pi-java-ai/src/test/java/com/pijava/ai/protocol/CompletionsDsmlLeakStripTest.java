package com.pijava.ai.protocol;

import java.util.List;
import java.util.stream.Collectors;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.openai.models.chat.completions.ChatCompletionMessageParam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 系端点的<b>模板回显清洗</b>：模型偶发把原生工具调用模板
 * （{@code <｜｜DSML｜｜ invoke …>}，DSML 全角竖线 U+FF5C）当**纯文本**吐进
 * content，端点不拦截 ⇒ 工具不执行、回复断在半截；且这段文本一旦回传进历史，
 * 后续轮次模型看到上下文里的模板就持续模仿（级联：一次泄漏，此后每次工具
 * 调用都泄漏）。
 *
 * <p>裁决：pi 上游无此逻辑（同类端点怪癖按 B152 先例登记窄差）。清洗只在
 * **回传历史**时做（确定性字符串处理），不动转录存储、不动流式增量。</p>
 */
class CompletionsDsmlLeakStripTest {

    /** 实测泄漏样本（2026-11-09 ark-code-latest→deepseek-v4-1-flash-economy）。 */
    private static final String LEAK = """
        <｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke name="bash"> \
        <｜｜DSML｜｜ parameter name="command" string="true">cd /repo && sed -n 90,300p PiLoop.java\
        </｜｜DSML｜｜ parameter> </｜｜DSML｜｜ invoke> </｜｜DSML｜｜ calls>""";

    private static Message.AssistantMessage assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static String assistantWireText(StreamRequest request) {
        List<ChatCompletionMessageParam> params =
            OpenAICompletionsMessageConverter.buildParams(request, "openai-completions", null)
                .messages();
        return params.stream()
            .filter(ChatCompletionMessageParam::isAssistant)
            .map(p -> p.asAssistant().content().get().asText())
            .collect(Collectors.joining("\n---\n"));
    }

    private static StreamRequest request(Message... messages) {
        return StreamRequest.of(ModelId.of("deepseek", "deepseek-chat"), List.of(messages));
    }

    @Test
    void leakedTemplateIsRemovedFromAssistantHistory() {
        var request = request(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("解析一下PiLoop"))),
            assistant("我先把 PiLoop.java 完整读一遍再解析。\n\n" + LEAK),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("继续"))));
        String wire = assistantWireText(request);
        assertThat(wire).doesNotContain("DSML");
        assertThat(wire).doesNotContain("sed -n 90,300p");
        assertThat(wire).contains("我先把 PiLoop.java 完整读一遍再解析。");
    }

    /** 嵌套（parameter 包在 invoke 内）必须整块裁掉——裸参数值（命令文本）不能残留。 */
    @Test
    void nestedInvokeIsStrippedWhole() {
        var request = request(assistant("前文。\n\n" + LEAK + "\n\n后文。"));
        String wire = assistantWireText(request);
        assertThat(wire).doesNotContain("DSML");
        assertThat(wire).doesNotContain("cd /repo");
        assertThat(wire).contains("前文。");
        assertThat(wire).contains("后文。");
    }

    /** 正常文本不受影响；无泄漏时逐字往返。 */
    @Test
    void cleanTextRoundTripsVerbatim() {
        var request = request(assistant("普通分析文本，含 < > 与引号 \"ok\"。"));
        assertThat(assistantWireText(request))
            .isEqualTo("普通分析文本，含 < > 与引号 \"ok\"。");
    }

    /** 泄漏整块占满文本 ⇒ 清洗后为空串（合法 assistant content）。 */
    @Test
    void leakOnlyTextCollapsesToEmpty() {
        var request = request(assistant(LEAK));
        String wire = assistantWireText(request);
        assertThat(wire).doesNotContain("DSML");
    }
}
