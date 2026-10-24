package com.cloudmusictool.app;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * set 型配置值格式的编解码唯一来源。
 *
 * home_blocks_filter 等四个 key 的值格式是模块 App（ConfigStore 写）与
 * 目标进程（Config.loadSet 读）之间的跨进程协议：StringSet 优先、
 * CSV 字符串兜底、空串视为未设置。两侧读写必须经此类，避免协议漂移。
 */
final class PrefsSetCodec {

    private PrefsSetCodec() {
    }

    /**
     * 读取 set 型配置。
     *
     * @return 命中 StringSet（含显式写入的空集）或非空 CSV 串时返回解析结果
     *         （CSV 逐项 trim、剔除空项、按 Set 去重）；键缺失、存的是空串或
     *         读取抛异常时返回 null，由调用方回退默认值。
     */
    static Set<String> read(SharedPreferences sp, String key) {
        try {
            Set<String> set = sp.getStringSet(key, null);
            if (set != null) {
                return new HashSet<>(set);
            }
        } catch (Throwable ignored) {
        }
        try {
            String raw = sp.getString(key, null);
            // 空串视为未设置：回退调用方默认值，避免返回空集吞掉默认。
            if (raw != null && !raw.isEmpty()) {
                Set<String> parsed = new HashSet<>();
                for (String item : raw.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed);
                    }
                }
                return parsed;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** set 序列化为 CSV；StringSet 写入抛异常时的兜底格式。 */
    static String toCsv(Set<String> value) {
        StringBuilder sb = new StringBuilder();
        for (String s : value) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
