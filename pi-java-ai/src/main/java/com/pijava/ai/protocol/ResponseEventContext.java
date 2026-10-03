package com.pijava.ai.protocol;

import java.util.Map;

import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * docs/69：responses 流处理的共享上下文 —— 收敛处理器里逐方法传递的 builder、
 * publisher、槽位表与 grammar 表（消除长参数列表，保持
 * {@code ResponsesStreamProcessor} 不超原行数）。
 */
final class ResponseEventContext {

    final StreamPartialBuilder builder;
    final SubmissionPublisher<StreamEvent> publisher;
    final Map<Long, String> slotTypes;
    final Map<Long, ResponsesStreamProcessor.FunctionCallState> toolCalls;
    final Map<Long, ResponsesCustomCalls.CustomCallState> customCalls;
    final Map<String, String> grammarProperties;
    final ResponsesReasoningCapture reasoningCapture;

    private ResponseEventContext(
            StreamPartialBuilder builder,
            SubmissionPublisher<StreamEvent> publisher,
            Map<Long, String> slotTypes,
            Map<Long, ResponsesStreamProcessor.FunctionCallState> toolCalls,
            Map<Long, ResponsesCustomCalls.CustomCallState> customCalls,
            Map<String, String> grammarProperties,
            ResponsesReasoningCapture reasoningCapture) {
        this.builder = builder;
        this.publisher = publisher;
        this.slotTypes = slotTypes;
        this.toolCalls = toolCalls;
        this.customCalls = customCalls;
        this.grammarProperties = grammarProperties;
        this.reasoningCapture = reasoningCapture;
    }

    static ResponseEventContext of(
            StreamPartialBuilder builder,
            SubmissionPublisher<StreamEvent> publisher,
            Map<String, String> grammarProperties,
            ResponsesReasoningCapture reasoningCapture) {
        return new ResponseEventContext(builder, publisher,
            new java.util.HashMap<Long, String>(),
            new java.util.HashMap<Long, ResponsesStreamProcessor.FunctionCallState>(),
            new java.util.HashMap<Long, ResponsesCustomCalls.CustomCallState>(),
            grammarProperties, reasoningCapture);
    }
}
