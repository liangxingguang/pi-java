package com.pijava.tui.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OSC 7501 程序状态协议（docs/26 §4.3，K39）：pi {@code program-status.ts}
 * 的 java 对位。程序告知终端自身 idle/working/blocked/done/error，支持该
 * 协议的终端（会话/任务列表）据此展示状态。
 *
 * <p>序列形：{@code ESC ] 7501 ; state=…[:app=…][:kind=…][:msg=<base64>] ESC \}。
 * app 须匹配 {@code [A-Za-z0-9_.+-]{1,32}}；消息控制字符替换为空格、trim，
 * UTF-8 截断 2048 字节后 base64。</p>
 */
public final class ProgramStatusProtocol {

    /** 程序名（pi {@code APP_NAME}）。 */
    public static final String APP_NAME = "pi";

    /** 特性探测查询（支持终端回同一 body）。 */
    public static final String QUERY = "\u001b]7501;?\u001b\\";

    private static final Pattern APP_PATTERN = Pattern.compile("[A-Za-z0-9_.+-]{1,32}");
    private static final Pattern REPLY_PATTERN =
        Pattern.compile("^\u001b\\]7501;\\?[^\u0007\u001b]*(?:\u0007|\u001b\\\\)$");
    private static final int MAX_MESSAGE_BYTES = 2048;

    /** 状态机。 */
    public enum State {
        /** 无运行。 */
        IDLE,
        /** 运行中。 */
        WORKING,
        /** 等待用户。 */
        BLOCKED,
        /** 完成。 */
        DONE,
        /** 失败。 */
        ERROR,
        /** 移除状态。 */
        CLEAR
    }

    /** blocked 原因（仅 {@link State#BLOCKED} 携带）。 */
    public enum Kind {
        /** 权限确认。 */
        PERMISSION("permission"),
        /** 一般提问。 */
        QUESTION("question"),
        /** 登录鉴权。 */
        AUTH("auth");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** 线上小写名。 */
        public String wire() {
            return wire;
        }

        /** 从线上小写名解析；未知返回 {@code null}。 */
        public static Kind fromWire(String value) {
            if (value == null) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.wire.equals(value.toLowerCase(Locale.ROOT))) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** 一条状态记录（未给出的字段为 {@code null}）。 */
    public record Status(State state, String app, Kind kind, String message) {

        /** 仅状态（无 app/kind/message）。 */
        public static Status of(State state) {
            return new Status(state, null, null, null);
        }
    }

    private ProgramStatusProtocol() {}

    /**
     * 编码一条状态报告。
     *
     * @param status 状态
     * @return OSC 7501 序列
     */
    public static String format(Status status) {
        List<String> pairs = new ArrayList<>();
        pairs.add("state=" + status.state().name().toLowerCase(Locale.ROOT));
        if (status.app() != null && APP_PATTERN.matcher(status.app()).matches()) {
            pairs.add("app=" + status.app());
        }
        if (status.state() == State.BLOCKED && status.kind() != null) {
            pairs.add("kind=" + status.kind().wire());
        }
        String message = truncateUtf8(replaceControlCharacters(
            status.message() == null ? "" : status.message()).trim(), MAX_MESSAGE_BYTES);
        if (!message.isEmpty()) {
            pairs.add("msg=" + Base64.getEncoder()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8)));
        }
        return "\u001b]7501;" + String.join(":", pairs) + "\u001b\\";
    }

    /** 是否为对 {@link #QUERY} 的回复。 */
    public static boolean isReply(String sequence) {
        return sequence != null && REPLY_PATTERN.matcher(sequence).matches();
    }

    /**
     * 读取 {@code PI_PROGRAM_STATUS}：{@code 1}⇒启用、{@code 0}⇒停用、
     * 缺省⇒启用（乐观输出；未知 OSC 由终端忽略，见 docs/26 §5 N1）。
     *
     * @param env 环境变量
     * @return 是否输出程序状态
     */
    public static boolean enabled(Map<String, String> env) {
        String value = env.get("PI_PROGRAM_STATUS");
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        return true;
    }

    private static String replaceControlCharacters(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || cp == 0x7f) {
                result.append(' ');
            } else {
                result.appendCodePoint(cp);
            }
        });
        return result.toString();
    }

    private static String truncateUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int used = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) {
                break;
            }
            used += size;
            end = i + Character.charCount(cp);
            i = end;
        }
        return text.substring(0, end);
    }
}
