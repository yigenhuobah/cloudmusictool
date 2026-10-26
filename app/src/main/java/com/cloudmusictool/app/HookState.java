package com.cloudmusictool.app;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目标进程内的 Hook 健康度状态。
 *
 * 记录每个 Feature 的 expected / installed / failed / entry / hit 与最近失败原因；
 * 通过模块 App 的 StatusProvider 回写（remote prefs 在宿主进程只读，不能反向写）。
 *
 * 写入策略：
 * - 普通状态变化按「领先 + 尾沿」节流：窗口首个变化立即发送（保持安装期响应性），
 *   窗口内后续变化在窗口关闭时合并为一次尾沿发送（携带最新状态），避免最后一次
 *   变化落在节流窗口内且之后无事件而永不上报；
 * - 安装结束后由 Hooks.installAll 调用一次 flush() 发送最终快照；
 * - 发送（节流调度、单线程工作线程、targetContext 解析、authority 拼接、
 *   warning-once 日志）统一委托共享回传管线 {@link TargetProcessPublisher}，
 *   与 ExperimentState 共用；节流决策见其 PublishThrottle。
 */
public final class HookState {

    private static final String TAG = "CloudMusicTool";
    private static final int MAX_JSON_CHARS = 64 * 1024;
    private static final int MAX_FAILURE_CHARS = 160;

    private static final Map<Feature, State> sStates = new ConcurrentHashMap<>();

    // ABTEST 不是 Feature（AbTestHooks 本地定位/安装），单独一组静态计数，
    // 经同一 publish 节奏回传，诊断页在根级 "abtest" 概要中展示。
    private static final AtomicInteger sAbTestInstalled = new AtomicInteger();
    private static final AtomicInteger sAbTestFailed = new AtomicInteger();
    private static final Set<String> sAbTestFailedKeys = ConcurrentHashMap.newKeySet();
    private static volatile String sAbTestLastFailure;

    // 节流器包私有可整体替换：单测注入假时钟；执行器与发送管线在 TargetProcessPublisher。
    static volatile TargetProcessPublisher.PublishThrottle sThrottle =
            new TargetProcessPublisher.PublishThrottle(System::currentTimeMillis);

    private static volatile String sModulePackage;
    private static volatile String sProcessName;

    /** mounts 通道描述：内容与告警文案固定，调度与发送由共享管线执行。 */
    private static final TargetProcessPublisher.Channel sChannel = new TargetProcessPublisher.Channel() {
        @Override
        public String modulePackage() {
            return sModulePackage;
        }

        @Override
        public String type() {
            // 与既有线上格式一致不写 type 键：StatusProvider 缺省按 mounts 处理。
            return null;
        }

        @Override
        public String buildJson() {
            return HookState.buildJson();
        }

        @Override
        public String contextUnavailableWarning() {
            return "hook status not published: target Context unavailable";
        }

        @Override
        public String publishFailureWarning(Throwable t) {
            return "hook status publish failed: " + t.getMessage();
        }
    };

    private HookState() {
    }

    private static final class State {
        volatile boolean enabled;
        final AtomicInteger expected = new AtomicInteger();
        final AtomicInteger installed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        /** 失败的挂载点中，Profile 标记为 critical 的数量（按 label 去重）。 */
        final AtomicInteger criticalFailed = new AtomicInteger();
        /** 入口调用次数：hook 链路进入次数，与 hit（过滤/改写实际生效）独立。 */
        final AtomicInteger entryCount = new AtomicInteger();
        final AtomicInteger hitCount = new AtomicInteger();
        final Set<String> failedKeys = ConcurrentHashMap.newKeySet();
        final Set<String> criticalFailedKeys = ConcurrentHashMap.newKeySet();
        volatile boolean hit;
        /** critical 挂载点失败 -> 功能降级。 */
        volatile boolean degraded;
        /** 一个挂载点都没装上 -> 功能不可用。 */
        volatile boolean unavailable;
        volatile String lastFailure;
        volatile long lastFailureAt;

        synchronized void attempted() {
            int total = installed.get() + failed.get();
            if (total > expected.get()) {
                expected.set(total);
            }
        }
    }

