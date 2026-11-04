package com.cloudmusictool.app;

import android.util.Log;

import io.github.yigenhuobah.core.HookStateCore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目标进程内的 Hook 健康度状态。
 *
 * <p>本类现在只保留模块专属的胶水：Feature 枚举映射、enabled/unavailable 展示语义、
 * ABTest 概要、崩溃摘要环形缓冲、回传节流与 JSON 字段面。安装/失败/命中/入口/
 * 最近失败原因的账本统一委托 {@link HookStateCore}（按 {@link Feature#key} 记账），
 * 不再维护一份本地 State。
 *
 * <p>写入策略：
 * - 普通状态变化按「领先 + 尾沿」节流：窗口首个变化立即发送（保持安装期响应性），
 *   窗口内后续变化在窗口关闭时合并为一次尾沿发送（携带最新状态），避免最后一次
 *   变化落在节流窗口内且之后无事件而永不上报；
 * - 安装结束后由 Hooks.installAll 调用一次 flush() 发送最终快照；
 * - 崩溃路径由 {@link #flushNow()} 在调用线程同步发送一次（不排队、不节流）；
 * - 发送（节流调度、单线程工作线程、targetContext 解析、authority 拼接、
 *   warning-once 日志）统一委托共享回传管线 {@link TargetProcessPublisher}，
 *   与 ExperimentState 共用；节流决策见其 PublishThrottle。
 */
public final class HookState {

    private static final String TAG = "CloudMusicTool";
    private static final int MAX_JSON_CHARS = 64 * 1024;

    /**
     * crashes 通道（内置诊断）单条摘要的最大长度：240，给 ReportProtocol
     * MAX_VALUE_CHARS=256 的通道校验留出余量（时间 + 线程名 + 异常摘要拼成一条）。
     */
    static final int MAX_CRASH_CHARS = 240;

    /** 安装/失败/命中/入口账本（core 纯 JVM 实现，按 Feature.key 记账）。 */
    private static final HookStateCore sCore = new HookStateCore();

    /**
     * 模块专属展示状态：core 账本不记录「是否启用」与「安装结束后是否一个挂载点
     * 都没装上」。enabled 决定根级汇总是否计入该 Feature；unavailable 只在
     * {@link #finish(Feature)} 结算，{@link #installed(Feature, String)} 立即清除。
     */
    private static final Set<Feature> sEnabled = ConcurrentHashMap.newKeySet();
    private static final Set<Feature> sUnavailable = ConcurrentHashMap.newKeySet();

    // 最近崩溃：最新在队尾，超过 MAX_CRASH_ENTRIES 条顶掉最旧的；只在 recordCrash/
    // crashesArray 内同步访问，publish 在锁外调用。
    private static final ArrayDeque<String> sCrashes = new ArrayDeque<>();

    /**
     * 进程级会话标识："s" + 目标进程启动时刻（epoch ms）+ "-" + 8 位随机 hex。
     *
     * 读取侧按字符串身份比较（{@link ReportProtocol#isNewSession}）：不同即新会话、
     * 相同即同进程，不再按毫秒大小排序——设备时钟回拨后新进程的毫秒值更小，
     * 按大小比较会把该进程整个生命周期的回传误判为旧会话整包丢弃。
     * 随机后缀还保证同一毫秒内重启的两个进程不会撞号。
     *
     * 目标进程重启后 session 必变，StatusProvider 据此把上一会话的过期数据（含
     * experiments 快照）作废；同一会话内的回写按整包覆盖。
     * 读取侧以根级 updatedAt 作为 report_time，超过
     * {@link ReportProtocol#DEFAULT_TTL_MS}（10 分钟）没有新回写即视为过期（未知），
     * 诊断页显示“数据已过期”。
     *
     * 这里刻意不发送周期性心跳：心跳会引入常驻的固定频率调度，且与单测注入的
     * 可数执行器（只支持 execute/schedule，scheduleAtFixedRate 直接抛异常）语义冲突；
     * 空闲但健康的目标进程会随 TTL 自然过期，属于设计内的“未知”状态。
     */
    static final String SESSION = newSessionId();

    private static String newSessionId() {
        return "s" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ABTEST 不是 Feature（AbTestHooks 本地定位/安装），单独一组静态计数，
    // 经同一 publish 节奏回传，诊断页在根级 "abtest" 概要中展示。
    private static final AtomicInteger sAbTestInstalled = new AtomicInteger();
    private static final AtomicInteger sAbTestFailed = new AtomicInteger();
    private static final Set<String> sAbTestFailedKeys = ConcurrentHashMap.newKeySet();
    private static volatile String sAbTestLastFailure;

    // 节流器包私有可整体替换：单测注入假时钟；执行器与发送管线在 TargetProcessPublisher。
    // 生产用单调时钟（nanoTime），墙钟回拨/跳变不会让节流窗口判定错乱。
    static volatile TargetProcessPublisher.PublishThrottle sThrottle =
            new TargetProcessPublisher.PublishThrottle(
                    TargetProcessPublisher.PublishThrottle.MONOTONIC_CLOCK);

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
        sEnabled.add(f);
        sCore.enable(f.key);
        Log.i(TAG, f.id + " enabled");
        publish();
    }

    public static void installed(Feature f, String label) {
        if (f == null) {
            return;
        }
        // 启动阶段可能已 finish 过（installed=0 -> unavailable=true）；运行时
        // ClassLoader 安装成功后必须立即清除，不能等下一次 finish。
        sUnavailable.remove(f);
        // 同一挂载点可能先 quiet miss、再经 fallback 定位成功：core 安装成功后
        // 会撤销该挂载点的失败记录，避免诊断页同时显示 installed 与 failed。
        sCore.installed(f.key, label);
        publish();
    }

    /**
     * 安装结束后结算健康状态。
     * - unavailable：一个挂载点都没装上；
     * - degraded：由 core 的 critical 失败集合派生（见 {@link #buildJson(int)}）。
     * expected 由 core 的 installed + failed 派生，保证诊断卡比例自洽。
     */
    public static void finish(Feature f) {
        if (f == null) {
            return;
        }
        // 与旧实现一致：即使从未 enable/installed，finish 也要让该 Feature 进入快照。
        sCore.enable(f.key);
        if (sCore.installedCount(f.key) == 0) {
            sUnavailable.add(f);
        } else {
            sUnavailable.remove(f);
        }
        publish();
    }

    /**
     * 同一 Feature 的同一 mount label 只记一次失败，避免 findClass/findMethod 两步查找重复计数，
     * 并标记该挂载点是否为 Profile 中的 critical。
     * critical 标记单独去重：即使 failed 已计过，也只在首次标记 critical 时累加。
     *
     * <p>挂载点信息从 key 的既有格式推导（core 的 mountLabelFromKey）；
     * 需要精确挂载点时改用
     * {@link #markNotFound(Feature, String, boolean, String)}。
     */
    public static void markNotFound(Feature f, String label, boolean critical) {
        if (f == null) {
            return;
        }
        if (critical) {
            sCore.markNotFoundCritical(f.key, label);
        } else {
            sCore.markNotFound(f.key, label);
        }
        publish();
    }

    /**
     * 带显式挂载点 label 的失败记账。
     *
     * <p>{@code mountLabel} 用于同一挂载点后续 fallback 定位/安装成功时撤销失败
     * （{@link #installed(Feature, String)}）。quiet 定位的失败键里已经带了挂载点，
     * 但键本身不是稳定的挂载点标识，不能直接拿键去匹配安装 label。
     */
    public static void markNotFound(Feature f, String label, boolean critical,
                                    String mountLabel) {
        if (f == null) {
            return;
        }
        if (critical) {
            sCore.markNotFoundCritical(f.key, label, mountLabel);
        } else {
            sCore.markNotFound(f.key, label, mountLabel);
        }
        publish();
    }

    public static void note(Feature f, String text) {
        noteInternal(f, null, text);
    }

    /**
     * 带挂载点的失败说明：挂载点后续安装成功时，{@link #installed(Feature, String)}
     * 可一并清除这条 lastFailure，避免诊断页继续展示已经恢复的 skip 原因。
     */
    public static void noteMount(Feature f, String mountLabel, String text) {
        noteInternal(f, mountLabel, text);
    }

    private static void noteInternal(Feature f, String mountLabel, String text) {
        if (f == null) {
            return;
        }
        // 与旧实现一致：note 也会让该 Feature 进入快照（text 为 null 时只建账本）。
        sCore.enable(f.key);
        if (mountLabel == null) {
            sCore.note(f.key, text);
        } else {
            sCore.noteMount(f.key, mountLabel, text);
        }
        if (text != null) {
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
        sAbTestLastFailure = truncate(text, 160);
        publish();
    }

    // ------------------------------------------------------------------
    // crashes 通道（内置诊断：目标进程内未捕获异常）
    // ------------------------------------------------------------------

    /**
     * 记录一条未捕获异常摘要（由 CloudMusicToolModule 的 UEH 包装调用）。
     *
     * 条目格式："<时间> <线程名> <异常类名+栈前 2 帧>"（{@link #crashSummary}），
     * 单条截断 ≤ {@link #MAX_CRASH_CHARS}；进程内只保留最近
     * {@link ReportProtocol#MAX_CRASH_ENTRIES} 条（新的顶掉最旧的），buildJson 在
     * 根级 "crashes" 数组输出（[0] 为最新）。自身全程吞掉异常：崩溃记录绝不能再抛。
     *
     * @param at         崩溃时间（epoch ms）
     * @param threadName 崩溃线程名（可空，按空串处理）
     * @param summary    异常摘要（{@link #crashSummary} 产出）；null/空忽略
     */
    public static void recordCrash(long at, String threadName, String summary) {
        try {
            String entry = formatCrashEntry(at, threadName, summary);
            if (entry == null) {
                return;
            }
            synchronized (sCrashes) {
                sCrashes.addLast(entry);
                while (sCrashes.size() > ReportProtocol.MAX_CRASH_ENTRIES) {
                    sCrashes.pollFirst();
                }
            }
            publish();
        } catch (Throwable ignored) {
            // 崩溃记录失败只静默放弃。
        }
    }

    /** 异常类名 + 栈前 2 帧的摘要；无栈帧时只有类名，t 为 null 返回空串。 */
    static String crashSummary(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < frames.length && i < 2; i++) {
            sb.append(i == 0 ? " at " : "; ").append(frames[i]);
        }
        return sb.toString();
    }

    /** "<时间> <线程名> <异常摘要>"；summary 为空返回 null（不记），超长截断到 240（含省略号）。 */
    private static String formatCrashEntry(long at, String threadName, String summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(at));
        String line = time + " " + (threadName == null ? "" : threadName) + " " + summary;
        return line.length() <= MAX_CRASH_CHARS
                ? line : line.substring(0, MAX_CRASH_CHARS - 1) + "…";
    }

    /** 最近崩溃数组（[0] 为最新）；无记录返回 null（buildJson 不输出该键）。 */
    private static JSONArray crashesArray() {
        synchronized (sCrashes) {
            if (sCrashes.isEmpty()) {
                return null;
            }
            JSONArray array = new JSONArray();
            Iterator<String> it = sCrashes.descendingIterator();
            while (it.hasNext()) {
                array.put(it.next());
            }
            return array;
        }
    }

    /**
     * 记录 Feature 入口调用次数。与 {@link #hit(Feature)} 区分：
     * entry 只表示 hook 入口被调用，hit 表示过滤/改写真正生效。
     */
    public static void entry(Feature f) {
        if (f == null) {
            return;
        }
        sCore.entry(f.key);
        publish();
    }

    public static void hit(Feature f) {
        if (f == null) {
            return;
        }
        sCore.hit(f.key);
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
     * 崩溃路径专用同步 flush：在当前线程直接构建快照并投递给模块 App，
     * 不排队到工作线程、不经过节流；任何异常都被吞掉。
     *
     * <p>目标进程没有可写的本地 prefs（remote prefs 只读，回传经 ContentResolver
     * 交给模块 App），因此这里同步的是 ContentResolver.call 的完成，而不是本进程
     * 的 SharedPreferences 落盘；状态持久化由 StatusProvider 负责。
     *
     * @return 是否在返回前完成了一次同步投递（Context 不可用/未初始化/异常时为 false）
     */
    public static boolean flushNow() {
        try {
            if (sModulePackage == null || sModulePackage.isEmpty()) {
                return false;
            }
            return TargetProcessPublisher.flushNow(sChannel);
        } catch (Throwable ignored) {
            // 崩溃路径绝不能因为 flush 失败而改变原有异常处理流程。
            return false;
        }
    }

    /**
     * 节流触发：领先沿立即发送，窗口内后续变化合并为窗口关闭时的一次尾沿（携带最新状态）。
     * 决策与发送均委托共享回传管线 {@link TargetProcessPublisher}。
     */
    private static void publish() {
        TargetProcessPublisher.publish(sThrottle, sChannel);
    }

    private static String buildJson() {
        return buildJson(MAX_JSON_CHARS);
    }

    /**
     * 包内部入口：单测用极小上限直接验证超限后的摘要回退分支（truncated + session）。
     * 生产只经无参 {@link #buildJson()} 走 {@link #MAX_JSON_CHARS}。
     */
    static String buildJson(int maxChars) {
        try {
            JSONObject root = new JSONObject();
            JSONObject features = new JSONObject();
            Set<String> touched = new HashSet<>(sCore.touched());
            int enabledCount = 0;
            int installed = 0;
            int expected = 0;
            int failed = 0;
            int noHit = 0;
            int degradedCount = 0;
            int unavailableCount = 0;
            for (Feature f : Feature.values()) {
                if (!touched.contains(f.key)) {
                    continue;
                }
                int featureInstalled = sCore.installedCount(f.key);
                int featureFailed = sCore.failedCount(f.key);
                int featureCriticalFailed = sCore.criticalFailedCount(f.key);
                int featureExpected = featureInstalled + featureFailed;
                long featureHits = sCore.hits(f.key);
                boolean enabled = sEnabled.contains(f);
                boolean degraded = sCore.hasCriticalSkip(f.key);
                boolean unavailable = sUnavailable.contains(f);

                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("enabled", enabled);
                o.put("expected", featureExpected);
                o.put("installed", featureInstalled);
                o.put("failed", featureFailed);
                o.put("criticalFailed", featureCriticalFailed);
                o.put("entryCount", sCore.entries(f.key));
                o.put("hit", featureHits > 0L);
                o.put("hitCount", featureHits);
                o.put("degraded", degraded);
                o.put("unavailable", unavailable);
                String lastFailure = sCore.lastFailure(f.key);
                if (lastFailure != null) {
                    o.put("lastFailure", lastFailure);
                }
                features.put(f.name(), o);
                if (enabled) {
                    enabledCount++;
                    installed += featureInstalled;
                    expected += featureExpected;
                    failed += featureFailed;
                    if (degraded) {
                        degradedCount++;
                    }
                    if (unavailable) {
                        unavailableCount++;
                    }
                    if (featureHits == 0L && featureExpected > 0) {
                        noHit++;
                    }
                }
            }
            JSONObject abtest = abTestSummary();
            JSONArray crashes = crashesArray();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("session", SESSION);
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
            if (crashes != null) {
                root.put("crashes", crashes);
            }
            root.put("features", features);
            String json = root.toString();
            if (json.length() > maxChars) {
                // 极端情况下退回摘要，保证不超过 64KB。
                JSONObject compact = new JSONObject();
                compact.put("updatedAt", System.currentTimeMillis());
                compact.put("session", SESSION);
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
                // 崩溃摘要体积极小（≤3×240 字符），摘要分支同样保留：
                // 超限时丢失的应是逐功能明细而不是崩溃证据。
                if (crashes != null) {
                    compact.put("crashes", crashes);
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
