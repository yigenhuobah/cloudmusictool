package com.cloudmusictool.app.hooks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 首页 / MMKV 缓存过滤的纯函数实现（无 Android 依赖，便于 JVM 单测）。
 */
final class JsonFilter {

    private JsonFilter() {
    }

    /**
     * blockCodeOrderList 是形如 ["PAGE_RECOMMEND_X", ...] 的 JSON 数组字符串。
     * 只按当前 hideSet 过滤（不跨请求保留状态）；返回过滤后的字符串，
     * removedOut[0] 写入删除数量。hideSet 为空时原样返回；解析失败 fail-open。
     */
    static String filterCodeListString(String json, Set<String> hideSet, int[] removedOut) {
        if (hideSet == null || hideSet.isEmpty() || json == null || json.isEmpty()) {
            return json;
        }
        try {
            JSONArray arr = new JSONArray(json);
            JSONArray out = new JSONArray();
            int removed = 0;
            for (int i = 0; i < arr.length(); i++) {
                String code = arr.optString(i, "");
                if (code != null && hideSet.contains(code)) {
                    removed++;
                    continue;
                }
                out.put(code);
            }
            if (removedOut != null) {
                removedOut[0] = removed;
            }
            return removed > 0 ? out.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    /**
     * 递归过滤 JSON 树：删除 blockCode 命中 hideSet 的对象。
     * 原地修改 JSONObject/JSONArray；removed[0] 累计删除数。
     * 零删除子树不产生任何变更，按原引用/原串返回，避免无产出的拷贝与重序列化。
     */
    static Object filterValue(Object node, Set<String> hideSet, int[] removed, int depth) {
        if (depth > 6) {
            return node;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            for (String key : keys) {
                Object val = obj.opt(key);
                if (val instanceof JSONObject || val instanceof JSONArray) {
                    try {
                        Object filtered = filterValue(val, hideSet, removed, depth + 1);
                        if (filtered != val) {
                            obj.put(key, filtered);
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (val instanceof String) {
                    String s = (String) val;
                    String t = s.trim();
                    if (t.startsWith("{") || t.startsWith("[")) {
                        int before = removed[0];
                        Object nested = filterNestedString(s, hideSet, removed, depth + 1);
                        if (removed[0] > before) {
                            try {
                                obj.put(key, nested);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            return obj;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            int removedBefore = removed[0];
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    String code = ((JSONObject) item).optString("blockCode", null);
                    if (code != null && hideSet.contains(code)) {
                        removed[0]++;
                        continue;
                    }
                    out.put(filterValue(item, hideSet, removed, depth + 1));
                } else if (item instanceof JSONArray) {
                    out.put(filterValue(item, hideSet, removed, depth + 1));
                } else if (item instanceof String) {
                    String s = (String) item;
                    String t = s.trim();
                    int before = removed[0];
                    if (t.startsWith("{") || t.startsWith("[")) {
                        Object nested = filterNestedString(s, hideSet, removed, depth + 1);
                        out.put(removed[0] > before ? nested : item);
                    } else {
                        out.put(item);
                    }
                } else {
                    out.put(item);
                }
            }
            // 零删除：子树无任何变更，返回原数组引用，避免拷贝被上层重序列化
            return removed[0] > removedBefore ? out : arr;
        }
        return node;
    }

    /**
     * 嵌套 JSON 字符串过滤。hideSet 任一值都未出现的串解析后不可能有删除，
     * 先行快门原样返回；确有删除才重序列化，零删除返回原串。
     * 包私有便于 JVM 单测直接断言零命中路径不重序列化。
     */
    static Object filterNestedString(String s, Set<String> hideSet, int[] removed, int depth) {
        if (!mayMatchAny(s, hideSet)) {
            return s;
        }
        try {
            int before = removed[0];
            String t = s.trim();
            Object root = t.startsWith("{") ? new JSONObject(t) : new JSONArray(t);
            Object filtered = filterValue(root, hideSet, removed, depth);
            return removed[0] > before ? filtered.toString() : s;
        } catch (Throwable ignored) {
            return s;
        }
    }

    /**
     * 快门：s 是否可能含 hideSet 任一值。hideSet 全部由 JSON 序列化不改写字面的值组成
     * 且都未在 s 中出现时返回 false（解析不可能命中，可跳过 parse）；否则保守返回 true，
     * 保证删除语义与全量解析完全一致。
     */
    private static boolean mayMatchAny(String s, Set<String> hideSet) {
        boolean maybe = false;
        for (String v : hideSet) {
            if (isScanSafe(v)) {
                if (s.contains(v)) {
                    return true;
                }
            } else {
                maybe = true;
            }
        }
        return maybe;
    }

    /**
     * v 是否每个字符都不会被 JSON 序列化改写：排除控制符与非 ASCII，
     * 以及引号、反斜杠和部分序列化器会转义的 / &lt; &gt; &amp; ' =。
     */
    private static boolean isScanSafe(String v) {
        if (v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c >= 0x7F || "\"\\/<>&'=".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }
}
