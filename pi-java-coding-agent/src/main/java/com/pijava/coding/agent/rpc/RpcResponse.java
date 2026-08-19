package com.pijava.coding.agent.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 响应信封 —— 成功/失败是同一 shape 的两种取值（对齐 pi {@code rpc-types.ts}），
 * 故用单 record + 可空字段而非 sealed。无载荷时省略 data/error 字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcResponse(
    String id,
    String command,
    boolean success,
    Object data,
    String error
) {
    /** 线格式固定 type="response"。 */
    @JsonProperty("type")
    public String type() {
        return "response";
    }

    /** 成功且无载荷。 */
    public static RpcResponse ok(String id, String command) {
        return new RpcResponse(id, command, true, null, null);
    }

    /** 成功带载荷。 */
    public static RpcResponse ok(String id, String command, Object data) {
        return new RpcResponse(id, command, true, data, null);
    }

    /** 失败。 */
    public static RpcResponse fail(String id, String command, String message) {
        return new RpcResponse(id, command, false, null, message);
    }
}
