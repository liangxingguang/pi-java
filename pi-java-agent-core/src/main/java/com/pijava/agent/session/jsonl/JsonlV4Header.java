package com.pijava.agent.session.jsonl;

import java.util.Map;

/**
 * The first line of a JSONL v4 session file (aligned with pi
 * {@code JsonlV4Header}). The header carries no {@code seq}.
 *
 * @param kind                 always {@code "header"}
 * @param version              4 for current files; 3 marks a legacy file
 * @param id                   session id
 * @param createdAtMs          epoch milliseconds
 * @param cwd                  the session working directory
 * @param parentSessionId      may be null (root sessions omit the key)
 * @param legacyParentSessionPath v3 parent path, mutually exclusive with
 *                             {@code parentSessionId}
 * @param metadata             application metadata, may be null
 */
public record JsonlV4Header(
    String kind,
    int version,
    String id,
    long createdAtMs,
    String cwd,
    String parentSessionId,
    String legacyParentSessionPath,
    Map<String, Object> metadata
) {

    /** A current-format header. */
    public static JsonlV4Header v4(String id, long createdAtMs, String cwd,
                                   String parentSessionId, Map<String, Object> metadata) {
        return new JsonlV4Header("header", 4, id, createdAtMs, cwd,
            parentSessionId, null, metadata);
    }
}
