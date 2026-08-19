package com.pijava.coding.agent.rpc;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RPC 模式扩展 UI 请求（对齐 pi {@code RpcExtensionUIRequest}）。
 *
 * <p>扩展需要用户输入时，stdout 发 {@code {type:"extension_ui_request", id, method,
 * ...}}；method 为 select/confirm/input/editor/notify/setStatus/setWidget/setTitle/
 * set_editor_text。</p>
 */
public record RpcExtensionUIRequest(
    String id,
    String method,
    Map<String, Object> params
) {
    /** 线格式 type（只序列化，反序列化忽略——record 无此字段）。 */
    @JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String type() {
        return "extension_ui_request";
    }
}
