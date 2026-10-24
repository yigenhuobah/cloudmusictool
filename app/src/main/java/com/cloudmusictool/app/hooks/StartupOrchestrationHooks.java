package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
                        HookKit.install(module, f, "StartupTask#runOnUIThread", m, chain -> {
                            try {
                                String task = taskNameOf(chain.getThisObject());
                                if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                                    HookKit.hit(module, f, "StartupTask.runOnUIThread",
                                            "StartupTask.runOnUIThread -> false " + task);
                                    return Boolean.FALSE;
                                }
                                return chain.proceed();
                            } catch (Throwable t) {
                                HookKit.error(module, f.id
                                        + " intercept error StartupTask#runOnUIThread", t);
                                return chain.proceed();
                            }
                        });
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
                        HookKit.install(module, f, "StartupTask#priority", m, chain -> {
                            try {
                                Object base = chain.proceed();
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
                                return shifted;
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error StartupTask#priority", t);
                                return chain.proceed();
                            }
                        });
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
                        HookKit.install(module, f, "StartupTask#runOnStage", m, chain -> {
                            try {
                                Object stage = chain.proceed();
                                String task = taskNameOf(chain.getThisObject());
                                if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                                    HookKit.hit(module, f, "StartupTask.runOnStage",
                                            "StartupTask.runOnStage kept " + stage + " " + task);
                                }
                                return stage;
                            } catch (Throwable t) {
                                HookKit.error(module, f.id
                                        + " intercept error StartupTask#runOnStage", t);
                                return chain.proceed();
                            }
                        });
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
                    HookKit.install(module, f, "StartupTask#degradeInit", m, chain -> {
                        try {
                            String task = taskNameOf(chain.getThisObject());
                            if (shouldOrchestrate(blocklist, chain.getThisObject())) {
                                HookKit.hit(module, f, "StartupTask.degradeInit",
                                        "StartupTask.degradeInit skipped " + task);
                                return null;
                            }
                            return chain.proceed();
                        } catch (Throwable t) {
                            HookKit.error(module, f.id
                                    + " intercept error StartupTask#degradeInit", t);
                            return chain.proceed();
                        }
                    });
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
