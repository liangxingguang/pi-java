package com.pijava.protocol;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 传输层字节连接抽象 —— server 监听器与 client 传输共同使用。
 */
public interface ByteConnection extends Closeable {

    /** 读侧。 */
    InputStream in();

    /** 写侧。 */
    OutputStream out();

    @Override
    void close();
}
