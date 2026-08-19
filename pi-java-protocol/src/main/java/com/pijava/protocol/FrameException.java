package com.pijava.protocol;

/**
 * 帧处理异常（超上限 / 残留不完整帧 / 非法状态）。
 */
public final class FrameException extends RuntimeException {

    /** @param message 帧错误描述 */
    public FrameException(String message) {
        super(message);
    }
}
