package com.pijava.coding.agent.theme;

/**
 * Oklab 与 OKHSL ↔ sRGB 转换（pi {@code packages/tui/src/oklab.ts} 233 行逐字移植，
 * 批 21/docs/32 T4）。
 *
 * <p>Oklab 与 OKHSL 是 Björn Ottosson 的色彩空间；OKHSL 的饱和度相对于各色相与
 * 亮度下 sRGB 色域的最大值。本类是其参考实现
 * （https://bottosson.github.io/posts/colorpicker/）的移植，
 * Copyright (c) 2021 Björn Ottosson，MIT license：
 *
 * <p>Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in the
 * Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the
 * following conditions: The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software. THE SOFTWARE IS
 * PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
 */
final class Oklab {

    private Oklab() {}

    // 向量＝[x, y, z]，矩阵＝三行向量。
    private static double[] multiply(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
        };
    }

    // ============================================================================
    // OKHSL <-> sRGB
    // ============================================================================

    private static final double[][] LINEAR_SRGB_TO_LMS = {
        {0.4122214694707629, 0.5363325372617349, 0.0514459932675022},
        {0.2119034958178251, 0.6806995506452344, 0.1073969535369405},
        {0.0883024591900564, 0.2817188391361215, 0.6299787016738222},
    };
    private static final double[][] LMS_TO_LAB = {
        {0.210454268309314, 0.793617774702305, -0.0040720430116193},
        {1.9779985324311684, -2.42859224204858, 0.450593709617411},
        {0.0259040424655478, 0.7827717124575296, -0.8086757549230774},
    };
    private static final double[][] LAB_TO_LMS = {
        {1, 0.3963377773761749, 0.2158037573099136},
        {1, -0.1055613458156586, -0.0638541728258133},
        {1, -0.0894841775298119, -1.2914855480194092},
    };
    private static final double[][] LMS_TO_LINEAR_SRGB = {
        {4.0767416360759583, -3.3077115392580629, 0.2309699031821043},
        {-1.2684379732850315, 2.6097573492876882, -0.341319376002657},
        {-0.0041960761386756, -0.7034186179359362, 1.7076146940746117},
    };
    /**
     * Per sRGB channel (red, green, blue): the (a, b) half-plane where that channel
     * clips first, and the polynomial approximating the maximum saturation there.
     */
    private static final double[][][] SATURATION_FIT = {
        {{-1.8817031, -0.80936501}, {1.19086277, 1.76576728, 0.59662641, 0.75515197, 0.56771245}},
        {{1.8144408, -1.19445267}, {0.73956515, -0.45954404, 0.08285427, 0.12541073, -0.14503204}},
        {{0.13110758, 1.81333971}, {1.35733652, -0.00915799, -1.1513021, -0.50559606, 0.00692167}},
    };
    private static final double K1 = 0.206;
    private static final double K2 = 0.03;
    private static final double K3 = (1 + K1) / (1 + K2);

    /** Oklab lightness to OKHSL lightness. */
    static double oklabToOkhslLightness(double x) {
        return 0.5 * (K3 * x - K1 + Math.sqrt((K3 * x - K1) * (K3 * x - K1) + 4 * K2 * K3 * x));
    }

    /** OKHSL lightness to Oklab lightness. */
    private static double okhslToOklabLightness(double x) {
        return (x * x + K1 * x) / (K3 * (x + K2));
    }

    /** sRGB transfer function: linear to encoded channel, both 0-1. */
    private static double linearToSrgb(double value) {
        return value > 0.0031308 ? 1.055 * Math.pow(value, 1 / 2.4) - 0.055 : 12.92 * value;
    }

    /** Inverse sRGB transfer function: encoded to linear channel, both 0-1. */
    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** Oklab [L, a, b] to linear sRGB [r, g, b] (0-1, may leave the gamut). */
    static double[] oklabToLinearSrgb(double[] lab) {
        var lms = multiply(LAB_TO_LMS, lab);
        return multiply(LMS_TO_LINEAR_SRGB,
            new double[] {lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]});
    }

    /** Linear sRGB [r, g, b] (0-1) to Oklab [L, a, b]. */
    private static double[] linearSrgbToOklab(double[] rgb) {
        var lms = multiply(LINEAR_SRGB_TO_LMS, rgb);
        return multiply(LMS_TO_LAB,
            new double[] {Math.cbrt(lms[0]), Math.cbrt(lms[1]), Math.cbrt(lms[2])});
    }

    /** sRGB channels (0-255) to Oklab [L, a, b]. */
    static double[] rgbToOklab(ThemeColor.Rgb rgb) {
        return linearSrgbToOklab(new double[] {
            srgbToLinear(rgb.r() / 255), srgbToLinear(rgb.g() / 255), srgbToLinear(rgb.b() / 255)});
    }

    /**
     * Linear sRGB [r, g, b] to sRGB channels (0-255, rounded), clipping out-of-gamut
     * channels.
     */
    static ThemeColor.Rgb linearSrgbToRgb(double[] linear) {
        return new ThemeColor.Rgb(
            Math.round(Math.min(1, Math.max(0, linearToSrgb(linear[0]))) * 255),
            Math.round(Math.min(1, Math.max(0, linearToSrgb(linear[1]))) * 255),
            Math.round(Math.min(1, Math.max(0, linearToSrgb(linear[2]))) * 255));
    }

    /** Rate of change of each cube-root LMS component along a chroma direction (a, b). */
    private static double[] lmsSlopes(double a, double b) {
        return new double[] {
            LAB_TO_LMS[0][1] * a + LAB_TO_LMS[0][2] * b,
            LAB_TO_LMS[1][1] * a + LAB_TO_LMS[1][2] * b,
            LAB_TO_LMS[2][1] * a + LAB_TO_LMS[2][2] * b,
        };
    }

    /**
     * Largest saturation (C/L) inside sRGB for hue (a, b): polynomial fit plus one
     * Halley step.
     */
    private static double maxSaturation(double a, double b) {
        int channel = -1;
        for (int i = 0; i < SATURATION_FIT.length; i++) {
            if (i == 2 || SATURATION_FIT[i][0][0] * a + SATURATION_FIT[i][0][1] * b > 1) {
                channel = i;
                break;
            }
        }
        var k = SATURATION_FIT[channel][1];
        var weights = LMS_TO_LINEAR_SRGB[channel];
        double saturation = k[0] + k[1] * a + k[2] * b + k[3] * a * a + k[4] * a * b;

        var slopes = lmsSlopes(a, b);
        var base = new double[] {1 + saturation * slopes[0], 1 + saturation * slopes[1],
            1 + saturation * slopes[2]};
        double f = weights[0] * base[0] * base[0] * base[0]
            + weights[1] * base[1] * base[1] * base[1]
            + weights[2] * base[2] * base[2] * base[2];
        double f1 = weights[0] * 3 * slopes[0] * base[0] * base[0]
            + weights[1] * 3 * slopes[1] * base[1] * base[1]
            + weights[2] * 3 * slopes[2] * base[2] * base[2];
        double f2 = weights[0] * 6 * slopes[0] * slopes[0] * base[0]
            + weights[1] * 6 * slopes[1] * slopes[1] * base[1]
            + weights[2] * 6 * slopes[2] * slopes[2] * base[2];
        return saturation - (f * f1) / (f1 * f1 - 0.5 * f * f2);
    }

    /** Oklab lightness and chroma of the most saturated sRGB color of hue (a, b). */
    private static double[] cusp(double a, double b) {
        double saturation = maxSaturation(a, b);
        var lab = oklabToLinearSrgb(new double[] {1, saturation * a, saturation * b});
        double lightness = Math.cbrt(1 / Math.max(lab[0], Math.max(lab[1], lab[2])));
        return new double[] {lightness, lightness * saturation};
    }

    /** Chroma where the constant-lightness line at {@code lightness} leaves the sRGB gamut. */
    private static double maxChroma(double a, double b, double lightness, double[] cusp) {
        double cuspL = cusp[0];
        double cuspC = cusp[1];
        if (lightness <= cuspL) {
            return (cuspC * lightness) / cuspL;
        }
        // Upper half: triangle edge, then one Halley step against each channel reaching 1.
        double t = (cuspC * (lightness - 1)) / (cuspL - 1);
        var slopes = lmsSlopes(a, b);
        double[] lms = {lightness + t * slopes[0], lightness + t * slopes[1], lightness + t * slopes[2]};
        double[] cubes = {lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]};
        double[] first = {3 * slopes[0] * lms[0] * lms[0], 3 * slopes[1] * lms[1] * lms[1],
            3 * slopes[2] * lms[2] * lms[2]};
        double[] second = {6 * slopes[0] * slopes[0] * lms[0], 6 * slopes[1] * slopes[1] * lms[1],
            6 * slopes[2] * slopes[2] * lms[2]};
        double minStep = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double f = LMS_TO_LINEAR_SRGB[i][0] * cubes[0] + LMS_TO_LINEAR_SRGB[i][1] * cubes[1]
                + LMS_TO_LINEAR_SRGB[i][2] * cubes[2] - 1;
            double f1 = LMS_TO_LINEAR_SRGB[i][0] * first[0] + LMS_TO_LINEAR_SRGB[i][1] * first[1]
                + LMS_TO_LINEAR_SRGB[i][2] * first[2];
            double f2 = LMS_TO_LINEAR_SRGB[i][0] * second[0] + LMS_TO_LINEAR_SRGB[i][1] * second[1]
                + LMS_TO_LINEAR_SRGB[i][2] * second[2];
            double u = f1 / (f1 * f1 - 0.5 * f * f2);
            double step = u >= 0 ? -f * u : Double.MAX_VALUE;
            minStep = Math.min(minStep, step);
        }
        return t + minStep;
    }

    /** OKHSL's chroma reference points at lightness L and hue (a, b): [c0, cMid, cMax]. */
    private static double[] chromaStops(double lightness, double a, double b) {
        var peak = cusp(a, b);
        double cMax = maxChroma(a, b, lightness, peak);
        double k = cMax / Math.min(lightness * (peak[1] / peak[0]),
            (1 - lightness) * (peak[1] / (1 - peak[0])));
        double midS = 0.11516993 + 1 / (7.4477897 + 4.1590124 * b + a * (-2.19557347
            + 1.75198401 * b + a * (-2.13704948 - 10.02301043 * b
            + a * (-4.24894561 + 5.38770819 * b + 4.69891013 * a))));
        double midT = 0.11239642 + 1 / (1.6132032 - 0.68124379 * b + a * (0.40370612
            + 0.90148123 * b + a * (-0.27087943 + 0.6122399 * b
            + a * (0.00299215 - 0.45399568 * b - 0.14661872 * a))));
        double cMid = 0.9 * k * Math.sqrt(Math.sqrt(1 / (1 / Math.pow(lightness * midS, 4)
            + 1 / Math.pow((1 - lightness) * midT, 4))));
        double c0 = Math.sqrt(1 / (1 / ((lightness * 0.4) * (lightness * 0.4))
            + 1 / (((1 - lightness) * 0.8) * ((1 - lightness) * 0.8))));
        return new double[] {c0, cMid, cMax};
    }

    /**
     * Convert OKHSL to sRGB channels (0-255, rounded), clipping out-of-gamut channels.
     *
     * @param hue hue in degrees
     * @param saturation saturation, 0-1
     * @param lightness lightness, 0-1
     * @return the sRGB color
     */
    static ThemeColor.Rgb okhslToRgb(double hue, double saturation, double lightness) {
        double l = okhslToOklabLightness(lightness);
        double[] lab = {l, 0, 0};
        if (l > 0 && l < 1 && saturation > 0) {
            double angle = (2 * Math.PI * (((hue % 360) + 360) % 360)) / 360;
            double a = Math.cos(angle);
            double b = Math.sin(angle);
            var stops = chromaStops(l, a, b);
            double c0 = stops[0];
            double cMid = stops[1];
            double cMax = stops[2];
            // Chroma rises from 0 through cMid at s = 0.8 to cMax at s = 1.
            double chroma;
            if (saturation < 0.8) {
                double t = 1.25 * saturation;
                double k1 = 0.8 * c0;
                chroma = (t * k1) / (1 - (1 - k1 / cMid) * t);
            } else {
                double t = 5 * (saturation - 0.8);
                double k1 = (0.2 * cMid * cMid * 1.25 * 1.25) / c0;
                chroma = cMid + (t * k1) / (1 - (1 - k1 / (cMax - cMid)) * t);
            }
            lab = new double[] {l, chroma * a, chroma * b};
        }
        return linearSrgbToRgb(oklabToLinearSrgb(lab));
    }

    /**
     * Convert sRGB channels (0-255) to OKHSL.
     *
     * @return {@code [h, s, l]}：h 色相度数（灰为 0），s/l 0-1
     */
    static double[] rgbToOkhsl(ThemeColor.Rgb rgb) {
        var lab = rgbToOklab(rgb);
        double l = lab[0];
        double labA = lab[1];
        double labB = lab[2];
        double chroma = Math.hypot(labA, labB);
        double lightness = oklabToOkhslLightness(l);
        if (chroma < 1e-9 || lightness <= 0 || lightness >= 1) {
            return new double[] {0, 0, lightness};
        }

        double hue = ((Math.atan2(labB, labA) * 180) / Math.PI + 360) % 360;
        var stops = chromaStops(l, labA / chroma, labB / chroma);
        double c0 = stops[0];
        double cMid = stops[1];
        double cMax = stops[2];
        double saturation;
        if (chroma < cMid) {
            double k1 = 0.8 * c0;
            saturation = 0.8 * (chroma / (k1 + (1 - k1 / cMid) * chroma));
        } else {
            double k1 = (0.2 * cMid * cMid * 1.25 * 1.25) / c0;
            double offset = chroma - cMid;
            saturation = 0.8 + 0.2 * (offset / (k1 + (1 - k1 / (cMax - cMid)) * offset));
        }
        return new double[] {hue, Math.min(1, Math.max(0, saturation)), lightness};
    }
}
