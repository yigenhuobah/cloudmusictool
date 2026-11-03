package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * PV-02 日志写盘任务屏蔽：阻断 NeedLogToFileTask 与 NBLogTask 启动任务。
 */
public final class LogTaskHooks {

    private LogTaskHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.LOG_TASKS;

        // 1. NeedLogToFileTask.init()V -> skipVoid
        try {
            Class<?> needLogCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.process.task.app.create.NeedLogToFileTask");
            if (needLogCls != null) {
                Method mInit = HookKit.findMethodQuiet(module, f, "NeedLogToFileTask#init",
                        needLogCls, "init");
                if (mInit != null) {
                    HookKit.install(module, f, "NeedLogToFileTask#init", mInit,
                            HookKit.skipVoid(module, f, "NeedLogToFileTask.init"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " NeedLogToFileTask hook failed", t);
        }

        // 2. NBLogTask.init()V -> skipVoid
        try {
            Class<?> nbLogCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.process.task.app.create.NBLogTask");
            if (nbLogCls != null) {
                Method mInit = HookKit.findMethodQuiet(module, f, "NBLogTask#init",
                        nbLogCls, "init");
                if (mInit != null) {
                    HookKit.install(module, f, "NBLogTask#init", mInit,
                            HookKit.skipVoid(module, f, "NBLogTask.init"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " NBLogTask hook failed", t);
        }
    }
}
