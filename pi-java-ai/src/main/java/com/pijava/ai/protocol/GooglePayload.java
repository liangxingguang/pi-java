package com.pijava.ai.protocol;

import java.util.List;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;

/**
 * google 车道的 {@code onPayload} 载荷载体 —— pi {@code GenerateContentParameters}
 * （{@code google-generative-ai.ts:95} 的 {@code {model, contents, config}} 单对象）
 * 对位（docs/17 §3.2，窄差 D32：java SDK 是 {@code generateContentStream(model,
 * contents, config)} 三分参调用，无单一参数对象可给）。
 *
 * <p>车道在构建后、HTTP 调用前把三件打包 firing；替换结果经
 * {@code instanceof GooglePayload} 守卫后拆包送回三分参。</p>
 */
public record GooglePayload(String model, List<Content> contents, GenerateContentConfig config) {}
