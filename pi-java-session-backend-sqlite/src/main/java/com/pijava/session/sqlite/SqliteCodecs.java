package com.pijava.session.sqlite;

import java.time.Instant;

import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionJson;

/** Row-encoding helpers shared by storage writes and import replay. */
final class SqliteCodecs {

    private SqliteCodecs() {}

    static String timestampToText(Instant timestamp) {
        return timestamp.toString();
    }

    static String jsonString(String value) {
        try {
            return SessionJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode fact value", e);
        }
    }

    static String recordPayload(LaneRecord record) {
        try {
            return SessionJson.mapper().writeValueAsString(record);
        } catch (Exception e) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Durable payload " + e.getMessage(), e);
        }
    }

    static String recordRunId(LaneRecord record) {
        if (record instanceof LaneRecord.OperationStarted started) {
            return started.id();
        }
        return switch (record) {
            case LaneRecord.OperationFinished r -> r.runId();
            case LaneRecord.StepAttempt r -> r.runId();
            case LaneRecord.ToolStarted r -> r.runId();
            case LaneRecord.QueueEnqueued r -> r.runId();
            case LaneRecord.QueueCancelled r -> r.runId();
            case LaneRecord.WriteDeferred r -> r.runId();
            case LaneRecord.UsageRecord r -> r.runId();
            default -> null;
        };
    }

    static String recordOpKind(LaneRecord record) {
        if (record instanceof LaneRecord.OperationStarted started
                && started.intent() != null) {
            return switch (started.intent()) {
                case LaneRecord.OperationStarted.Run r -> "run";
                case LaneRecord.OperationStarted.Compaction c -> "compaction";
                case LaneRecord.OperationStarted.Navigation n -> "navigation";
            };
        }
        return null;
    }
}
