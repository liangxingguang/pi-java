package com.pijava.ai.stream;

/** Provider-local identity of one streaming tool call; never projected into message content. */
public record ToolCallSlotKey(String providerIndex, String idFallback) {

    /** Key a call by the provider's stable stream/output index. */
    public static ToolCallSlotKey indexed(long index) {
        return indexed(Long.toString(index));
    }

    /** Key a call by a provider index whose wire representation is textual. */
    public static ToolCallSlotKey indexed(String index) {
        return new ToolCallSlotKey(index, null);
    }

    /** Key a call by id only on lanes that have no provider stream index. */
    public static ToolCallSlotKey fallback(String id) {
        return new ToolCallSlotKey(null, id);
    }
}
