package com.study.heatmap.core;

import java.util.Locale;

/**
 * 时间 -> 颜色。
 *
 * 用对数缩放：如果把「读了 8 分钟的行」和「扫过 3 秒的行」线性映射，
 * 前者会把色阶吃满，其余行全挤在近乎透明的一档，整张图看起来只有几个黑块。
 */
public final class Heat {

    private static final int R = 226;
    private static final int G = 75;
    private static final int B = 74;

    private Heat() {
    }

    /** 0..1 强度，对数压缩。 */
    public static double intensity(double seconds, double maxSeconds) {
        if (seconds <= 0 || maxSeconds <= 0) {
            return 0;
        }
        double t = Math.log1p(seconds) / Math.log1p(maxSeconds);
        return Math.max(0, Math.min(1, t));
    }

    /** 行背景色：留白给未学习的行由 CSS 处理。 */
    public static String lineBackground(double seconds, double maxSeconds) {
        double t = intensity(seconds, maxSeconds);
        if (t <= 0) {
            return "";
        }
        // gamma 把中低段压下去，否则「扫过一眼」和「真读懂了」看起来一样红
        double alpha = 0.04 + 0.38 * Math.pow(t, 1.7);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", R, G, B, alpha);
    }

    /** 左侧竖条：颜色更实，用来强化梯度。 */
    public static String gutterColor(double seconds, double maxSeconds) {
        double t = intensity(seconds, maxSeconds);
        if (t <= 0) {
            return "transparent";
        }
        double alpha = 0.12 + 0.72 * Math.pow(t, 1.4);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", R, G, B, alpha);
    }

    /** 人类可读时长：38s / 12m 34s / 2h 08m */
    public static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        s = s % 60;
        if (m < 60) {
            return s == 0 ? m + "m" : m + "m " + s + "s";
        }
        long h = m / 60;
        m = m % 60;
        return String.format(Locale.ROOT, "%dh %02dm", h, m);
    }

    /** 状态栏用：03:24 */
    public static String clock(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        if (s < 3600) {
            return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
