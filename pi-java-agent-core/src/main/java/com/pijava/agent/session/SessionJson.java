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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Shared Jackson mapper for the session layer (JSONL codec and SQLite
 * payload codec). Configures epoch-ms {@link Instant} encoding and explicit
 * {@code role + content}/{@code type + fields} node building for messages and
 * content blocks so the JSON shape matches pi byte-for-byte (Jackson's
 * polymorphic type info is unreliable inside generic collections).
 */
public final class SessionJson {

    private static final ObjectMapper MAPPER = createMapper();

    private SessionJson() {}

    /** The shared mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Validate that a payload is JSON-serializable (no cycles, no
     * non-serializable leaves), throwing {@code invalid_payload} otherwise.
     */
    public static void assertSerializable(Object value) {
        try {
            MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Durable payload " + e.getMessage(), e);
        }
    }

    /** Build the pi-shaped JSON node for a message ({@code role + content}). */
    public static ObjectNode messageNode(Message message) {
        var node = MAPPER.createObjectNode();
        node.put("role", message.role());
        ArrayNode content = node.putArray("content");
        for (var block : message.content()) {
            content.add(blockNode(block));
        }
        if (message instanceof Message.ToolResultMessage tool) {
            node.put("toolUseId", tool.toolUseId());
            node.put("toolName", tool.toolName());
            node.put("isError", tool.isError());
        }
        return node;
    }

    /** Build the pi-shaped JSON node for a content block ({@code type + fields}). */
    public static ObjectNode blockNode(ContentBlock block) {
        var node = MAPPER.createObjectNode();
        switch (block) {
            case ContentBlock.TextContent t -> {
                node.put("type", "text");
                node.put("text", t.text());
            }
            case ContentBlock.ThinkingContent t -> {
                node.put("type", "thinking");
                node.put("text", t.text());
            }
            case ContentBlock.ImageContent i -> {
                node.put("type", "image");
                node.put("mediaType", i.mediaType());
                node.put("data", i.data());
            }
            case ContentBlock.UrlImageContent u -> {
                node.put("type", "image_url");
                node.put("url", u.url());
            }
            case ContentBlock.ToolUseContent t -> {
                node.put("type", "tool_use");
                node.put("id", t.id());
                node.put("name", t.name());
                node.set("arguments", MAPPER.valueToTree(t.arguments()));
            }
            case ContentBlock.ToolResultContent t -> {
                node.put("type", "tool_result");
                node.put("toolUseId", t.toolUseId());
                node.put("toolName", t.toolName());
                ArrayNode content = node.putArray("content");
                for (var inner : t.content()) {
                    content.add(blockNode(inner));
                }
                node.put("isError", t.isError());
            }
            case ContentBlock.DiffContent d -> {
                node.put("type", "diff");
                node.put("diffText", d.diffText());
            }
        }
        return node;
    }

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var module = new SimpleModule("pi-session");
        module.addSerializer(Instant.class, new InstantEpochMsSerializer());
        module.addDeserializer(Instant.class, new InstantEpochMsDeserializer());
        module.addSerializer(Message.class, new MessageSerializer());
        module.addSerializer(ContentBlock.class, new ContentBlockSerializer());
        // Wildcard generic cast: ProvisionedEntry.class is raw; the serializer accepts any subtype.
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Class provisionedEntryType = ProvisionedEntry.class;
        module.addSerializer(provisionedEntryType,
            (com.fasterxml.jackson.databind.JsonSerializer) new ProvisionedEntrySerializer());
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
            gen.writeTree(messageNode(value));
        }
    }

    /** Serialize a {@link ContentBlock} with its explicit {@code type} field. */
    static final class ContentBlockSerializer extends JsonSerializer<ContentBlock> {
        @Override
        public void serialize(ContentBlock value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeTree(blockNode(value));
        }
    }

    /**
     * Serialize a {@link ProvisionedEntry} as its inner entry, preserving the
     * polymorphic {@code type} field (Jackson drops it through wildcard types).
     */
    static final class ProvisionedEntrySerializer extends JsonSerializer<ProvisionedEntry<?>> {
        @Override
        public void serialize(ProvisionedEntry<?> value, JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            gen.writeTree(MAPPER.valueToTree(value.entry()));
        }
    }
}
