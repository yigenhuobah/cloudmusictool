package com.cloudmusictool.app;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 目标进程内的 Hook 健康度状态。
 *
 * 记录每个 Feature 的 expected / installed / failed / hit 与最近失败原因；
 * 通过模块 App 的 StatusProvider 回写（remote prefs 在宿主进程只读，不能反向写）。
 *
 * 写入策略：
 * - 普通状态变化最多每 2 秒触发一次；
 * - 安装结束后由 Hooks.installAll 调用一次 flush() 发送最终快照；
 * - buildJson + ContentResolver.call 在单线程工作线程执行，避免阻塞主线程；
 * - 目标 Context 只反射解析一次并缓存。
 */
public final class HookState {

    private static final String TAG = "CloudMusicTool";
    private static final long PUBLISH_INTERVAL_MS = 2000L;
    private static final int MAX_JSON_CHARS = 64 * 1024;
    private static final int MAX_FAILURE_CHARS = 160;

    private static final Map<Feature, State> sStates = new ConcurrentHashMap<>();

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CloudMusicTool-HookState");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong sLastPublish = new AtomicLong(0L);
    private static final AtomicBoolean sPublishQueued = new AtomicBoolean(false);

    private static volatile String sModulePackage;
    private static volatile Context sTargetContext;
    private static volatile boolean sContextWarningLogged;
    private static volatile boolean sPublishFailureLogged;

    private HookState() {
    }

    private static final class State {
        volatile boolean enabled;
        final AtomicInteger expected = new AtomicInteger();
        final AtomicInteger installed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        /** 失败的挂载点中，Profile 标记为 critical 的数量（按 label 去重）。 */
        final AtomicInteger criticalFailed = new AtomicInteger();
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
        sModulePackage = modulePackage;
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

    /** 兼容旧调用：无 label 时每次都计数。 */
    public static void markNotFound(Feature f) {
        markNotFound(f, null);
    }

    /**
     * 同一 Feature 的同一 mount label 只记一次失败，避免 findClass/findMethod 两步查找重复计数。
     */
    public static void markNotFound(Feature f, String label) {
        markNotFound(f, label, false);
    }

    /**
     * 同上，并标记该挂载点是否为 Profile 中的 critical。
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
        sExecutor.execute(HookState::doPublish);
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
            root.put("updatedAt", System.currentTimeMillis());
            root.put("enabledFeatures", enabledCount);
            root.put("installed", installed);
            root.put("expected", expected);
            root.put("failed", failed);
            root.put("noHitFeatures", noHit);
            root.put("degradedFeatures", degradedCount);
            root.put("unavailableFeatures", unavailableCount);
            root.put("features", features);
            String json = root.toString();
            if (json.length() > MAX_JSON_CHARS) {
                // 极端情况下退回摘要，保证不超过 64KB。
                JSONObject compact = new JSONObject();
                compact.put("updatedAt", System.currentTimeMillis());
                compact.put("enabledFeatures", enabledCount);
                compact.put("installed", installed);
                compact.put("expected", expected);
                compact.put("failed", failed);
                compact.put("noHitFeatures", noHit);
                compact.put("degradedFeatures", degradedCount);
                compact.put("unavailableFeatures", unavailableCount);
                compact.put("truncated", true);
                return compact.toString();
            }
            return json;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 节流触发：最多每 2 秒排队一次，实际发送在工作线程。 */
    private static void publish() {
        long now = System.currentTimeMillis();
        long last = sLastPublish.get();
        if (now - last < PUBLISH_INTERVAL_MS) {
            return;
        }
        if (!sLastPublish.compareAndSet(last, now)) {
            return;
        }
        if (!sPublishQueued.compareAndSet(false, true)) {
            return;
        }
        sExecutor.execute(() -> {
            try {
                doPublish();
            } finally {
                sPublishQueued.set(false);
            }
        });
    }

    private static void doPublish() {
        String pkg = sModulePackage;
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String json = buildJson();
        if (json == null) {
            return;
        }
        try {
            Context ctx = targetContext();
            if (ctx == null) {
                if (!sContextWarningLogged) {
                    sContextWarningLogged = true;
                    Log.w(TAG, "hook status not published: target Context unavailable");
                }
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("json", json);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + pkg + ".status"),
                    "report", null, extras);
        } catch (Throwable t) {
            if (!sPublishFailureLogged) {
                sPublishFailureLogged = true;
                Log.w(TAG, "hook status publish failed: " + t.getMessage());
            }
        }
    }

    /** 反射 ActivityThread.currentApplication()，成功一次后缓存。 */
    private static Context targetContext() {
        Context cached = sTargetContext;
        if (cached != null) {
            return cached;
        }
        synchronized (HookState.class) {
            if (sTargetContext != null) {
                return sTargetContext;
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) {
                    return null;
                }
                Object app = at.getMethod("getApplication").invoke(thread);
                if (app instanceof Context) {
                    sTargetContext = (Context) app;
                }
            } catch (Throwable t) {
                return null;
            }
            return sTargetContext;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
