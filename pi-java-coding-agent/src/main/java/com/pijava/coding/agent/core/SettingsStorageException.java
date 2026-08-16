package com.pijava.coding.agent.core;

/**
 * Thrown when settings cannot be read or written.
 */
public final class SettingsStorageException extends RuntimeException {

    /**
     * Create an exception with a detail message and underlying cause.
     *
     * @param message detail message
     * @param cause   underlying cause
     */
    public SettingsStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
