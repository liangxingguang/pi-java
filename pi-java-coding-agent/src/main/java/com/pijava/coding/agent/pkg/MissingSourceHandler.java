package com.pijava.coding.agent.pkg;

/**
 * pi {@code resolve(onMissing)} 的回调（package-manager.ts:920）——缺包时
 * 三选：skip/error/install。本批会话装配恒 null（pi 同：无回调 ⇒ 缺包直装）；
 * TUI 三选询问臂随批 19。
 */
@FunctionalInterface
public interface MissingSourceHandler {

    MissingSourceAction onMissing(String source);
}
