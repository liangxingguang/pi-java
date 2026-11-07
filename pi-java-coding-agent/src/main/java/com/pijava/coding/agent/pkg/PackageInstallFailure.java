package com.pijava.coding.agent.pkg;

/**
 * pi {@code PackageInstallFailure}（package-manager.ts:101-105）——配置的包在
 * resolve 期间装不上时记一条，启动照常继续（issue #11088）。
 *
 * @param source  pi 记录的<b>解析后源串</b>（{@code resolvedSource}，可能是
 *                autoload delta 落到 user 条目的那一支）
 * @param scope   实际安装用的作用域（delta 折叠后）
 * @param message 失败消息（{@code IOException}/{@code RuntimeException} 的 message）
 */
public record PackageInstallFailure(String source, SourceScope scope, String message) {}