    private static State state(Feature f) {
        State s = sStates.get(f);
        if (s == null) {
            s = new State();
            State prev = sStates.putIfAbsent(f, s);
            if (prev != null) {
                s = prev;
            }
        }
        return s;
    }

    /** 由 CloudMusicToolModule 在 onModuleLoaded 调用。 */
    public static void init(String modulePackage) {
        init(modulePackage, null);
    }

    /** 记录回传进程名，供模块 App 只统计主进程失败功能数。 */
    public static void init(String modulePackage, String processName) {
        sModulePackage = modulePackage;
        sProcessName = processName;
    }

    /** 模块 App 包名；目标进程用它拼 StatusProvider authority。 */
    public static String modulePackage() {
        return sModulePackage;
    }

    public static void enable(Feature f) {
        if (f == null) {
            return;
        }
        state(f).enabled = true;
        Log.i(TAG, f.id + " enabled");
        publish();
    }

    public static void installed(Feature f, String label) {
        if (f == null) {
            return;
        }
        State s = state(f);
        s.installed.incrementAndGet();
        // 启动阶段可能已 finish 过（installed=0 -> unavailable=true）；运行时
        // ClassLoader 安装成功后必须立即清除，不能等下一次 finish。
        if (s.installed.get() > 0) {
            s.unavailable = false;
        }
        s.attempted();
        publish();
    }

    /**
     * 安装结束后结算健康状态。
     * - unavailable：一个挂载点都没装上；
     * - degraded：Profile 中标记为 critical 的挂载点有失败。
     * expected 始终由 attempted() 维护（installed + failed），保证诊断卡比例自洽。
     */
    public static void finish(Feature f) {
        if (f == null) {
            return;
        }
        State s = state(f);
        s.unavailable = s.installed.get() == 0;
        s.degraded = s.criticalFailed.get() > 0;
        publish();
    }

    /**
     * 同一 Feature 的同一 mount label 只记一次失败，避免 findClass/findMethod 两步查找重复计数，
     * 并标记该挂载点是否为 Profile 中的 critical。
     * critical 标记单独去重：即使 failed 已计过，也只在首次标记 critical 时累加。
     */
    public static void markNotFound(Feature f, String label, boolean critical) {
        if (f == null) {
            return;
        }
        State s = state(f);
        if (label != null && !s.failedKeys.add(label)) {
            if (critical && s.criticalFailedKeys.add(label)) {
                s.criticalFailed.incrementAndGet();
                publish();
            }
            return;
        }
        s.failed.incrementAndGet();
        if (critical && (label == null || s.criticalFailedKeys.add(label))) {
            s.criticalFailed.incrementAndGet();
        }
        s.attempted();
        publish();
    }

    public static void note(Feature f, String text) {
        if (f == null) {
            return;
        }
        State s = state(f);
        if (text != null) {
            s.lastFailure = truncate(text, MAX_FAILURE_CHARS);
            s.lastFailureAt = System.currentTimeMillis();
            Log.i(TAG, f.id + " " + text);
        }
        publish();
    }

    // ------------------------------------------------------------------
    // ABTest 观测（ABTEST 不是 Feature，不进 features 结构）
    // ------------------------------------------------------------------

    /** ABTest 挂载点安装成功；label 仅对齐 Feature 级 API 形态，不参与计数。 */
    public static void abTestInstalled(String label) {
        if (label == null) {
            return;
        }
        sAbTestInstalled.incrementAndGet();
        publish();
    }

    /** ABTest 挂载点定位/安装失败；同一 label 只记一次，避免多步查找重复计数。 */
    public static void abTestNotFound(String label) {
        if (label == null || !sAbTestFailedKeys.add(label)) {
            return;
        }
        sAbTestFailed.incrementAndGet();
        publish();
    }

    /** 记录 ABTest 最近失败原因（与 Feature 级 note 同风格，超长截断）。 */
    public static void noteAbTest(String text) {
        if (text == null) {
            return;
        }
        sAbTestLastFailure = truncate(text, MAX_FAILURE_CHARS);
        publish();
    }

