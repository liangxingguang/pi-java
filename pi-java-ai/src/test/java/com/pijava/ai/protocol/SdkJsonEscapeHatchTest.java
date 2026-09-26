package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;

/**
 * <b>包 A3c 的关键前提</b>：两个厂商 SDK 都能用**原始 JSON** 构造它们类型系统里没有的形状。
 *
 * <p>背景：pi 的 TS 侧靠**结构类型**把任意键塞进 SDK 的请求对象
 * （{@code openai-completions.ts:1244} 甚至明写 {@code as unknown as ChatCompletionMessageParam}），
 * 而 Java 侧的类型化参数不容忍未知键。本包一度据此判定 Anthropic 与 Completions 的
 * 原生渲染「不可实施」（{@code docs/51 §3 F13} 的初版），**那是错的** ——
 * 实测两条 SDK 都留着「未知变体」的直通：反序列化时把认不出的 {@code type} 收进
 * {@code _unknown}/{@code _json}/{@code additionalProperties}，序列化时**原样写出**。</p>
 *
 * <p>本类把这条通路钉成可执行的证据（它是 Anthropic 的 {@code tool_addition}/{@code tool_removal}
 * 与 Completions 的 Kimi 形状能落地的**唯一**依据）。<b>判据是逐字节往返</b> ——
 * 只断言「不抛异常」在缺陷态也会绿。</p>
 *
 * <p>⚠️ 这条通路是**非 beta 参数承载 beta 形状**，依赖 SDK 的未知变体直通（不是文档化的
 * 公开承诺）⇒ 升级 SDK 时必须重跑本类。{@code docs/51 §12.4} 记了它。</p>
 */
class SdkJsonEscapeHatchTest {

    /** pi {@code anthropic-messages.ts:1258-1268} 的 {@code tool_removal} 块。 */
    @Test
    void anthropicCarriesToolAdditionAndRemovalBlocksThroughRawJson() throws Exception {
        var mapper = ObjectMappers.jsonMapper();
        var raw = "{\"type\":\"tool_addition\",\"tool\":{\"type\":\"tool_reference\",\"name\":\"late_tool\"}}";

        ContentBlockParam block = mapper.treeToValue(mapper.readTree(raw), ContentBlockParam.class);

        // 认不出的变体落进 _unknown，且 _json() 能读回原样。
        assertThat(block._json()).isPresent();
        assertThat(block._json().orElseThrow().toString()).contains("tool_addition");
        // 与类型化的 text 块放进**同一条** content 列表 —— 这正是系统消息的块序形状。
        var message = MessageParam.builder()
            .role(MessageParam.Role.SYSTEM)
            .content(MessageParam.Content.ofBlockParams(java.util.List.of(
                ContentBlockParam.ofText(com.anthropic.models.messages.TextBlockParam.builder()
                    .text("updated guidance").build()),
                block)))
            .build();

        // 逐字节往返：整条消息序列化出来必须与 pi 的线格同形。
        assertThat(mapper.writeValueAsString(message)).isEqualTo(
            "{\"content\":[{\"text\":\"updated guidance\",\"type\":\"text\"},"
            + "{\"type\":\"tool_addition\",\"tool\":{\"type\":\"tool_reference\",\"name\":\"late_tool\"}}],"
            + "\"role\":\"system\"}");
    }

    /** pi {@code openai-completions.ts:1240-1246} 的 Kimi 形状。 */
    @Test
    void openAiCompletionsCarriesAToolBearingSystemMessageThroughRawJson() throws Exception {
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        var raw = "{\"role\":\"system\",\"content\":\"updated guidance\",\"tools\":"
            + "[{\"type\":\"function\",\"function\":{\"name\":\"late_tool\",\"description\":\"late_tool tool\","
            + "\"parameters\":{\"type\":\"object\"}}}]}";

        var param = mapper.treeToValue(mapper.readTree(raw),
            com.openai.models.chat.completions.ChatCompletionMessageParam.class);

        assertThat(param.system()).isPresent();
        assertThat(mapper.writeValueAsString(param)).isEqualTo(
            "{\"content\":\"updated guidance\",\"role\":\"system\",\"tools\":"
            + "[{\"type\":\"function\",\"function\":{\"name\":\"late_tool\",\"description\":\"late_tool tool\","
            + "\"parameters\":{\"type\":\"object\"}}}]}");
    }

    /**
     * ⚠️ Kimi 消息**没有 {@code content} 键**（pi {@code :1241-1244} 只写 role ＋ tools）
     * —— {@code ChatCompletionSystemMessageParam.content} 在 SDK 里是必填，省略时必须确认
     * 线上**也不出现** {@code content}，否则平白多一个 pi 没有的键。
     */
    @Test
    void openAiCompletionsOmitsContentOnTheKimiSystemMessage() throws Exception {
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        var raw = "{\"role\":\"system\",\"tools\":[{\"type\":\"function\","
            + "\"function\":{\"name\":\"late_tool\"}}]}";

        var param = mapper.treeToValue(mapper.readTree(raw),
            com.openai.models.chat.completions.ChatCompletionMessageParam.class);

        assertThat(mapper.writeValueAsString(param))
            .isEqualTo("{\"role\":\"system\",\"tools\":[{\"type\":\"function\","
                + "\"function\":{\"name\":\"late_tool\"}}]}");
    }
}
