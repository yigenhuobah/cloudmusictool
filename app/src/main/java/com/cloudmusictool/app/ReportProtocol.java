package com.cloudmusictool.app;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 诊断回传协议（纯 JVM 逻辑，供 StatusProvider 与单测共用）。
 *
 * 目标进程只回传计数与 skip 原因，不包含用户内容、账号、设备标识或业务数据。
 * key 白名单：reg.<key> / hit.<key> / skip.<key>；value 最长 256 字符；
 * 单次最多 800 条；hit 多进程回传取历史最大值。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = "reg.";
    public static final String PREFIX_HIT = "hit.";
    public static final String PREFIX_SKIP = "skip.";
    public static final int MAX_VALUE_CHARS = 256;
    public static final int MAX_ENTRIES = 800;
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** 主进程名；子进程回传不计入失败功能数，避免未装 hook 的子进程误报。 */
    public static final String MAIN_PROCESS = "com.netease.cloudmusic";

    private ReportProtocol() {
    }

    public static boolean isValidKey(String key) {
        if (key == null || key.length() <= 3) {
            return false;
        }
        if (!key.startsWith(PREFIX_REG) && !key.startsWith(PREFIX_HIT)
                && !key.startsWith(PREFIX_SKIP)) {
            return false;
        }
        String suffix = key.substring(key.indexOf('.') + 1);
        if (suffix.isEmpty() || suffix.length() > 128) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (Character.isWhitespace(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidValue(String value) {
        return value != null && value.length() <= MAX_VALUE_CHARS;
    }

    public static int mergeHit(int previous, int incoming) {
        return Math.max(previous, incoming);
    }

    public static boolean isValidPayload(String json) {
        return json != null && !json.isEmpty() && json.length() <= MAX_PAYLOAD_CHARS;
    }

    /** 主进程回传判定；旧版没有 process 字段时按主进程兼容。 */
    public static boolean isMainProcessReport(JSONObject root) {
        if (root == null) {
            return false;
        }
        String process = root.optString("process", "");
        return process.isEmpty() || MAIN_PROCESS.equals(process);
    }

    public static boolean isMainProcessReport(String json) {
        if (!isValidPayload(json)) {
            return false;
        }
        try {
            return isMainProcessReport(new JSONObject(json));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 已启用且明确失败的功能数。
     *
     * 失败判定：目标进程有回传；该功能 enabled；installed == 0 或 lastFailure 非空。
     * 非主进程回传直接返回 0。
     */
    public static int failedFeatureCount(JSONObject root) {
        if (!isMainProcessReport(root)) {
            return 0;
        }
        JSONObject features = root.optJSONObject("features");
        if (features == null) {
            return 0;
        }
        int count = 0;
        Iterator<String> keys = features.keys();
        while (keys.hasNext()) {
            JSONObject feature = features.optJSONObject(keys.next());
            if (feature == null || !feature.optBoolean("enabled", false)) {
                continue;
            }
            boolean registered = feature.optInt("installed", 0) > 0;
            String skip = feature.optString("lastFailure", "");
            if (!registered || !skip.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
