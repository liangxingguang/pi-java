package com.pijava.ai.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 凭证文件权限工具（pi {@code cli.ts:28-33} 的 {@code chmodSync(0o600)}）。
 *
 * <p>docs/19 #112：只属主可读写。Windows 等非 POSIX 文件系统无法表达该属性，
 * 与 pi 在 Windows 上的 {@code chmodSync} 空操作等价。</p>
 */
final class CredentialFilePermissions {

    private CredentialFilePermissions() {
    }

    /**
     * 把文件收紧到 {@code rw-------}；非 POSIX 文件系统为 no-op。
     *
     * @throws IOException 权限设置失败（与 pi 的 {@code chmodSync} 失败同样中止写入）
     */
    static void restrict(Path path) throws IOException {
        try {
            var view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // 网络文件系统等不在文件系统能力集内：按「不支持」返回，避免正常保存失败。
        }
    }
}