    /**
     * 记录 Feature 入口调用次数。与 {@link #hit(Feature)} 区分：
     * entry 只表示 hook 入口被调用，hit 表示过滤/改写真正生效。
     */
    public static void entry(Feature f) {
        if (f == null) {
            return;
        }
        state(f).entryCount.incrementAndGet();
        publish();
    }

    public static void hit(Feature f) {
        if (f == null) {
            return;
        }
        State s = state(f);
        s.hit = true;
        s.hitCount.incrementAndGet();
        publish();
    }

    /** 安装结束后发送最终快照（不受 2 秒节流限制）。 */
    public static void flush() {
        if (sModulePackage == null || sModulePackage.isEmpty()) {
            return;
        }
        TargetProcessPublisher.flush(sChannel);
    }

    /**
     * 节流触发：领先沿立即发送，窗口内后续变化合并为窗口关闭时的一次尾沿（携带最新状态）。
     * 决策与发送均委托共享回传管线 {@link TargetProcessPublisher}。
     */
    private static void publish() {
        TargetProcessPublisher.publish(sThrottle, sChannel);
    }

    private static String buildJson() {
        try {
            JSONObject root = new JSONObject();
            JSONObject features = new JSONObject();
            int enabledCount = 0;
            int installed = 0;
            int expected = 0;
            int failed = 0;
            int noHit = 0;
            int degradedCount = 0;
            int unavailableCount = 0;
            for (Feature f : Feature.values()) {
                State s = sStates.get(f);
                if (s == null) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("enabled", s.enabled);
                o.put("expected", s.expected.get());
                o.put("installed", s.installed.get());
                o.put("failed", s.failed.get());
                o.put("criticalFailed", s.criticalFailed.get());
                o.put("entryCount", s.entryCount.get());
                o.put("hit", s.hit);
                o.put("hitCount", s.hitCount.get());
                o.put("degraded", s.degraded);
                o.put("unavailable", s.unavailable);
                if (s.lastFailure != null) {
                    o.put("lastFailure", s.lastFailure);
                }
                features.put(f.name(), o);
                if (s.enabled) {
                    enabledCount++;
                    installed += s.installed.get();
                    expected += s.expected.get();
                    failed += s.failed.get();
                    if (s.degraded) {
                        degradedCount++;
                    }
                    if (s.unavailable) {
                        unavailableCount++;
                    }
                    if (!s.hit && s.expected.get() > 0) {
                        noHit++;
                    }
                }
            }
            JSONObject abtest = abTestSummary();
            root.put("updatedAt", System.currentTimeMillis());
            if (sProcessName != null) {
                root.put("process", sProcessName);
            }
            root.put("enabledFeatures", enabledCount);
            root.put("installed", installed);
            root.put("expected", expected);
            root.put("failed", failed);
            root.put("noHitFeatures", noHit);
            root.put("degradedFeatures", degradedCount);
            root.put("unavailableFeatures", unavailableCount);
            if (abtest != null) {
                root.put("abtest", abtest);
            }
            root.put("features", features);
            String json = root.toString();
            if (json.length() > MAX_JSON_CHARS) {
                // 极端情况下退回摘要，保证不超过 64KB。
                JSONObject compact = new JSONObject();
                compact.put("updatedAt", System.currentTimeMillis());
                if (sProcessName != null) {
                    compact.put("process", sProcessName);
                }
                compact.put("enabledFeatures", enabledCount);
                compact.put("installed", installed);
                compact.put("expected", expected);
                compact.put("failed", failed);
                compact.put("noHitFeatures", noHit);
                compact.put("degradedFeatures", degradedCount);
                compact.put("unavailableFeatures", unavailableCount);
                if (abtest != null) {
                    compact.put("abtest", abtest);
                }
                compact.put("truncated", true);
                return compact.toString();
            }
            return json;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有任一 ABTest 记录时返回根级概要对象，否则 null（无记录不输出该键）。 */
    private static JSONObject abTestSummary() throws JSONException {
        int installed = sAbTestInstalled.get();
        int failed = sAbTestFailed.get();
        if (installed == 0 && failed == 0) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("installed", installed);
        o.put("failed", failed);
        if (sAbTestLastFailure != null) {
            o.put("lastFailure", sAbTestLastFailure);
        }
        return o;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
