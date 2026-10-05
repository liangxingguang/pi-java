package com.pijava.agent.session;

/**
 * Query parameters for finding lane records. {@code null} fields are unfiltered.
 *
 * <p>The lane-record family is this repo's own side-channel audit layer: pi
 * removed its record/query layer before anchor {@code 200387122} — at the pin
 * {@code RecordQuery} survives only in {@code packages/agent/CHANGELOG.md} and
 * the record family is absent from pi's source — so this type has no pi
 * counterpart to align with (B168, {@code docs/16}).
 *
 * @param lane          exact lane, {@code null} = all lanes
 * @param type          exact record discriminant, {@code null} = all types
 * @param runId         matches {@code operation_started.id} or the {@code runId}
 *                      property of operation-owned records
 * @param operationKind only valid when {@code type == "operation_started"}
 * @param afterSeq      exclusive chronological lower bound (seq &gt; afterSeq), {@code null} = none
 * @param order         default {@link EntryOrder#NEWEST_FIRST}
 * @param limit         positive maximum results, {@code null} = unlimited
 */
public record RecordQuery(
    String lane,
    String type,
    String runId,
    OperationKind operationKind,
    Long afterSeq,
    EntryOrder order,
    Integer limit
) {

    /** Query all records, newest first, unlimited. */
    public static RecordQuery all() {
        return new RecordQuery(null, null, null, null, null, EntryOrder.NEWEST_FIRST, null);
    }
}
