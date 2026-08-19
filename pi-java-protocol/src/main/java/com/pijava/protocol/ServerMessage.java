package com.pijava.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 服务端消息（对齐 pi {@code ServerMessageSchema}）：
 * ServerHello | ServerHelloError | ResponseEnvelope | EventEnvelope。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ServerMessage.ServerHello.class),
    @JsonSubTypes.Type(value = ServerMessage.ServerHelloError.class),
    @JsonSubTypes.Type(value = ServerMessage.ResponseEnvelope.class),
    @JsonSubTypes.Type(value = ServerMessage.EventEnvelope.class)
})
public sealed interface ServerMessage {

    /** 线格式 type 值。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("hello")
    record ServerHello(int version, String connectionId, ServerSnapshot snapshot)
        implements ServerMessage {
        @Override public String type() { return "hello"; }
    }

    @JsonTypeName("hello_error")
    record ServerHelloError(ProtocolError error) implements ServerMessage {
        @Override public String type() { return "hello_error"; }
    }

    @JsonTypeName("response")
    record ResponseEnvelope(String id, CommandResult result, ProtocolError error)
        implements ServerMessage {
        @Override public String type() { return "response"; }
    }

    @JsonTypeName("event")
    record EventEnvelope(ServerEvent event) implements ServerMessage {
        @Override public String type() { return "event"; }
    }
}
