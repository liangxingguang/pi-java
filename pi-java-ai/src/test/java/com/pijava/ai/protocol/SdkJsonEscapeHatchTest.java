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
 * 原生渲染「不可实施」（{@code 原 docs/51 §3 F13} 的初版），**那是错的** ——
 * 实测两条 SDK 都留着「未知变体」的直通：反序列化时把认不出的 {@code type} 收进
 * {@code _unknown}/{@code _json}/{@code additionalProperties}，序列化时**原样写出**。</p>
 *
 * <p>本类把这条通路钉成可执行的证据（它是 Anthropic 的 {@code tool_addition}/{@code tool_removal}
 * 与 Completions 的 Kimi 形状能落地的**唯一**依据）。<b>判据是逐字节往返</b> ——
 * 只断言「不抛异常」在缺陷态也会绿。</p>
 *
 * <p>⚠️ 这条通路是**非 beta 参数承载 beta 形状**，依赖 SDK 的未知变体直通（不是文档化的
 * 公开承诺）⇒ 升级 SDK 时必须重跑本类。{@code 原 docs/51 §12.4} 记了它。</p>
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

    /**
     * <b>包 A-09（R11）的前提实测</b>：additional body property 里的**显式 null**。
     *
     * <p>pi 的 {@code JSON.stringify} 保 {@code "key": null}（{@code Literal(null)} 是
     * 「写这个键，值是 null」）；SDK 的 {@code JsonValue.from(Map)} 走它自己的 mapper ——
     * 若带 NON_NULL inclusion，null 值会被**静默丢掉**。两条路各测一次：</p>
     * <ul>
     *   <li>{@code JsonValue.from(map 含 null)} —— 实测被丢（下方断言钉住「不可用」）；</li>
     *   <li>先以无 inclusion 设置的 mapper 建树（{@code NullNode} 保留），再
     *       {@code JsonValue.from(tree)} —— 树序列化不经过 POJO inclusion ⇒ null 存活。</li>
     * </ul>
     *
     * <p>⚠️ 与上面三条同一纪律：这条通路依赖 SDK 的序列化细节（不是文档化承诺）⇒
     * 升级 SDK 必须重跑本类。</p>
     */
    @Test
    void openAiAdditionalBodyPropertiesCanCarryAnExplicitNull() throws Exception {
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("keep", "x");
        map.put("nul", null);

        // ⚠️ 观测面是 JsonValue 本身，不是 CreateParams —— `writeValueAsString(params)`
        // 得 `{}`（SDK 把请求包在 body 里，原 docs/54 §12 的 A-01 教训）。
        var fromMap = mapper.writeValueAsString(com.openai.core.JsonValue.from(map));
        assertThat(fromMap).contains("\"keep\":\"x\"");
        assertThat(fromMap).as("JsonValue.from(map) 丢 null 值 ⇒ 不能直接喂 map")
            .doesNotContain("nul");

        var tree = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(map);
        var fromTree = mapper.writeValueAsString(com.openai.core.JsonValue.from(tree));
        assertThat(fromTree).contains("\"keep\":\"x\"");
        assertThat(fromTree).as("树路径必须保住显式 null").contains("\"nul\":null");
    }
}
