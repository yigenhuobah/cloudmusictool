package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * PV-04 APM 上报抑制：抑制 APM 性能数据上报与后台上传。
 */
public final class ApmHooks {

    private ApmHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.APM;

        try {
            Class<?> statCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.core.statistic.StatisticImpl");
            if (statCls != null) {
                Method mLogApm = HookKit.findByNameAndArity(module, f, "StatisticImpl#logApm",
                        statCls, "logApm", 1);
                if (mLogApm == null) {
                    mLogApm = HookKit.findByNameQuiet(module, f, "StatisticImpl#logApm",
                            statCls, "logApm");
                }
                if (mLogApm != null) {
                    HookKit.install(module, f, "StatisticImpl#logApm", mLogApm,
                            HookKit.skipVoid(module, f, "StatisticImpl.logApm"));
                }

                Method mForceApmUpload = HookKit.findMethodQuiet(module, f,
                        "StatisticImpl#forceApmUpload", statCls, "forceApmUpload");
                if (mForceApmUpload == null) {
                    mForceApmUpload = HookKit.findByNameAndArity(module, f,
                            "StatisticImpl#forceApmUpload", statCls, "forceApmUpload", 0);
                }
                if (mForceApmUpload == null) {
                    mForceApmUpload = HookKit.findByNameQuiet(module, f,
                            "StatisticImpl#forceApmUpload", statCls, "forceApmUpload");
                }
                if (mForceApmUpload != null) {
                    HookKit.install(module, f, "StatisticImpl#forceApmUpload", mForceApmUpload,
                            HookKit.skipVoid(module, f, "StatisticImpl.forceApmUpload"));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " StatisticImpl APM hook failed", t);
        }
    }
}
