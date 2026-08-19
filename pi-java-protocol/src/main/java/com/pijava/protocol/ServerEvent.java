package com.pijava.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 服务端推送事件（对齐 pi {@code ServerEventSchema}，4 个）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ServerEvent.ServerSnapshotEvent.class),
    @JsonSubTypes.Type(value = ServerEvent.SessionSnapshotEvent.class),
    @JsonSubTypes.Type(value = ServerEvent.SessionProgress.class),
    @JsonSubTypes.Type(value = ServerEvent.SessionRemoved.class)
})
public sealed interface ServerEvent {

    /** 线格式 type 值。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("server_snapshot")
    record ServerSnapshotEvent(ServerSnapshot snapshot) implements ServerEvent {
        @Override public String type() { return "server_snapshot"; }
    }

    @JsonTypeName("session_snapshot")
    record SessionSnapshotEvent(SessionSnapshot snapshot) implements ServerEvent {
        @Override public String type() { return "session_snapshot"; }
    }

    @JsonTypeName("session_progress")
    record SessionProgress(String sessionId, TranscriptProgress progress)
        implements ServerEvent {
        @Override public String type() { return "session_progress"; }
    }

    @JsonTypeName("session_removed")
    record SessionRemoved(String sessionId) implements ServerEvent {
        @Override public String type() { return "session_removed"; }
    }
}
