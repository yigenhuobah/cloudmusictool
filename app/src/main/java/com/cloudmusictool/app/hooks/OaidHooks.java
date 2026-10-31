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
                Method mo = HookKit.findMethodQuiet(managerCls, "o", String.class);
                if (mo == null) {
                    mo = HookKit.findByNameQuiet(managerCls, "o");
                }
                if (mo == null) {
                    // quiet 定位失败必须显式记账，否则该挂载点既不进 expected 也不进 failed。
                    HookKit.markMountMissing(module, f, "MusicOaidUploadManager#o");
                } else {
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
                Method mInit = HookKit.findMethodQuiet(taskCls, "init");
                if (mInit == null) {
                    HookKit.markMountMissing(module, f, "AdOaidUploadTask#init");
                } else {
                    HookKit.install(module, f, "AdOaidUploadTask#init", mInit,
                            HookKit.skipVoid(module, f, "AdOaidUploadTask.init"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdOaidUploadTask hook failed", t);
        }
    }
}
