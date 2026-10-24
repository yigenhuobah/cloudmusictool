package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.AbTestPolicy;
import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.HookState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ABTest 只读观测 + 精确白名单强开。
 *
 * 只读观测始终安装；forceMap 为空时绝不修改任何返回值。
 * 强开只针对 {@link Config#getAbTestForceMap()} 中已通过 AbTestPolicy 过滤的 key，
 * 且运行时会再用 isAllowed + isValidGroup 二次校验。
 *
 * 递归防护：所有 hook 内只调用 chain.proceed()，绝不调用被 hook 的同名方法。
 */
public final class AbTestHooks {

    private static final String TAG = HookKit.TAG;

    private static final String CLS_MANAGER = "com.netease.cloudmusic.abtest2.ABTestManagerImpl";
    private static final String CLS_CONFIG = "com.netease.cloudmusic.abtest2.ABTestConfig";
    private static final String CLS_LIBRA_CONFIG = "com.netease.cloudmusic.abtest2.LibraABTestConfig";
    private static final String CLS_MODEL = "com.netease.cloudmusic.abtest2.b";
    private static final String CLS_STARTUP_AB = "com.netease.cloudmusic.module.startup.StartupABManager";

    private static final long FORCE_LOG_WINDOW_MS = 5000L;
    private static final int MAX_OBSERVE_LOGS = 100;

    private static volatile Map<String, String> sForceMap = Collections.emptyMap();
    private static volatile Set<String> sPromoted = Collections.emptySet();

    private static final Set<String> sObservedKeys = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger sObserveLogs = new AtomicInteger(0);
    private static final Map<String, long[]> sForceLogState = new ConcurrentHashMap<>();

    private static volatile boolean sConfigResolved;
    private static volatile Class<?> sLibraClass;
    private static volatile Constructor<?> sLibraCtor;
    private static volatile Constructor<?> sBaseCtor;
    private static volatile Field sNameField;
    private static volatile Field sGroupField;
    private static volatile Field sStatusField;
    private static volatile Field sClientField;
    private static volatile Field sLibraLogField;
    private static volatile Field sLibraExpInfoField;
    private static volatile Field sLibraTimestampField;

    private AbTestHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        Map<String, String> map = config == null
                ? Collections.<String, String>emptyMap()
                : config.getAbTestForceMap();
        Map<String, String> snapshot = new HashMap<>(map);
        Set<String> promoted = new HashSet<>(snapshot.keySet());
        promoted.removeAll(AbTestPolicy.CURATED);
        sForceMap = Collections.unmodifiableMap(snapshot);
        sPromoted = Collections.unmodifiableSet(promoted);

        resolveConfigClasses(module, cl);

        HookKit.info(module, "ABTEST install observe=on forceKeys=" + snapshot.size());

        installManagerHook(module, cl);
        installGroupKeysHook(module, cl, CLS_CONFIG, "ABTestConfig#getGroupKeys");
        installGroupKeysHook(module, cl, CLS_LIBRA_CONFIG, "LibraABTestConfig#getGroupKeys");
        installModelHook(module, cl);
        installStartupHook(module, cl);
    }

    // ------------------------------------------------------------------
    // hook 点
    // ------------------------------------------------------------------

    private static void installManagerHook(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = HookKit.findClass(module, null, cl, CLS_MANAGER);
            Method m = HookKit.findMethod(module, null, cls, "checkExperiment", null,
                    new Class<?>[]{String.class, boolean.class}, (String[]) null);
            install(module, "ABTestManagerImpl#checkExperiment", m, chain -> {
                Object result = chain.proceed();
                try {
                    String key = argString(chain, 0);
                    if (result == null) {
                        String forced = forcedGroup(key);
                        if (forced != null) {
                            Object cfg = createConfig(module, cl, key, forced);
                            if (cfg != null) {
                                logForce(module, key, forced);
                                observe(module, key, forced, sourceOf(cfg));
                                return cfg;
                            }
                        }
                        return null;
                    }
                    observe(module, key, groupOf(result), sourceOf(result));
                } catch (Throwable t) {
                    HookKit.warn(module, "ABTEST observe error checkExperiment: " + t.getMessage());
                }
                return result;
            });
        } catch (Throwable t) {
            recordNotFound(module, "ABTestManagerImpl#checkExperiment", "skip", t);
        }
    }

    private static void installGroupKeysHook(XposedModule module, ClassLoader cl,
                                             String className, String label) {
        try {
            Class<?> cls = HookKit.findClass(module, null, cl, className);
            Method m = HookKit.findMethod(module, null, cls, "getGroupKeys", String.class,
                    new Class<?>[0], (String[]) null);
            install(module, label, m, chain -> {
                try {
                    Object self = chain.getThisObject();
                    String key = fieldString(self, "abtestname");
                    String forced = forcedGroup(key);
                    if (forced != null) {
                        logForce(module, key, forced);
                        return forced;
                    }
                } catch (Throwable t) {
                    HookKit.warn(module, "ABTEST force error " + label + ": " + t.getMessage());
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            recordNotFound(module, label, "skip", t);
        }
    }

    private static void installModelHook(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = HookKit.findClass(module, null, cl, CLS_MODEL);
            Method m = HookKit.findMethod(module, null, cls, "x", null,
                    new Class<?>[]{String.class, boolean.class}, (String[]) null);
            install(module, "abtest2.b#x", m, chain -> {
                Object result = chain.proceed();
                try {
                    String key = argString(chain, 0);
                    if (result == null) {
                        String forced = forcedGroup(key);
                        if (forced != null) {
                            Object cfg = createConfig(module, cl, key, forced);
                            if (cfg != null) {
                                logForce(module, key, forced);
                                observe(module, key, forced, "model");
                                return cfg;
                            }
                        }
                        return null;
                    }
                    observe(module, key, groupOf(result), "model");
                } catch (Throwable t) {
                    HookKit.warn(module, "ABTEST observe error b#x: " + t.getMessage());
                }
                return result;
            });
        } catch (Throwable t) {
            recordNotFound(module, "abtest2.b#x", "skip", t);
        }
    }

    private static void installStartupHook(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = HookKit.findClass(module, null, cl, CLS_STARTUP_AB);
            Method m = HookKit.findMethod(module, null, cls, "getGroup", String.class,
                    new Class<?>[]{String.class}, (String[]) null);
            install(module, "StartupABManager#getGroup", m, chain -> {
                Object result = chain.proceed();
                try {
                    String key = argString(chain, 0);
                    String group = result == null ? "c" : result.toString();
                    observe(module, key, group, "startup");
                } catch (Throwable t) {
                    HookKit.warn(module, "ABTEST observe error StartupABManager: " + t.getMessage());
                }
                return result;
            });
        } catch (Throwable t) {
            recordNotFound(module, "StartupABManager#getGroup", "skip", t);
        }
    }

    /**
     * 经 HookKit 的 Feature 可空路径安装：类/方法定位（含 Profile 坐标与 DexKit
     * 兜底）与返回类型校验全部复用主管线，本侧只按返回值落 ABTest 观测账
     * （ABTEST 不是 Feature，HookKit 不感知 abtest 通道，不写 Feature 级计数）。
     */
    private static void install(XposedModule module, String label, Method m,
                                XposedInterface.Hooker hooker) {
        if (m == null) {
            recordMiss(label);
            return;
        }
        if (HookKit.install(module, null, label, m, hooker)) {
            HookState.abTestInstalled(label);
        } else {
            HookState.abTestNotFound(label);
            HookState.noteAbTest("hook failed " + label);
        }
    }

    /** 定位失败（HookKit 返回 null）：skip 日志已由 HookKit 统一输出，这里只落观测账。 */
    private static void recordMiss(String label) {
        HookState.abTestNotFound(label);
        HookState.noteAbTest("skip " + label);
    }

    /**
     * 定位/安装失败的观测落账：失败按 label 去重计入 HookState，原因文本进根级
     * abtest 概要；日志与原 warn 文本保持一致。ABTEST 不是 Feature，不进 features。
     */
    private static void recordNotFound(XposedModule module, String label, String what,
                                       Throwable t) {
        String why = what + " " + label + ": " + t.getMessage();
        HookState.abTestNotFound(label);
        HookState.noteAbTest(why);
        HookKit.warn(module, "ABTEST " + why);
    }

    // ------------------------------------------------------------------
    // 强开决策与合成 config
    // ------------------------------------------------------------------

    /** 二次校验：精确命中 + 非黑名单（curated/已提升）+ 合法分组。 */
    private static String forcedGroup(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String group = sForceMap.get(key);
        if (group == null) {
            return null;
        }
        if (!AbTestPolicy.isValidGroup(group)) {
            return null;
        }
        if (!AbTestPolicy.isAllowed(key, sPromoted)) {
            return null;
        }
        return group;
    }

    private static void resolveConfigClasses(XposedModule module, ClassLoader cl) {
        if (sConfigResolved) {
            return;
        }
        synchronized (AbTestHooks.class) {
            if (sConfigResolved) {
                return;
            }
            try {
                Class<?> base = Class.forName(CLS_CONFIG, false, cl);
                sBaseCtor = declaredCtor(base);
                sNameField = accessibleField(base, "abtestname");
                sGroupField = accessibleField(base, "abtestgroup");
                sStatusField = accessibleField(base, "status");
                sClientField = accessibleField(base, "clientConfig");
            } catch (Throwable t) {
                HookKit.warn(module, "ABTEST ABTestConfig unavailable: " + t.getMessage());
            }
            try {
                Class<?> libra = Class.forName(CLS_LIBRA_CONFIG, false, cl);
                sLibraClass = libra;
                sLibraCtor = declaredCtor(libra);
                // Libra 独有字段：缺失时跳过，不影响基类字段。
                sLibraLogField = accessibleField(libra, "log");
                sLibraExpInfoField = accessibleField(libra, "expInfo");
                sLibraTimestampField = accessibleField(libra, "timestamp");
            } catch (Throwable t) {
                HookKit.warn(module, "ABTEST LibraABTestConfig unavailable: " + t.getMessage());
            }
            HookKit.info(module, "ABTEST config fields name=" + (sNameField != null)
                    + " group=" + (sGroupField != null)
                    + " status=" + (sStatusField != null)
                    + " client=" + (sClientField != null)
                    + " libra=" + (sLibraCtor != null)
                    + " log=" + (sLibraLogField != null)
                    + " expInfo=" + (sLibraExpInfoField != null)
                    + " timestamp=" + (sLibraTimestampField != null));
            sConfigResolved = true;
        }
    }

    /**
     * 合成 config：优先 LibraABTestConfig（是 ABTestConfig 子类，兼容基类 cast），
     * 失败退回 ABTestConfig。字段逐个写入，某个字段缺失/类型不符只跳过该字段，
     * 不因 Libra 独有字段缺失导致整体失败；构造失败返回 null，由调用方保持原返回值。
     */
    private static Object createConfig(XposedModule module, ClassLoader cl, String key, String group) {
        try {
            resolveConfigClasses(module, cl);
            Object cfg = newInstance(sLibraCtor);
            boolean libra = cfg != null;
            if (cfg == null) {
                cfg = newInstance(sBaseCtor);
            }
            if (cfg == null || sNameField == null || sGroupField == null) {
                return null;
            }
            setField(sNameField, cfg, key);
            setField(sGroupField, cfg, group);
            setIntField(sStatusField, cfg, 1);
            setField(sClientField, cfg, new HashMap<String, Object>());
            if (libra) {
                // 防止 Libra 专有代码直接读取这些字段时 NPE。
                setField(sLibraLogField, cfg, "");
                setField(sLibraExpInfoField, cfg, new HashMap<String, Object>());
                setLongField(sLibraTimestampField, cfg, System.currentTimeMillis());
            }
            return cfg;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Field f, Object target, Object value) {
        if (f == null) {
            return;
        }
        try {
            f.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setIntField(Field f, Object target, int value) {
        if (f == null) {
            return;
        }
        try {
            f.setInt(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setLongField(Field f, Object target, long value) {
        if (f == null) {
            return;
        }
        try {
            f.setLong(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static Object newInstance(Constructor<?> ctor) {
        if (ctor == null) {
            return null;
        }
        try {
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> declaredCtor(Class<?> cls) {
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field accessibleField(Class<?> cls, String name) {
        Field f = HookKit.findField(cls, name);
        if (f != null) {
            try {
                f.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        return f;
    }

    // ------------------------------------------------------------------
    // 读取与日志
    // ------------------------------------------------------------------

    private static String groupOf(Object cfg) {
        if (cfg == null) {
            return null;
        }
        try {
            if (sGroupField != null) {
                Object v = sGroupField.get(cfg);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            }
        } catch (Throwable ignored) {
        }
        // 不调用 getGroupKeys()：它可能被本模块 hook，读到的会是强制分组而非服务端原值。
        try {
            Field f = HookKit.findField(cfg.getClass(), "abtestgroup");
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(cfg);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String sourceOf(Object cfg) {
        Class<?> libra = sLibraClass;
        return libra != null && cfg != null && libra.isInstance(cfg) ? "libra" : "old";
    }

    private static String fieldString(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field f = HookKit.findField(target.getClass(), name);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object v = f.get(target);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String argString(XposedInterface.Chain chain, int index) {
        try {
            Object v = chain.getArg(index);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void observe(XposedModule module, String key, String group, String source) {
        try {
            ExperimentState.record(key, group, source);
        } catch (Throwable ignored) {
        }
        if (key == null || key.isEmpty()) {
            return;
        }
        if (!sObservedKeys.add(key)) {
            return;
        }
        int n = sObserveLogs.incrementAndGet();
        if (n <= MAX_OBSERVE_LOGS) {
            HookKit.info(module, "ABTEST observe key=" + key
                    + " group=" + (group == null ? "c" : group)
                    + " source=" + (source == null ? "unknown" : source)
                    + " (" + n + "/" + MAX_OBSERVE_LOGS + ")");
        }
    }

    private static void logForce(XposedModule module, String key, String group) {
        long now = System.currentTimeMillis();
        long[] state = sForceLogState.computeIfAbsent(key, k -> new long[]{0L, 0L});
        boolean log;
        synchronized (state) {
            log = state[1] == 0L || now - state[0] >= FORCE_LOG_WINDOW_MS;
            if (log) {
                state[0] = now;
                state[1]++;
            }
        }
        if (log) {
            HookKit.info(module, "ABTEST force key=" + key + " group=" + group);
        }
    }
}
