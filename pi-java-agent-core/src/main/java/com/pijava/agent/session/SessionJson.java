package com.pijava.agent.session;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.pijava.ai.message.Message;

/**
 * Shared Jackson mapper for the session layer (JSONL codec and SQLite
 * payload codec). Configures epoch-ms {@link Instant} encoding and the
 * {@code role + content} shape of {@link Message}, matching pi's JSON.
 */
public final class SessionJson {

    private static final ObjectMapper MAPPER = createMapper();

    private SessionJson() {}

    /** The shared mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var module = new SimpleModule("pi-session");
        module.addSerializer(Instant.class, new InstantEpochMsSerializer());
        module.addDeserializer(Instant.class, new InstantEpochMsDeserializer());
        module.addSerializer(Message.class, new MessageSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    /** Serialize {@link Instant} as integer epoch milliseconds. */
    static final class InstantEpochMsSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeNumber(value.toEpochMilli());
        }
    }

    /** Deserialize integer epoch milliseconds into {@link Instant}. */
    static final class InstantEpochMsDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Instant.ofEpochMilli(p.getLongValue());
        }
    }

    /** Serialize a {@link Message} as {@code {role, content}} (pi shape). */
    static final class MessageSerializer extends JsonSerializer<Message> {
        @Override
        public void serialize(Message value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("role", value.role());
            gen.writeFieldName("content");
            gen.writeObject(value.content());
            gen.writeEndObject();
        }
    }
}