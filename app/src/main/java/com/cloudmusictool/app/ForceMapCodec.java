package com.cloudmusictool.app;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * ABTest 强制分组表 {key:group} 的 JSON 解析唯一来源。
 *
 * 公共部分：JSON 对象解析、空 key/空 group/非字符串 group 剔除、保持出现顺序、
 * 非法 JSON 一律按空表处理；条目过滤策略由调用方以谓词传入——
 * 目标进程 Config 侧走 AbTestPolicy.isAllowed+isValidGroup，
 * 模块 App 实验页（ExperimentFragment）只校验 isValidGroup。
 */
final class ForceMapCodec {

    private ForceMapCodec() {
    }

    /**
     * 解析 {key:group} JSON 对象为保持出现顺序的候选表（未经策略过滤）。
     * raw 为 null/空白、JSON 非法或解析中途异常时返回空表。
     */
    static Map<String, String> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject obj = new JSONObject(raw);
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                // 只看真正的字符串值：optString 会把 JSON null/数字/布尔/对象
                // 转成 "null"/"1"/"true"/"{...}" 等非空串，绕过“空 group 剔除”契约。
                Object value = obj.opt(key);
                if (key != null && !key.isEmpty() && value instanceof String) {
                    String group = (String) value;
                    if (!group.isEmpty()) {
                        out.put(key, group);
                    }
                }
            }
            return out;
        } catch (Throwable ignored) {
            // 损坏的 JSON 按空配置处理
            return new LinkedHashMap<>();
        }
    }

    /** 按调用方策略过滤候选表，保持原有顺序；结果可变（实验页需 remove/put）。 */
    static Map<String, String> applyFilter(Map<String, String> candidates,
                                           BiPredicate<String, String> filter) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            if (filter.test(e.getKey(), e.getValue())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
