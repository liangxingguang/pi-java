package com.pijava.coding.agent.extension;

/**
 * docs/50 §2.1：pi {@code newSession} 的 {@code {cancelled}} 回执。
 *
 * @param cancelled {@code true} ⇒ 扩展经 {@code session_before_switch} 取消了
 *                  新建，未创建任何会话
 */
public record ExtensionNewSessionResult(boolean cancelled) {}
