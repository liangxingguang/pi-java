package com.pijava.server;

import java.io.Closeable;
import java.util.Optional;
import java.util.function.Consumer;

import com.pijava.protocol.ByteConnection;

/**
 * 传输监听器 —— 提供已完成传输层认证的字节连接（对齐 pi {@code PiServerListener}）。
 */
public interface PiServerListener extends Closeable {

    /** 启动后的可读地址（若传输有地址）。 */
    Optional<String> address();

    /** 启动监听；每接受一条连接调用 {@code accept}。 */
    void start(Consumer<ByteConnection> accept);

    @Override
    void close();
}
