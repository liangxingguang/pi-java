package com.pijava.coding.agent.rpc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RPC 模式扩展 UI 响应（对齐 pi {@code RpcExtensionUIResponse}）。
 *
 * <p>客户端从 stdin 回 {@code {type:"extension_ui_response", id, value|confirmed|
 * cancelled}}。value 为 null 时用 confirmed/cancelled 区分确认类交互。</p>
 */
public record RpcExtensionUIResponse(
    String id,
    Object value,
    Boolean confirmed,
    Boolean cancelled
) {
    /** 线格式 type（只序列化，反序列化忽略——record 无此字段）。 */
    @JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String type() {
        return "extension_ui_response";
    }

    /** 便捷构造：input/editor/select 类响应。 */
    public static RpcExtensionUIResponse value(String id, Object value) {
        return new RpcExtensionUIResponse(id, value, null, null);
    }

    /** 便捷构造：confirm 类响应。 */
    public static RpcExtensionUIResponse confirm(String id, boolean confirmed) {
        return new RpcExtensionUIResponse(id, null, confirmed, !confirmed);
    }
}
