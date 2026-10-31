package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EX-02 启动任务编排。
 *
 * 只处理 startup_task_blocklist（与 PV-03 共用）中显式列出的非核心任务：
 * - runOnUIThread -> false：移出主线程；
 * - priority -> 降低优先级（数值增大，宿主的比较器为升序）；
 * - degradeInit -> 跳过：降级路径下不再执行该任务；
 * - runOnStage -> 保持原阶段（阶段重映射会破坏任务依赖顺序，只记录不修改）。
 *
 * 核心任务保护名单内的任务永不改动。
 */
public final class StartupOrchestrationHooks {

    private static final String STARTUP_TASK =
            "com.netease.cloudmusic.core.startup.StartupTask";

    /** 核心启动任务：跳过/降级会直接导致崩溃或登录态异常。PV-03 与本类共享（经 {@link #isProtected}）。 */
    public static final Set<String> PROTECTED_TASKS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "CoreInitTask",
                    "NativeInitTask",
                    "MMKVTask",
                    "ServiceFacadeAllTask",
                    "ServiceFacadeMainTask",
                    "ServiceFacadeMainManualTask",
                    "ServiceFacadeWithoutAppWidgetTask",
                    "WorkPathTask",
                    "SdWorkPathTask",
                    "CookiePreloadTask",
                    "NetworkTask",
                    "SessionTask"
            )));

    private static final String PROTECTED_PREFIX = "ServiceFacade";
    private static final int PRIORITY_PENALTY = 1000;

    private StartupOrchestrationHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "EX-02 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 与 PV-03 一致：使用 installOne 传入的会话级 config 快照，不二次 Config.load。
        Set<String> configured = config.getFilterSet(Config.KEY_STARTUP_TASK_BLOCKLIST);
        final Set<String> blocklist = configured == null || configured.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(configured));

        if (blocklist.isEmpty()) {
            HookKit.info(module, f.id
                    + " startup_task_blocklist is empty, no orchestration applied");
            return;
        }

        try {
            Class<?> cls = HookKit.findClass(module, f, cl, STARTUP_TASK);
            if (cls == null) {
                return;
            }

            // 1. runOnUIThread()Z -> false（移出主线程）
            try {
                Method m = HookKit.findMethod(module, f, cls, "runOnUIThread",
                        boolean.class, new Class<?>[0]);
                if (m != null) {
                    if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                        HookKit.install(module, f, "StartupTask#runOnUIThread", m,
                                chain -> interceptRunOnUiThread(module, f, blocklist, chain));
                    } else {
                        HookKit.warn(module, f.id + " skip StartupTask#runOnUIThread: unexpected return "
                                + m.getReturnType());
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " StartupTask#runOnUIThread hook failed", t);
            }

            // 2. priority()I -> 降优先级
            try {
                Method m = HookKit.findMethod(module, f, cls, "priority",
                        int.class, new Class<?>[0]);
                if (m != null) {
                    if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                        HookKit.install(module, f, "StartupTask#priority", m,
                                chain -> interceptPriority(module, f, blocklist, chain));
                    } else {
                        HookKit.warn(module, f.id + " skip StartupTask#priority: unexpected return "
                                + m.getReturnType());
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " StartupTask#priority hook failed", t);
            }

            // 3. runOnStage()I -> 保持原阶段，仅记录（阶段重映射风险高）
            try {
                Method m = HookKit.findMethod(module, f, cls, "runOnStage",
                        int.class, new Class<?>[0]);
                if (m != null) {
                    if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                        HookKit.install(module, f, "StartupTask#runOnStage", m,
                                chain -> interceptRunOnStage(module, f, blocklist, chain));
                    } else {
                        HookKit.warn(module, f.id + " skip StartupTask#runOnStage: unexpected return "
                                + m.getReturnType());
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " StartupTask#runOnStage hook failed", t);
            }

            // 4. degradeInit()V -> 降级路径下跳过
            try {
                Method m = HookKit.findMethod(module, f, cls, "degradeInit",
                        void.class, new Class<?>[0]);
                if (m != null && m.getReturnType() == void.class) {
                    HookKit.install(module, f, "StartupTask#degradeInit", m,
                            chain -> interceptDegradeInit(module, f, blocklist, chain));
                } else if (m != null) {
                    HookKit.warn(module, f.id + " skip StartupTask#degradeInit: unexpected return "
                            + m.getReturnType());
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " StartupTask#degradeInit hook failed", t);
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " StartupTask hook failed", t);
        }
    }

    /**
     * runOnUIThread：命中黑名单返回 false；其余路径只 proceed 一次。
     * 判定阶段的异常只记录，随后走 proceed 放行宿主原逻辑。
     */
    static Object interceptRunOnUiThread(XposedModule module, Feature f, Set<String> blocklist,
                                         XposedInterface.Chain chain) throws Throwable {
        try {
            String task = taskNameOf(chain.getThisObject());
            if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                HookKit.hit(module, f, "StartupTask.runOnUIThread",
                        "StartupTask.runOnUIThread -> false " + task);
                return Boolean.FALSE;
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error StartupTask#runOnUIThread", t);
        }
        return proceed(module, f, "StartupTask#runOnUIThread", chain);
    }

    /**
     * priority：先 proceed 一次拿到宿主值，再按黑名单加罚分。
     *
     * proceed 必须在所有 try 之外调用一次；后处理（记账/日志）抛异常时返回宿主原值，
     * 绝不二次 proceed —— 否则宿主 priority() 会被执行两遍，首个异常还会被第二次调用覆盖。
     */
    static Object interceptPriority(XposedModule module, Feature f, Set<String> blocklist,
                                    XposedInterface.Chain chain) throws Throwable {
        Object base = proceed(module, f, "StartupTask#priority", chain);
        try {
            String task = taskNameOf(chain.getThisObject());
            if (!shouldOrchestrate(blocklist, chain.getThisObject())) {
                return base;
            }
            int current = base instanceof Number ? ((Number) base).intValue() : 0;
            int shifted = current > Integer.MAX_VALUE - PRIORITY_PENALTY
                    ? Integer.MAX_VALUE
                    : current + PRIORITY_PENALTY;
            HookKit.hit(module, f, "StartupTask.priority",
                    "StartupTask.priority " + current + " -> " + shifted + " " + task);
            return Integer.valueOf(shifted);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error StartupTask#priority", t);
            return base;
        }
    }

    /** runOnStage：只记录不改动；proceed 只调用一次，后处理失败返回宿主原值。 */
    static Object interceptRunOnStage(XposedModule module, Feature f, Set<String> blocklist,
                                      XposedInterface.Chain chain) throws Throwable {
        Object stage = proceed(module, f, "StartupTask#runOnStage", chain);
        try {
            String task = taskNameOf(chain.getThisObject());
            if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                HookKit.hit(module, f, "StartupTask.runOnStage",
                        "StartupTask.runOnStage kept " + stage + " " + task);
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error StartupTask#runOnStage", t);
        }
        return stage;
    }

    /** degradeInit：命中黑名单跳过（void）；其余路径只 proceed 一次。 */
    static Object interceptDegradeInit(XposedModule module, Feature f, Set<String> blocklist,
                                       XposedInterface.Chain chain) throws Throwable {
        try {
            String task = taskNameOf(chain.getThisObject());
            if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                HookKit.hit(module, f, "StartupTask.degradeInit",
                        "StartupTask.degradeInit skipped " + task);
                return null;
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error StartupTask#degradeInit", t);
        }
        return proceed(module, f, "StartupTask#degradeInit", chain);
    }

    /** 唯一的 proceed 入口：异常记录后原样抛回，调用方不得再补一次。 */
    private static Object proceed(XposedModule module, Feature f, String label,
                                  XposedInterface.Chain chain) throws Throwable {
        try {
            return chain.proceed();
        } catch (Throwable t) {
            HookKit.error(module, f.id + " proceed error " + label, t);
            throw t;
        }
    }

    private static String taskNameOf(Object task) {
        if (task == null) {
            return null;
        }
        try {
            Method m = task.getClass().getMethod("taskName");
            m.setAccessible(true);
            Object name = m.invoke(task);
            if (name != null) {
                String value = name.toString();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return task.getClass().getSimpleName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shouldOrchestrate(Set<String> blocklist, Object task) {
        if (task == null) {
            return false;
        }
        String taskName = taskNameOf(task);
        String simpleName;
        try {
            simpleName = task.getClass().getSimpleName();
        } catch (Throwable t) {
            simpleName = null;
        }
        boolean listed = (taskName != null && blocklist.contains(taskName))
                || (simpleName != null && blocklist.contains(simpleName));
        if (!listed) {
            return false;
        }
        return !isProtected(taskName) && !isProtected(simpleName);
    }

    /** 保护判定：清单命中或 ServiceFacade 前缀命中；PV-03（StartupBlacklistHooks）与本类共用。 */
    public static boolean isProtected(String name) {
        if (name == null) {
            return false;
        }
        return PROTECTED_TASKS.contains(name) || name.startsWith(PROTECTED_PREFIX);
    }
}
