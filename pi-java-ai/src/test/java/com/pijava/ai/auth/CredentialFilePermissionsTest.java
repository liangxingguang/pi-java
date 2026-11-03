package com.pijava.ai.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * docs/19 #112：凭证文件 POSIX 权限（pi {@code cli.ts:28-33}）。
 *
 * <p>Windows 无法表达 POSIX 权限 ⇒ 本地权限断言跳过；非 POSIX 的「不抛异常」
 * 用例守住回归，真实断言在 Linux CI 上执行。</p>
 */
class CredentialFilePermissionsTest {

    @TempDir
    Path tmp;

    @Test
    void restrictMakesNewFileOwnerOnly() throws Exception {
        var path = tmp.resolve("new.json");
        Files.createFile(path);
        assumePosix(path);

        CredentialFilePermissions.restrict(path);

        assertThat(Files.getPosixFilePermissions(path))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void restrictTightensExistingBroadPermissions() throws Exception {
        var path = tmp.resolve("broad.json");
        Files.createFile(path);
        assumePosix(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"));

        CredentialFilePermissions.restrict(path);

        assertThat(Files.getPosixFilePermissions(path))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void restrictDoesNotThrowWhenPosixIsUnavailable() {
        var path = tmp.resolve("plain.json");
        assertThatCode(() -> {
            Files.writeString(path, "{}");
            CredentialFilePermissions.restrict(path);
        }).doesNotThrowAnyException();
    }

    @Test
    void fileCredentialStoreTightensFileBeforeWriting() throws Exception {
        var path = tmp.resolve("auth.json");
        var store = new FileCredentialStore(path);
        store.storeApiKey("openai", "sk");
        assumePosix(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"));

        store.storeApiKey("openai", "sk-2");

        assertThat(Files.getPosixFilePermissions(path))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void oauthCredentialStoreTightensFileBeforeWriting() throws Exception {
        var path = tmp.resolve("auth-oauth.json");
        var store = new OAuthCredentialStore(path);
        store.store("anthropic", OAuthCredential.permanent("token"));
        assumePosix(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"));

        store.store("anthropic", OAuthCredential.permanent("token-2"));

        assertThat(Files.getPosixFilePermissions(path))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    private static void assumePosix(Path path) {
        Assumptions.assumeTrue(
            Files.getFileAttributeView(path, PosixFileAttributeView.class) != null,
            "POSIX file attributes unavailable on this platform");
    }
}
