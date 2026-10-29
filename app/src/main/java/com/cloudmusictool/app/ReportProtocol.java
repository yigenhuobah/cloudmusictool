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
 *   {updatedAt, session, process, enabledFeatures, installed, expected, failed,
 *   noHitFeatures, degradedFeatures, unavailableFeatures, truncated, features,
 *   abtest}（允许集，缺省键合法；session 为本版本新增的进程会话标识）；
 *   process 为空（旧版兼容）或主进程名；features 的键必须是 Feature 枚举名，
 *   每个 feature 对象的键 ⊆ {@link #FEATURE_KEYS}。
 * - experiments 通道（ExperimentState.buildJson 产出）：根键白名单
 *   {updatedAt, process, items}；items 至多 {@link #MAX_ENTRIES} 条，每条为对象。
 * - 公共上限：任意字符串值 ≤ {@link #MAX_VALUE_CHARS} 字符；
 *   payload 总量 ≤ {@link #MAX_PAYLOAD_CHARS}（isValidPayload）。
 *
 * 会话与新鲜度（读取侧，与 QB 对齐）：
 * - session = 目标进程启动毫秒字符串，用来丢弃迟到的旧会话整包回写；
 * - report_time 复用根级 updatedAt（epoch ms）；超过 {@link #DEFAULT_TTL_MS}
 *   或 updatedAt <= 0 视为过期，失败计数返回 0（未知）。
 */
public final class ReportProtocol {

    public static final int MAX_VALUE_CHARS = 256;
    public static final int MAX_ENTRIES = 800;
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** 超过 10 分钟没有新回传就视为过期，失败计数按“未知”处理（0）。 */
    public static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;

    /** 主进程名；子进程回传不计入失败功能数，避免未装 hook 的子进程误报。 */
    public static final String MAIN_PROCESS = "com.netease.cloudmusic";

    /** mounts 通道根键白名单（与 HookState.buildJson 的字段面一致）。 */
    private static final Set<String> MOUNTS_ROOT_KEYS = new HashSet<>(Arrays.asList(
            "updatedAt", "session", "process", "enabledFeatures", "installed", "expected",
            "failed", "noHitFeatures", "degradedFeatures", "unavailableFeatures",
            "truncated", "features", "abtest"));

    /** 单个 feature 对象的字段白名单。 */
    private static final Set<String> FEATURE_KEYS = new HashSet<>(Arrays.asList(
            "id", "enabled", "expected", "installed", "failed", "criticalFailed",
            "entryCount", "hit", "hitCount", "degraded", "unavailable", "lastFailure"));

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
     * 回传数据是否仍新鲜。updatedAt <= 0 表示从未回传或旧版本没有时间戳；
     * ttlMs <= 0 视为没有可用 TTL（过期）；now < updatedAt（设备时钟回拨）
     * 按新鲜处理，避免误伤。
     */
    public static boolean isFresh(long updatedAt, long now, long ttlMs) {
        if (updatedAt <= 0L || ttlMs <= 0L) {
            return false;
        }
        return now - updatedAt <= ttlMs;
    }

    /**
     * 迟到的旧会话写入：已存会话存在，而本次没有会话标识（更早版本的回传）
     * 或会话启动时间更早，整包必须丢弃，不能覆盖新会话的数据。
     */
    public static boolean isStaleSession(String storedSession, String incomingSession) {
        long stored = parseLong(storedSession);
        if (stored <= 0L) {
            return false;
        }
        long incoming = parseLong(incomingSession);
        return incoming <= 0L || incoming < stored;
    }

    /** 新会话：本次会话启动时间更晚（或首次带 session 回传），应整段覆盖旧会话数据。 */
    public static boolean isNewSession(String storedSession, String incomingSession) {
        long incoming = parseLong(incomingSession);
        if (incoming <= 0L) {
            return false;
        }
        long stored = parseLong(storedSession);
        return stored <= 0L || incoming > stored;
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 已启用且明确失败的功能数（供异常横幅使用）。
     *
     * 失败判定同时满足：目标进程（主进程）有回传；数据在 TTL 内；该功能 enabled；
     * 且 installed <= 0 或 criticalFailed > 0。普通 lastFailure 只作诊断，
     * 不再计入横幅，避免单个可选挂载点改名就把功能判死。
     *
     * 非主进程、缺失数据、过期/未知时间戳一律返回 0；CMT 的 criticalFailed 由
     * Profile 的 isCriticalMount 在目标进程侧计算，这里直接消费。
     */
    public static int failedFeatureCount(JSONObject root) {
        return failedFeatureCount(root, System.currentTimeMillis(), DEFAULT_TTL_MS);
    }

    /** 确定性入口：now/ttlMs 由调用方注入（单测用假时钟）。 */
    public static int failedFeatureCount(JSONObject root, long now, long ttlMs) {
        if (!isMainProcessReport(root)) {
            return 0;
        }
        if (!isFresh(root.optLong("updatedAt", 0L), now, ttlMs)) {
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
            if (feature.optInt("installed", 0) <= 0
                    || feature.optInt("criticalFailed", 0) > 0) {
                count++;
            }
        }
        return count;
    }
}
