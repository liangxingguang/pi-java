package com.pijava.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 客户端消息（对齐 pi {@code ClientMessageSchema}）：ClientHello | RequestEnvelope。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClientMessage.ClientHello.class),
    @JsonSubTypes.Type(value = ClientMessage.RequestEnvelope.class)
})
public sealed interface ClientMessage {

    /** 线格式 type 值。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("hello")
    record ClientHello(int version) implements ClientMessage {
        @Override public String type() { return "hello"; }
    }

    @JsonTypeName("request")
    record RequestEnvelope(String id, Command request) implements ClientMessage {
        @Override public String type() { return "request"; }
    }
}
