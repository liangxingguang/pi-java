package com.pijava.server;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import com.pijava.protocol.ByteConnection;
import com.pijava.protocol.ByteConnections;

/**
 * Unix Domain Socket 监听器（JDK AF_UNIX；Windows 10/11 亦原生支持，无 TCP
 * fallback —— 相对 pi 在 win32 直接抛异常的能力增强）。
 */
public final class UnixSocketListener implements PiServerListener {

    private final Path socketPath;
    private ServerSocketChannel channel;
    private Thread acceptThread;

    /** @param socketPath Unix socket 路径 */
    public UnixSocketListener(Path socketPath) {
        this.socketPath = socketPath;
    }

    @Override
    public Optional<String> address() {
        return Optional.of(socketPath.toString());
    }

    @Override
    public void start(Consumer<ByteConnection> accept) {
        try {
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            channel.bind(UnixDomainSocketAddress.of(socketPath));
            acceptThread = Thread.ofPlatform().start(() -> {
                while (channel.isOpen()) {
                    try {
                        SocketChannel conn = channel.accept();
                        if (conn != null) {
                            accept.accept(ByteConnections.from(conn));
                        }
                    } catch (IOException e) {
                        if (channel.isOpen()) {
                            // 单连接接受失败不终止监听
                        }
                    }
                }
            });
        } catch (IOException e) {
            throw new PiServerException("Cannot bind unix socket " + socketPath, e);
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭中
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        try {
            java.nio.file.Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // 清理失败可忽略
        }
    }
}
