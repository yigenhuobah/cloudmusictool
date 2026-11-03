package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * PV-01 OAID 与设备追踪拦截：阻断 OAID 上报与 AdOaidUploadTask 启动任务。
 */
public final class OaidHooks {

    private OaidHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.OAID_REPORT;

        // 1. MusicOaidUploadManager.o(String)V -> skipVoid
        try {
            Class<?> managerCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.oaid.MusicOaidUploadManager");
            if (managerCls != null) {
                Method mo = HookKit.findMethodQuiet(module, f, "MusicOaidUploadManager#o",
                        managerCls, "o", String.class);
                if (mo == null) {
                    mo = HookKit.findByNameQuiet(module, f, "MusicOaidUploadManager#o",
                            managerCls, "o");
                }
                if (mo != null) {
                    HookKit.install(module, f, "MusicOaidUploadManager#o", mo,
                            HookKit.skipVoid(module, f, "MusicOaidUploadManager.o"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MusicOaidUploadManager hook failed", t);
        }

        // 2. AdOaidUploadTask.init()V -> skipVoid
        try {
            Class<?> taskCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.process.task.app.create.AdOaidUploadTask");
            if (taskCls != null) {
                Method mInit = HookKit.findMethodQuiet(module, f, "AdOaidUploadTask#init",
                        taskCls, "init");
                if (mInit != null) {
                    HookKit.install(module, f, "AdOaidUploadTask#init", mInit,
                            HookKit.skipVoid(module, f, "AdOaidUploadTask.init"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdOaidUploadTask hook failed", t);
        }
    }
}
