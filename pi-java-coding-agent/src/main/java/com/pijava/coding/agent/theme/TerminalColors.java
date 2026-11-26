package com.pijava.coding.agent.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 终端上报的当前主题色（pi {@code packages/tui/src/terminal-colors.ts} 的
 * {@code TerminalColors} 对位，批 21/docs/32 T4）。
 *
 * <p><b>裁决③（窄差 D123）</b>：TamboUI 后端没有 OSC 10/11/4 查询面，java 侧
 * 该输入恒为空实例（{@link #EMPTY}）；{@link Themes#setTerminalColors} 是留好的
 * 注入缝，后端具备能力后可用 {@link #parseOscReply(String)} 解析回复再注入。</p>
 *
 * @param foreground 默认前景（pi OSC 10），可空
 * @param background 默认背景（pi OSC 11），可空
 * @param palette ANSI 0-15 色（pi OSC 4；仅当 16 色全上报才非空），可空
 */
public record TerminalColors(ThemeColor.Rgb foreground, ThemeColor.Rgb background,
                             List<ThemeColor.Rgb> palette) {

    private static final Pattern OSC_REPLY = Pattern.compile(
        "^\u001B\\](?:(1[01])|4;(\\d{1,3}));([^\u0007\u001B]*)(?:\u0007|\u001B\\\\)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX6 = Pattern.compile("^[0-9a-f]{6}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX12 = Pattern.compile("^[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_CHANNEL = Pattern.compile("^[0-9a-f]+$", Pattern.CASE_INSENSITIVE);

    /** 终端什么都没上报。 */
    public static final TerminalColors EMPTY = new TerminalColors(null, null, null);

    /** 紧凑构造：palette 防御性拷贝。 */
    public TerminalColors {
        palette = palette == null ? null : List.copyOf(palette);
    }

    /**
     * 解析单条 OSC 10/11/4 回复；非法或不认识的回复返回 {@link #EMPTY}。
     *
     * <p>颜色值支持 {@code #rgb}／{@code #rrggbbaa}（pi 用 hex channel 缩放）
     * 与 {@code rgb:r/g/b}（同 {@code rgba:r/g/b}），通道为 16 进制。
     * OSC 4 仅在 palette index 0-15 时记录，且只有 16 色全上报才返回非空 palette。</p>
     */
    public static TerminalColors parseOscReply(String reply) {
        if (reply == null) {
            return EMPTY;
        }
        var matcher = OSC_REPLY.matcher(reply);
        if (!matcher.matches()) {
            return EMPTY;
        }
        var rgb = parseOscColorValue(matcher.group(3));
        if (rgb == null) {
            return EMPTY;
        }
        String fgBg = matcher.group(1);
        if (fgBg != null) {
            return "10".equals(fgBg) ? new TerminalColors(rgb, null, null)
                : new TerminalColors(null, rgb, null);
        }
        int index;
        try {
            index = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return EMPTY;
        }
        if (index < 0 || index > 15) {
            return EMPTY;
        }
        var palette = new ArrayList<ThemeColor.Rgb>(16);
        for (int i = 0; i < 16; i++) {
            palette.add(null);
        }
        palette.set(index, rgb);
        return new TerminalColors(null, null, palette);
    }

    private static ThemeColor.Rgb parseOscColorValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        var value = rawValue.trim();
        if (value.startsWith("#")) {
            var hex = value.substring(1);
            if (HEX6.matcher(hex).matches()) {
                return rgb(hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6));
            }
            if (HEX12.matcher(hex).matches()) {
                return rgb(hex.substring(0, 4), hex.substring(4, 8), hex.substring(8, 12));
            }
            return null;
        }
        var rgbValue = value.replaceFirst("^rgba?:", "");
        var channels = rgbValue.split("/", -1);
        if (channels.length != 3) {
            return null;
        }
        return rgb(channels[0], channels[1], channels[2]);
    }

    private static ThemeColor.Rgb rgb(String r, String g, String b) {
        var red = parseHexChannel(r);
        var green = parseHexChannel(g);
        var blue = parseHexChannel(b);
        return red < 0 || green < 0 || blue < 0 ? null
            : new ThemeColor.Rgb(red, green, blue);
    }

    private static int parseHexChannel(String channel) {
        if (!HEX_CHANNEL.matcher(channel).matches()) {
            return -1;
        }
        long max = 1L << (4 * channel.length());
        if (max <= 0) {
            return -1;
        }
        long parsed = Long.parseLong(channel, 16);
        return (int) Math.round((parsed / (double) (max - 1)) * 255.0);
    }
}
