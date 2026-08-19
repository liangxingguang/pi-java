package com.pijava.client;

/**
 * 客户端异常。
 */
public final class PiClientException extends RuntimeException {

    /** @param message 错误描述 */
    public PiClientException(String message) {
        super(message);
    }

    /** @param message 错误描述
     *  @param cause   根因 */
    public PiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
