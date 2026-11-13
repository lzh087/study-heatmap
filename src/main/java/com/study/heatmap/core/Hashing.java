package com.study.heatmap.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 行内容锚点。
 *
 * 关键设计：读写两侧都以「去掉首尾空白后的行内容哈希」作为主键，而不是行号。
 * 这样 git pull / 插入删除代码之后，热力数据依然能落回原来那几行，
 * 不会出现「换个分支热力图整体错位」的问题。
 *
 * 同一文件里可能有很多重复行（空行、单独的 "}"），所以再加一个 occurrence 序号区分。
 */
public final class Hashing {

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private Hashing() {
    }

    /** 行内容锚点：SHA-1 前 10 位十六进制。 */
    public static String anchor(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        MessageDigest md = DIGEST.get();
        md.reset();
        byte[] d = md.digest(line.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 5; i++) {
            int v = d[i] & 0xFF;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    public static String key(String anchor, int occurrence) {
        return anchor + "#" + occurrence;
    }

    public static boolean isBlank(String line) {
        if (line == null || line.isEmpty()) {
            return true;
        }
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String shortHash(String s) {
        String a = anchor(s);
        return a.substring(0, Math.min(8, a.length()));
    }
}
