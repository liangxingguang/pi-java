package com.pijava.coding.agent.core;

/**
 * Thrown when settings cannot be read or written.
 */
public final class SettingsStorageException extends RuntimeException {

    public SettingsStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
