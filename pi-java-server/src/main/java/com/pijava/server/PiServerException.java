package com.pijava.server;

/**
 * 服务端异常。
 */
public final class PiServerException extends RuntimeException {

    /** @param message 错误描述 */
    public PiServerException(String message) {
        super(message);
    }

    /** @param message 错误描述
     *  @param cause   根因 */
    public PiServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
