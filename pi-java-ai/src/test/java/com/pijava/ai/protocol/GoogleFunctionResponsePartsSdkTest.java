package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.genai.types.FunctionResponse;
import com.google.genai.types.FunctionResponsePart;
import com.google.genai.types.Part;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包B84 步1：<b>SDK 能力探针</b> —— {@code FunctionResponse.parts} 能不能表达。
 *
 * <p>本类**没有先红**（依赖升级不是行为改动，见 {@code docs/45 §4} 步1）；它的价值在于
 * <b>编译即证明</b>：{@code google-genai 1.15.0} 的 {@code FunctionResponse} 只有
 * {@code willContinue}/{@code scheduling}/{@code id}/{@code name}/{@code response}
 * 五个字段（{@code javap} 实测），{@code FunctionResponsePart} 整个类型不存在 ⇒
 * 本类在 1.15.0 上**编译不过**。把 SDK 降回去，这个文件就是红灯。</p>
 *
 * <p>⚠️ 为什么不能绕：pi 的 gemini 3+ 路径把工具结果图片**内嵌**进
 * {@code functionResponse.parts}（{@code google-shared.ts:315}），而 1.15.0 上
 * 唯一可能的表达是 {@code Part.fromJson(...)} —— 实测它会**静默丢掉**未知键
 * （{@code {"functionResponse":{"…","parts":[…]}}} 往返后 {@code parts} 消失）
 * ⇒ 不是「写法不同」，是**写不出来**。</p>
 *
 * <p>第二条钉 pi 的**条件展开**语义（{@code ...(hasImages && multimodal && { parts })}
 * —— {@code google-shared.ts:315}）：键**不写**时线上就没有该键，不是空数组。
 * 这一条同时是步4实现的地基 —— 内嵌分支必须「不满足就整键不写」。</p>
 */
class GoogleFunctionResponsePartsSdkTest {

    private static final String B64_ALPHA = "YWxwaGE="; // "alpha"

    /**
     * 内嵌图片能序列化出 {@code parts} 键，且形状是
     * {@code {inlineData:{mimeType,data}}}（pi 的 {@code :303-308}）。
     */
    @Test
    void nestedFunctionResponsePartsSerializeOnTheWire() {
        var image = FunctionResponsePart.fromBytes(
            "alpha".getBytes(StandardCharsets.UTF_8), "image/png");
        var part = Part.builder()
            .functionResponse(FunctionResponse.builder()
                .name("read")
                .response(Map.of("output", "alpha text"))
                .parts(image)
                .build())
            .build();

        var json = part.toJson();

        assertThat(json).as("内嵌 parts 键在场").contains("\"parts\"");
        assertThat(json).as("内嵌图走 inlineData").contains("\"inlineData\"");
        assertThat(json).as("mimeType 逐字上线").contains("\"mimeType\":\"image/png\"");
        assertThat(json).as("字节按 base64 上线").contains(B64_ALPHA);
        assertThat(json).as("name 与 response 同框").contains("\"name\":\"read\"")
            .contains("\"output\":\"alpha text\"");
    }

    /**
     * <b>条件展开的等价物</b>：不写 {@code parts} ⇒ 线上**没有** {@code "parts"} 键。
     *
     * <p>pi 用对象展开做到这一点；Java 的 builder 是「没 set 就没有」⇒ 语义天然一致，
     * 但这一条要**钉住**，因为步4的内嵌分支正是靠它对齐 pi 的
     * {@code ...(cond && { parts })}。</p>
     */
    @Test
    void partsKeyIsAbsentWhenNotSet() {
        var json = Part.builder()
            .functionResponse(FunctionResponse.builder()
                .name("read")
                .response(Map.of("output", "alpha text"))
                .build())
            .build()
            .toJson();

        assertThat(json).as("未设置 parts ⇒ 线上无该键（不是空数组）").doesNotContain("\"parts\"");
        assertThat(json).as("其余字段照常").contains("\"name\":\"read\"");
    }
}
