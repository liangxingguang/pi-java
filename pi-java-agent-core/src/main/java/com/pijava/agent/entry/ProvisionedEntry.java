package com.pijava.agent.entry;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An entry that has been provisioned (has an id) but whose
 * {@code seq}/{@code parentId}/{@code timestamp} are assigned by the storage
 * on commit (aligned with pi's {@code ProvisionedEntry} type alias).
 *
 * <p>The {@code seq}/{@code parentId}/{@code timestamp} components of the
 * wrapped entry are placeholders; callers must only read {@code id()} and the
 * type-specific payload fields.</p>
 *
 * @param <T> the concrete entry type
 */
public final class ProvisionedEntry<T extends Entry> {

    private final T entry;

    public ProvisionedEntry(T entry) {
        this.entry = entry;
    }

    /**
     * The provisioned entry. {@code @JsonValue} makes the wrapper serialize
     * as the entry itself, matching pi's type-alias semantics.
     */
    @JsonValue
    public T entry() {
        return entry;
    }
}
