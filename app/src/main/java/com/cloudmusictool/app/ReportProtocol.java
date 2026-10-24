package com.cloudmusictool.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 诊断回传协议（纯 JVM 逻辑，供 StatusProvider 与单测共用）。
 *
 * 这里是边界强制的真实契约：StatusProvider.handle 在落盘前按通道调用
 * validateMountsPayload / validateExperimentsPayload，结构之外的 payload
 * 整体拒绝（不截断、不部分接受）。
 *
 * - mounts 通道（HookState.buildJson 产出）：根键白名单
 *   {updatedAt, process, enabledFeatures, installed, expected, failed,
 *   noHitFeatures, degradedFeatures, unavailableFeatures, truncated, features,
 *   abtest}（允许集，缺省键合法；abtest 为新版本将新增的键）；
 *   process 为空（旧版兼容）或主进程名；features 的键必须是 Feature 枚举名，
 *   每个 feature 对象的键 ⊆ {@link #FEATURE_KEYS}。
 * - experiments 通道（ExperimentState.buildJson 产出）：根键白名单
 *   {updatedAt, process, items}；items 至多 {@link #MAX_ENTRIES} 条，每条为对象。
 * - 公共上限：任意字符串值 ≤ {@link #MAX_VALUE_CHARS} 字符；
 *   payload 总量 ≤ {@link #MAX_PAYLOAD_CHARS}（isValidPayload）。
 */
public final class ReportProtocol {

    public static final int MAX_VALUE_CHARS = 256;
    public static final int MAX_ENTRIES = 800;
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** 主进程名；子进程回传不计入失败功能数，避免未装 hook 的子进程误报。 */
    public static final String MAIN_PROCESS = "com.netease.cloudmusic";

    /** mounts 通道根键白名单（与 HookState.buildJson 的字段面一致）。 */
    private static final Set<String> MOUNTS_ROOT_KEYS = new HashSet<>(Arrays.asList(
            "updatedAt", "process", "enabledFeatures", "installed", "expected",
            "failed", "noHitFeatures", "degradedFeatures", "unavailableFeatures",
            "truncated", "features", "abtest"));

    /** 单个 feature 对象的字段白名单。 */
    private static final Set<String> FEATURE_KEYS = new HashSet<>(Arrays.asList(
            "id", "enabled", "expected", "installed", "failed", "criticalFailed",
            "hit", "hitCount", "degraded", "unavailable", "lastFailure"));

    /** experiments 通道根键白名单（与 ExperimentState.buildJson 的字段面一致）。 */
    private static final Set<String> EXPERIMENTS_ROOT_KEYS = new HashSet<>(Arrays.asList(
            "updatedAt", "process", "items"));

    /** Feature 枚举名集合；features 的键必须是其中之一。 */
    private static final Set<String> FEATURE_NAMES = featureNames();

    private ReportProtocol() {
    }

    private static Set<String> featureNames() {
        Set<String> names = new HashSet<>();
        for (Feature f : Feature.values()) {
            names.add(f.name());
        }
        return names;
    }

    /**
     * mounts 通道结构校验。
     *
     * @return null 表示结构合法；非 null 为拒绝原因（可直接进日志）。
     */
    public static String validateMountsPayload(JSONObject root) {
        if (root == null) {
            return "payload is not an object";
        }
        String reason = unknownRootKey(root, MOUNTS_ROOT_KEYS);
        if (reason != null) {
            return reason;
        }
        // 缺 process 字段按主进程兼容（与 isMainProcessReport 一致）。
        if (!isMainProcessReport(root)) {
            return "unexpected process: " + root.optString("process", "");
        }
        Object features = root.opt("features");
        if (!(features == null || features instanceof JSONObject)) {
            return "features is not an object";
        }
        if (features != null) {
            Iterator<String> names = ((JSONObject) features).keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!FEATURE_NAMES.contains(name)) {
                    return "unknown feature key: " + name;
                }
                Object entry = ((JSONObject) features).opt(name);
                if (!(entry instanceof JSONObject)) {
                    return "feature entry is not an object: " + name;
                }
                Iterator<String> fields = ((JSONObject) entry).keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!FEATURE_KEYS.contains(field)) {
                        return "unknown feature field: " + name + "." + field;
                    }
                }
            }
        }
        return checkStringValues(root);
    }

    /**
     * experiments 通道结构校验。
     *
     * @return null 表示结构合法；非 null 为拒绝原因（可直接进日志）。
     */
    public static String validateExperimentsPayload(JSONObject root) {
        if (root == null) {
            return "payload is not an object";
        }
        String reason = unknownRootKey(root, EXPERIMENTS_ROOT_KEYS);
        if (reason != null) {
            return reason;
        }
        Object items = root.opt("items");
        if (!(items == null || items instanceof JSONArray)) {
            return "items is not an array";
        }
        if (items != null) {
            JSONArray array = (JSONArray) items;
            if (array.length() > MAX_ENTRIES) {
                return "too many items: " + array.length();
            }
            for (int i = 0; i < array.length(); i++) {
                if (!(array.opt(i) instanceof JSONObject)) {
                    return "item is not an object: index " + i;
                }
            }
        }
        return checkStringValues(root);
    }

    private static String unknownRootKey(JSONObject root, Set<String> allowed) {
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                return "unknown root key: " + key;
            }
        }
        return null;
    }

    /** 任意字符串值 ≤ MAX_VALUE_CHARS；迭代遍历，深嵌套 payload 不会撑爆调用栈。 */
    private static String checkStringValues(JSONObject root) {
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Object node = pending.pop();
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    pending.push(obj.opt(keys.next()));
                }
            } else if (node instanceof JSONArray) {
                JSONArray array = (JSONArray) node;
                for (int i = 0; i < array.length(); i++) {
                    pending.push(array.opt(i));
                }
            } else if (node instanceof String && ((String) node).length() > MAX_VALUE_CHARS) {
                return "string value exceeds " + MAX_VALUE_CHARS + " chars";
            }
        }
        return null;
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
