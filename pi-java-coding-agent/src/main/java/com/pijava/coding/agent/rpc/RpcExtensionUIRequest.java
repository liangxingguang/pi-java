package com.pijava.coding.agent.rpc;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RPC 模式扩展 UI 请求（对齐 pi {@code RpcExtensionUIRequest}，
 * rpc-types.ts:253-297）。
 *
 * <p>扩展需要用户输入时，stdout 发 {@code {type:"extension_ui_request", id, method,
 * ...}}；method 为 select/confirm/input/editor/notify/setStatus/setWidget/setTitle/
 * set_editor_text。<b>参数键平铺在顶层</b>（pi {@code output({type, id, ...request})}
 * 的 spread 逐字，rpc-mode.ts:117）——{@code params} 经 {@link JsonAnyGetter} 拍平
 * 序列化；java 侧从不读取请求（宿主侧才读），反序列化不支持平铺形。</p>
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

    /** 参数平铺到顶层（pi 的 spread 对位；null 值由生产者侧省略——pi 的 undefined 键不落线）。 */
    @JsonAnyGetter
    @Override
    public Map<String, Object> params() {
        return params == null ? Map.of() : params;
    }
}
