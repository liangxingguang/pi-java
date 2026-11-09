package com.pijava.ai.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek 系端点的<b>工具调用模板回显清洗</b>（openai-completions 车道，
 * assistant 历史回传路径）。
 *
 * <p>背景（2026-11-09 实测）：模型偶发把原生工具调用模板当**纯文本**吐进
 * {@code content}——标记形如 {@code <｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke
 * name="…"> <｜｜DSML｜｜ parameter …>…</｜｜DSML｜｜ parameter> </｜｜DSML｜｜
 * invoke> </｜｜DSML｜｜ calls>}（{@code ｜} 为全角竖线 U+FF5C），端点不拦截 ⇒
 * 工具不执行、回复断在半截。更重的是这段文本会进转录历史，回传后模型看到
 * 上下文里的模板就持续模仿——一次泄漏，之后每次工具调用都泄漏（级联）。</p>
 *
 * <p>处理：只裁**回传历史**里的泄漏块（平衡深度扫描——{@code parameter} 嵌套
 * 在 {@code invoke} 内，裸参数值如命令文本必须随块一起裁掉），替换为简短占位；
 * 不动转录存储、不动流式增量。pi 上游无此逻辑——按 B152（TeamoRouter→DeepSeek
 * 端点怪癖）先例登记窄差。回归钉 {@code CompletionsDsmlLeakStripTest}。</p>
 */
final class DsmlToolCallStrip {

    /** DSML 标记（开/合通用形式，{@code ｜}＝U+FF5C）。 */
    private static final Pattern TAG = Pattern.compile("</?｜｜DSML｜｜[^>]*>");
    private static final String TAG_MARK = "｜｜DSML｜｜";
    private static final String OPEN = "<｜｜DSML｜｜";
    /** 泄漏块的替换占位。 */
    private static final String OMITTED = "[malformed tool call removed]";

    private DsmlToolCallStrip() {
    }

    /** 无泄漏时逐字返回；有泄漏时按平衡块裁剪（未闭合 ⇒ 裁到文末）。 */
    static String strip(String text) {
        if (text == null || !text.contains(TAG_MARK)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher tags = TAG.matcher(text);
        int pos = 0;
        while (true) {
            int open = text.indexOf(OPEN, pos);
            if (open < 0) {
                out.append(text, pos, text.length());
                break;
            }
            out.append(text, pos, open);
            // 平衡深度扫描：开标记 +1、合标记 -1，归零处即泄漏块终点。
            tags.region(open, text.length());
            int depth = 0;
            int end = text.length();
            while (tags.find()) {
                if (tags.group().charAt(1) == '/') {
                    depth--;
                    if (depth == 0) {
                        end = tags.end();
                        break;
                    }
                } else {
                    depth++;
                }
            }
            tags.reset();
            out.append(OMITTED);
            pos = end;
        }
        String result = out.toString();
        if (result.contains(TAG_MARK)) {
            // 残余游离标记（无配对开/合）兜底剥除。
            result = TAG.matcher(result).replaceAll("");
        }
        return result;
    }
}
