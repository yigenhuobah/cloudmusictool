package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * PV-03 启动任务黑名单：拦截启动黑名单中的非必要任务与追踪组件。
 *
 * 核心任务保护名单（PROTECTED_TASKS + ServiceFacade 前缀）与 EX-02 共享
 * （StartupOrchestrationHooks.isProtected）：受保护任务命中黑名单也不拦截，
 * 避免用户误配黑名单误杀核心启动任务。
 */
public final class StartupBlacklistHooks {

    private StartupBlacklistHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.STARTUP_BLACKLIST;

        Set<String> cfgList = config.getFilterSet(Config.KEY_STARTUP_TASK_BLOCKLIST);
        final Set<String> blocklist;
        if (cfgList != null && !cfgList.isEmpty()) {
            blocklist = new HashSet<>(cfgList);
        } else {
            blocklist = new HashSet<>(Arrays.asList(
                    "AdOaidUploadTask",
                    "NBLogTask",
                    "NeedLogToFileTask"
            ));
        }

        // StartupTask.needExecute()Z
        try {
            Class<?> taskCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.core.startup.StartupTask");
            if (taskCls != null) {
                Method mNeedExecute = HookKit.findMethodQuiet(taskCls, "needExecute");
                if (mNeedExecute != null) {
                    HookKit.install(module, f, "StartupTask#needExecute", mNeedExecute, chain -> {
                        try {
                            Object thisObj = chain.getThisObject();
                            String taskName = null;
                            String simpleName = null;
                            if (thisObj != null) {
                                simpleName = thisObj.getClass().getSimpleName();
                                try {
                                    Method mName = thisObj.getClass().getMethod("taskName");
                                    mName.setAccessible(true);
                                    Object r = mName.invoke(thisObj);
                                    if (r != null) {
                                        taskName = r.toString();
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            if (shouldSkip(blocklist, taskName, simpleName)) {
                                HookKit.hit(module, f, "StartupTask.needExecute blocked "
                                        + (taskName != null ? taskName : simpleName));
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error StartupTask#needExecute", t);
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " StartupTask hook failed", t);
        }
    }

    /**
     * needExecute 拦截判定：黑名单命中且未受保护才跳过。
     * 保护判定（{@code StartupOrchestrationHooks.isProtected}，含 ServiceFacade 前缀）
     * 与 EX-02 共用：受保护任务即使被列入黑名单也放行原返回，与 EX-02 的
     * shouldOrchestrate 保护语义对称。
     */
    private static boolean shouldSkip(Set<String> blocklist, String taskName, String simpleName) {
        boolean blocked = (taskName != null && blocklist.contains(taskName))
                || (simpleName != null && blocklist.contains(simpleName));
        if (!blocked) {
            return false;
        }
        return !StartupOrchestrationHooks.isProtected(taskName)
                && !StartupOrchestrationHooks.isProtected(simpleName);
    }
}
