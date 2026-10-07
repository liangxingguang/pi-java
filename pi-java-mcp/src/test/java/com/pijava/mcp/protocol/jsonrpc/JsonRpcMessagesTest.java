package com.pijava.mcp.protocol.jsonrpc;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcMessagesTest {

    /** Build an object with nullable values. */
    private static Map<String, Object> msg(Object... kv) {
        var map = new HashMap<String, Object>();
        for (var i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> base(Object... kv) {
        return msg(kv);
    }

    @Test
    void parsesRequests() {
        var noParams = (JsonRpcMessage.Request) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "id", 1, "method", "ping"));
        assertThat(noParams.id()).isEqualTo(new JsonRpcId.Number(1.0));
        assertThat(noParams.method()).isEqualTo("ping");
        assertThat(noParams.params()).isNull();

        var params = Map.of("key", "value");
        var withParams = (JsonRpcMessage.Request) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "id", "abc", "method", "x", "params", params));
        assertThat(withParams.id()).isEqualTo(new JsonRpcId.Text("abc"));
        assertThat(withParams.params()).isSameAs(params);

        var decimal = (JsonRpcMessage.Request) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "id", 1.5, "method", "ping"));
        assertThat(decimal.id()).isEqualTo(new JsonRpcId.Number(1.5));
    }

    @Test
    void parsesNotifications() {
        var notification = (JsonRpcMessage.Notification) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "method", "notifications/progress"));
        assertThat(notification.method()).isEqualTo("notifications/progress");
        assertThat(notification.params()).isNull();
    }

    @Test
    void parsesSuccessAndErrorResponses() {
        var success = (JsonRpcMessage.Response.Success) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "id", 7, "result", Map.of("ok", true)));
        assertThat(success.id()).isEqualTo(new JsonRpcId.Number(7.0));
        assertThat(success.result()).isEqualTo(Map.of("ok", true));

        var error = (JsonRpcMessage.Response.Error) JsonRpcMessages.parse(
                msg("jsonrpc", "2.0", "id", 7,
                        "error", Map.of("code", -32601, "message", "no", "data", "x")));
        assertThat(error.error()).isEqualTo(new JsonRpcError(-32601, "no", "x"));
    }

    @Test
    void rejectsInvalidMessages() {
        assertInvalid("non-object", "string");
        assertInvalid("wrong jsonrpc version", base("jsonrpc", "1.0", "id", 1, "method", "x"));
        assertInvalid("non-string method", base("jsonrpc", "2.0", "id", 1, "method", 42));
        assertInvalid("NaN id", base("jsonrpc", "2.0", "id", Double.NaN, "method", "x"));
        assertInvalid("Infinity id", base("jsonrpc", "2.0", "id", Double.POSITIVE_INFINITY, "method", "x"));
        assertInvalid("boolean id", base("jsonrpc", "2.0", "id", true, "method", "x"));
        assertInvalid("null id", base("jsonrpc", "2.0", "id", null, "method", "x"));
        assertInvalid("result and error both present",
                base("jsonrpc", "2.0", "id", 1, "result", Map.of(),
                        "error", Map.of("code", 1, "message", "m")));
        assertInvalid("neither result nor error", base("jsonrpc", "2.0", "id", 1));
        assertInvalid("error with non-number code",
                base("jsonrpc", "2.0", "id", 1,
                        "error", Map.of("code", "x", "message", "m")));
        assertInvalid("error with non-string message",
                base("jsonrpc", "2.0", "id", 1,
                        "error", Map.of("code", 1, "message", 2)));
    }

    private void assertInvalid(String label, Object value) {
        assertThatThrownBy(() -> JsonRpcMessages.parse(value), label)
                .isInstanceOf(McpError.class)
                .satisfies(error -> assertThat(((McpError) error).code())
                        .isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.code()));
    }
}
