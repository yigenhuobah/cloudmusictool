package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/**
 * set 型配置值格式的编解码唯一来源。
 *
 * home_blocks_filter 等四个 key 的值格式是模块 App（ConfigStore 写）与
 * 目标进程（Config.loadSet 读）之间的跨进程协议：StringSet 优先、
 * 字符串兜底、空串视为未设置。兜底字符串中简单值（无逗号/无首尾空白/非空）
 * 仍沿用旧版 CSV，便于旧读取侧兼容；无法用 CSV 无损表达的集合才改用
 * json: 前缀的 JSON 数组编码。两侧读写必须经此类，避免协议漂移。
 */
final class PrefsSetCodec {

    /** 新版兜底格式前缀：其后是 JSON 字符串数组，可无损保存逗号/首尾空格/空串。 */
    private static final String JSON_PREFIX = "json:";

    private PrefsSetCodec() {
    }

    /** 底层读取抛异常时的回调；用于上层按 key 记录一次 WARN。 */
    interface ReadErrorHandler {
        void onError(Throwable t);
    }

    /**
     * 读取 set 型配置。
     *
     * @return 命中 StringSet（含显式写入的空集）或非空字符串时返回解析结果
     *         （字符串格式见 {@link #parse(String)}）；键缺失、存的是空串或
     *         读取抛异常时返回 null，由调用方回退默认值。
     */
    static Set<String> read(SharedPreferences sp, String key) {
        return read(sp, key, null);
    }

    /**
     * 与 {@link #read(SharedPreferences, String)} 语义一致，额外把“最终无法读取”
     * 的异常交给 onError：StringSet 抛错但字符串兜底成功不算失败；两种格式都
     * 读不到且至少有一次抛异常时才回调，避免把正常兜底/缺失报成 WARN。
     */
    static Set<String> read(SharedPreferences sp, String key, ReadErrorHandler onError) {
        Throwable failure = null;
        try {
            Set<String> set = sp.getStringSet(key, null);
            if (set != null) {
                return new HashSet<>(set);
            }
        } catch (Throwable t) {
            failure = t;
        }
        try {
            String raw = sp.getString(key, null);
            // 空串视为未设置：回退调用方默认值，避免返回空集吞掉默认。
            if (raw != null && !raw.isEmpty()) {
                return parse(raw);
            }
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            }
        }
        if (failure != null && onError != null) {
            onError.onError(failure);
        }
        return null;
    }

    /**
     * 解析 set 的字符串表示，{@link #toCsv(Set)} 的逆操作。
     *
     * <p>带 {@link #JSON_PREFIX} 前缀且为字符串数组的新格式按原值还原元素（逗号、
     * 首尾空格、空串都保留）；旧版逗号分隔 CSV 保持原有语义（逐项 trim、剔除
     * 空项、去重）。前缀命中但 JSON 损坏或含非字符串元素时回退旧 CSV 解析，
     * 避免把恰好以 json: 开头的旧值当损坏配置丢弃。
     *
     * @return null 表示未设置（null/空串）
     */
    static Set<String> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.startsWith(JSON_PREFIX)) {
            Set<String> fromJson = parseJsonArray(raw.substring(JSON_PREFIX.length()));
            if (fromJson != null) {
                return fromJson;
            }
        }
        Set<String> parsed = new HashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed;
    }

    /** @return null 表示内容不是本类写出的 JSON 字符串数组（由调用方回退旧格式） */
    private static Set<String> parseJsonArray(String body) {
        try {
            JSONArray array = new JSONArray(body);
            Set<String> parsed = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (!(item instanceof String)) {
                    // 含非字符串元素 => 不是本类编码，按旧 CSV 处理，避免误吞旧值。
                    return null;
                }
                parsed.add((String) item);
            }
            return parsed;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * set 序列化为可逆字符串；StringSet 写入抛异常时的兜底格式。
     * 简单值保持旧版 CSV（旧读取侧可直接兼容），仅当 CSV 无法无损表达
     * （空集、空元素、含逗号、首尾空白、以 json: 开头）时改用 JSON 数组编码。
     */
    static String toCsv(Set<String> value) {
        if (value.isEmpty() || needsJsonEncoding(value)) {
            JSONArray array = new JSONArray();
            for (String s : value) {
                if (s != null) {
                    array.put(s);
                }
            }
            return JSON_PREFIX + array.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (String s : value) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean needsJsonEncoding(Set<String> value) {
        for (String s : value) {
            if (s == null || s.isEmpty() || !s.equals(s.trim())
                    || s.indexOf(',') >= 0 || s.startsWith(JSON_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
